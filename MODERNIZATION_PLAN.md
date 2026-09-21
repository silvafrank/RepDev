# RepDev Modernization Plan

## 0. One correction first

There's no Qt anywhere in this codebase — it's **Java + SWT** (Eclipse's native
widget toolkit: `swt-win/swt.jar`, `swt-lin/`, `org.eclipse.swt.*` everywhere).
That actually changes the plan for the better: SWT is a thin wrapper over real
native widgets (Win32/GTK), so there's no Qt/C++ layer to strip out — the whole
UI is Java, and everything below it (parsing, sessions, config) already is too.
The port is JVM-UI-framework-out, not language-out.

## 1. What RepDev actually is

Not really "an IDE" in the generic sense — it's a **terminal-automation client
for Symitar (Jack Henry) core banking hosts**. It opens a Telnet or SSH session
to an AIX box, drives Symitar's `FM`/`Repgen` menu system like a human at a
terminal would (send command, `readUntil` a prompt, scrape the response), and
wraps that in an editor with syntax highlighting/autocomplete for Symitar's
proprietary "Repgen" report language. The editor half is a fairly normal code
editor. The session half is screen-scraping a live core banking system. That
distinction drives most of the architecture decisions below.

## 2. Current-state map

| Area | File(s) | LOC | Role |
|---|---|---|---|
| Entry/lifecycle | `RepDevMain.java` | 560 | boot, icon loading, config load/save, global hotkeys |
| Main window (god object) | `MainShell.java` | 4,198 | menus, toolbar, file tree, tabs, ~95 inner listener classes |
| Editor | `EditorComposite.java` | 2,656 | `StyledText`-based code editor, error squiggles, task tags |
| Core protocol | `DirectSymitarSession.java` | 2,031 | Telnet **and** SSH (via `jsch`), screen-scrape automation |
| Code folding | `FoldingManager.java` | 1,208 | section/paragraph folding for Repgen source |
| Repgen language | `parser/RepgenParser.java` + 19 files | 3,586 | hand-written parser, tokens, DB layout, keywords, functions — **the real IP** |
| Autocomplete | `SuggestShell.java` | 728 | IntelliSense-style suggestions |
| Options/config UI | `OptionsShell.java` | 945 | settings dialog |
| Config persistence | `Config.java` | 587 | Java-serialized settings, incl. session/credential fields |
| Auth/crypto | `RepDev_SSO.java`, `SymitarUserInfo.java` | 368 + ~90 | master-password AES envelope, SSH keyboard-interactive handling |
| Theming | `UITheme.java`, `Style.java`, `styles/*.xml` | — | dark mode, 11 XML color themes, custom-drawn toolbar icons |
| Everything else | Find/Replace, Goto, Snippets, Source Control glue, FM/Report shells, Compare | ~7,000 | supporting dialogs, mostly standard SWT `Shell` boilerplate |

**Total: ~26.4k LOC**, one flat package (`com.repdev` + `com.repdev.parser`),
Ant build, hand-managed per-OS native SWT jars, no dependency manager, no
tests directory, no CI.

## 3. What must survive the port intact

This is the contract for "maintain core functionality" — treat it as the
acceptance checklist for the new app, independent of what framework wins:

- Telnet **and** SSH connect to Symitar/AIX, multiple servers and multiple
  SYMs concurrently, with the exact `FM`/Repgen prompt-driven automation
  (`DirectSymitarSession`'s `readUntil`/prompt-response loop).
- Repgen syntax highlighting, folding, autocomplete, error-checking
  (`RepgenParser` + friends) — this parser is the single most valuable,
  most portable asset in the repo. It has zero SWT imports; it's pure domain
  logic and should be lifted essentially unchanged.
- Run Reports / Run FM directly from the editor, with live progress and
  prompt round-tripping.
- Project model: group RepGens/LetterFiles/HelpFiles/DataFiles per Credit
  Union, multi-project.
- Drag-and-drop / batch promote Test → Production.
- Date-archive / backup multiple files at once.
- Snippets with variables, Find/Replace, Goto Line/Section, Surround-With.
- EASE client menu selection support.
- RepDev SSO (master-password-gated credential store).
- In-house source-control glue (`SourceControl.java` — diff/sync against a
  local repo directory).
- The theming system and recently-added dark mode, DPI-aware icon scaling,
  collapsible side panels — these are genuine UX wins already built; don't
  regress them, generalize them (a modern UI framework gets you most of this
  for free, see §6).

## 4. Security findings (fix regardless of framework choice)

Found while reading the files above — flagging because "make sure it's
secure" was explicit in the ask, and a port is the cheapest time to fix these
(you're rewriting the call sites anyway):

1. **Config is Java native serialization** (`ObjectInputStream`/
   `ObjectOutputStream` in `Config.java`/`RepDevMain.java`) written to a
   plain file on disk. Deserializing untrusted/tampered data via
   `ObjectInputStream` is a well-known RCE gadget-chain vector. Low exploit
   likelihood here (local file, not network-facing) but it's a legacy
   pattern with no reason to carry forward — replace with JSON/a plain
   struct format.
2. **Hardcoded PBKDF2 salt** (`RepDev_SSO.java`: `SALT = "RepDev"`, same for
   every install). Salt should be random-per-install and stored alongside
   the ciphertext, not constant in source.
3. **Plaintext credential fields** in `Config`: `lastUsername`/`lastPassword`
   are written to disk whenever `DEVELOPER` or a leftover session leaves
   `FORGET_PASS_ON_EXIT` unset. Gated by a compile-time flag today, which
   means it's one build-config mistake away from shipping. Should be
   structurally impossible in production builds, and even then routed
   through an OS credential store (DPAPI/Keychain/libsecret), not app config.
4. **Telnet is still a first-class, default connection path**
   (`Config`'s default `port = 23`). Telnet is cleartext — username,
   password, and every report/account screen scraped over the wire are
   sniffable on the network path. This is a core-banking credential path;
   SSH should become the only supported transport in the new client, with
   Telnet (if kept at all) opt-in and loudly flagged.
5. **No MFA hook.** SSO today is a single master password gating a local
   credential vault. Worth adding TOTP/WebAuthn support at the vault layer
   during the rewrite, since credit unions increasingly require it.
6. **`JSch` (via `jsch.jar`)** is an unmaintained library (last real release
   years ago, several open CVEs in the SSH implementation space generally).
   The new client should move to a maintained SSH stack (e.g. Apache
   MINA SSHD, or a Rust/Go-native binding if the platform layer moves off
   the JVM).

None of this is exotic — it's the standard "hobby-project-grade secret
handling that outgrew its threat model" pattern, and it's very fixable.

## 5. Target architecture recommendation

**Recommendation: Kotlin + Compose Multiplatform for Desktop**, not a full
web/Electron rewrite. Reasoning:

- The two most valuable, most subtle pieces of this codebase —
  `RepgenParser` (3.6k LOC of hand-tuned domain parsing) and
  `DirectSymitarSession` (2k LOC of finicky terminal-automation timing) —
  are pure Java with **no SWT dependency**. Kotlin has zero-cost Java
  interop, so these get called as-is from day one. Nothing about this app's
  hardest-won logic needs to be rewritten in another language just to get a
  new UI. (Ladder rung 2: reuse what's already here.)
- Compose Multiplatform (JetBrains) renders with Skia — the same rendering
  engine backing the current IntelliJ/Android Studio "new UI" and JetBrains
  Fleet. It gives you, essentially for free, the things currently
  hand-rolled here: dark mode (`UITheme.java` custom filter-based repainting
  → a real `MaterialTheme`/color-scheme system), DPI-aware icon scaling
  (`RepDevMain.computeIconScale()` → Compose handles density natively),
  smooth animations, custom-drawable components without raw `GC`/`Image`
  pixel manipulation (see the hand-drawn moon/chevron icons in
  `RepDevMain.java` — trivial vector drawing in Compose).
  Immediate performance win too: SWT's `StyledText` re-tokenizes/repaints
  fairly naively on large files; a Compose-based editor (or embedding an
  existing fast text-editing component) with a proper virtualized text
  layout scales much better on the large generated Repgen sources this tool
  edits.
- Ships as a real native desktop app (via `jpackage`/Conveyor) on Windows and
  Linux — matching the existing `swt-win`/`swt-lin` split 1:1, no backend
  server required, no new attack surface from exposing core-banking
  credentials to a browser process.
- Stays open to more later without a second rewrite: Compose Multiplatform
  code is shareable with Compose for Web (Wasm) or Android if a browix   -based
  or mobile companion is ever wanted — but that's a "later, if asked for"
  door, not part of this plan.

**Alternatives considered and rejected:**

- **Electron/Tauri + full TypeScript rewrite.** Attractive shell (Tauri
  especially — small binary, Rust host), but it means re-implementing the
  parser and the terminal-automation session logic from scratch in
  TS/Rust, or keeping a JVM sidecar process just to run the old logic —
  which is the worst of both worlds (Electron's bloat *or* Tauri's
  lightness thrown away by bundling a JVM anyway). Only worth it if the
  long-term goal is a browser-hosted multi-tenant product, which isn't
  what's being asked for here.
- **Straight JavaFX port.** Also good Java interop, but JavaFX's own
  momentum/tooling has stagnated relative to Compose Multiplatform, and
  you'd still be hand-building the theme/DPI/dark-mode system that Compose
  gives natively.
- **Browser-based SaaS IDE** (server holds the Symitar session, multiple
  users connect over the web). Real functionality upside (access without
  local install, centralized audit logging) but it inverts the security
  model: a server now holds live core-banking credentials/sessions for
  potentially many credit unions, which is a much bigger security
  commitment than "make the desktop app secure." Worth a follow-up
  conversation once the desktop port is done, not a step-one bet.

## 6. Migration strategy — strangler fig, not big-bang

Don't stop feature work on the SWT app and disappear for a rewrite. Peel
layers out in an order that keeps a shippable app at every step:

1. **Extract the engine from the UI first**, inside the current repo, before
   touching Compose. `RepgenParser`/parser package and `SymitarSession`/
   `DirectSymitarSession` already have effectively no SWT coupling in the
   core logic (the only SWT touchpoints are `ProgressBar`/`Text` params
   passed in for progress callbacks — swap those for a plain
   listener/interface). This alone turns "port the app" into "write a new
   UI against an existing, already-decoupled engine module" — the highest
   leverage single step in this whole plan.
2. **Fix the security items in §4** while doing step 1 — you're already
   touching `Config`/session code to decouple it from SWT, so replace
   Java serialization with a plain format and route credentials through an
   OS credential store in the same pass.
3. **Stand up the Compose Multiplatform shell** against the extracted
   engine: file tree, tabs, and *one* working editor tab first (parity
   target: open a file, see highlighting/folding, save it). This is the
   riskiest UI piece (StyledText replacement) — prove it early.
4. **Port dialogs by usage frequency**, not by file size: Run Report/Run FM,
   Find/Replace, Options, Snippets, then the long tail (Compare, LPT Print,
   Source Control selection, EASE, etc.). Most of these are simple forms —
   low risk, mechanical.
5. **Cut over Telnet-by-default and the plaintext credential paths** as part
   of this rewrite, not before — no reason to fix it twice.
6. **Retire the Ant/manual-jar build** for Gradle (Kotlin Multiplatform's
   native build tool) — picks up dependency management, reproducible
   builds, and CI for free, none of which the current Ant setup has.

## 7. What you actually gain

- **Performance:** GPU-accelerated Skia rendering vs. SWT's OS-widget
  repainting; better large-file editor scaling; faster cold start via
  native `jpackage` images vs. the current shell-script `javac`-then-`java`
  dev loop in `start.bat`.
- **Functionality "for free":** real dark mode/theming (no more per-`Shell`
  filter + hand-maintained XML color files), animations/transitions,
  crisp multi-DPI without the manual scale-step math in `RepDevMain`,
  easier custom-drawn UI (icons, badges) without raw `GC` pixel plotting.
- **Security posture:** modern secret storage, SSH-only by default,
  maintained crypto/SSH libraries, no native-serialization config format.
- **Maintainability:** one build tool with real dependency management
  instead of hand-copied platform jars; a decoupled engine module that's
  independently testable (currently zero test infrastructure exists);
  Kotlin's null-safety and more expressive syntax over the ~95-inner-class
  `MainShell` god object.
- **Optionality:** Kotlin Multiplatform leaves a real (not theoretical) path
  to a web or mobile companion later without re-writing the engine again.

## 8. Effort/risk, honestly

This is a multi-month effort for a 26k-LOC app with a genuinely tricky
terminal-automation core, not a weekend port. The parser and session code
are the low-risk, high-value carryover; the editor UI (replacing
`StyledText`'s folding/squiggles/autocomplete popup behavior) is the one
piece worth prototyping first, since "does Compose's text editing feel as
good as SWT's for a dense code editor" is the real open question — not the
engine, not the theming, not the dialogs.

## 9. Immediate next steps

1. Prototype: extract `SymitarSession`/`RepgenParser` into a
   `repdev-engine` module with zero SWT imports, get it compiling standalone.
2. Prototype: a single Compose Multiplatform window that opens that module
   and renders one Repgen file with syntax highlighting — the "does the
   editor feel right" spike.
3. In parallel (independent of framework decision): kill Telnet-by-default
   and the plaintext-password code path — that's worth doing today
   regardless of what UI wins.
