# newdev decisions

Living log of choices made while building `newdev/`, and why. Companion to
`MODERNIZATION_PLAN.md` (the original plan) — this is the refinement asked
for after starting implementation.

## 1. jsch → Apache MINA SSHD (verified, not assumed)

jsch has had no release since 2018. Two real alternatives exist:
mwiede/jsch (an actively-maintained fork, drop-in API) and Apache MINA SSHD
(ASF project, most active, coroutine-friendly). Picked **MINA SSHD** because
the session layer is being rewritten anyway, so there's no drop-in-API
advantage to gain from the fork.

This was **tested, not assumed**: `SshTransportIntegrationTest.kt` spins up a
real embedded `SshServer` configured to match the AIX host's actual behavior
(keyboard-interactive only, two rounds — username then password — plain
`password` auth rejected), authenticates against it, opens a PTY shell
channel, and asserts bytes round-trip. Both tests pass:

```
SshTransportIntegrationTest > authenticates and echoes over the pty shell() PASSED
SshTransportIntegrationTest > rejects the wrong username() PASSED
```

Also fixed while porting: the original `DirectSymitarSession.java` loads
`known_hosts` but then sets `StrictHostKeyChecking=no`, so a *changed* host
key (possible MITM) was silently accepted anyway. `SshTransport.kt` uses
MINA SSHD's `ModifiedServerKeyAcceptor` extension point to throw instead —
same trust-on-first-use for genuinely new hosts, but a changed key now fails
loud. This wasn't in the original plan's security list; add it there.

## 2. Full Java → Kotlin port, or Kotlin-on-top?

Asked directly: should we go further than "Kotlin base to maintain the Java
code" and port off Java entirely?

**Yes to converting every source file to Kotlin. No to leaving the JVM.**

Those are different questions and worth separating:

- **Kotlin vs. Java, same JVM**: zero-cost interop, so this is a ratchet, not
  a rewrite — convert file by file, run the full app on a mix the whole way.
  Worth doing everywhere for null-safety on the parser/session code (this
  app's crashiest area historically), coroutines instead of the ad-hoc
  threads in the session/polling code, and data/sealed classes replacing the
  getter-setter and switch-statement boilerplate all over `com.repdev`. No
  reason to leave any file as `.java` long-term.
- **Off the JVM entirely (Rust/Go/Node/etc.)**: rejected. The two files that
  matter most — `RepgenParser.java` (3.6k lines) and
  `DirectSymitarSession.java`'s screen-scraping/timing logic (2k lines) —
  have no spec, no test suite, and encode years of undocumented AIX-quirk
  handling. Porting them to a different language means re-deriving that
  behavior blind. That's the exact risk `MODERNIZATION_PLAN.md` already
  rejected Electron/Tauri for (a full UI rewrite risking silent behavior
  drift) — leaving the JVM would do the same thing to the *engine*, which is
  the part that can least afford it. Kotlin avoids the question: keep the
  logic, translate the syntax, verify with tests as each file moves (see
  `PrintItemTest.kt` for the pattern), never a rewrite-from-understanding.

## 3. Toolchain actually verified, not assumed

Built for real on this machine (JDK 21 via Gradle toolchain, not the JRE 8
that's `JAVA_HOME` by default here):

```
:engine:build       BUILD SUCCESSFUL — 4/4 tests pass
:app-desktop:build  BUILD SUCCESSFUL
```

Gradle 8.11 + Kotlin 2.1.0 + Compose Multiplatform 1.7.3 + Apache MINA SSHD
2.19.0 all compile and link together with no version conflicts.
`app-desktop`'s `Main.kt` is a toolchain smoke test (a button, nothing more)
— real UI work is separate and not started.

## Updated order of work

1. ~~Scaffold + engine module + SSH spike (verified)~~ — done.
2. Continue porting `com.repdev` → `engine`, Kotlin-izing as each file moves:
   next up is `RepgenParser`/`Token`/`DatabaseLayout` (the actual value of
   this app) and `SymitarSession`/`DirectSymitarSession` (screen-scraping —
   port behavior first, refactor after there's a test around it).
3. Real `app-desktop` UI against the engine, editor pane first.
4. Carry forward the rest of `MODERNIZATION_PLAN.md`'s security list
   (plaintext credential storage, Java native serialization, Telnet-by-
   default) as each affected file gets ported.

## 4. `com.repdev.parser` ported — pure logic now, SWT/session-coupled logic deferred

All 16 files in `com/repdev/parser/` have been read. The small data classes
(`Division`, `Keyword`, `Argument`, `Function`, `Variable`, `SpecialVariable`,
`SpecialVariables`, `Include`, `Task`, `SectionInfo`) and the two
`getInstance()` singletons (`FunctionLayout`, `KeywordLayout`) are ported to
`engine/parser`, following the same `load(file)`/`load(lines)` factory
pattern already used for `DatabaseLayout` — no more process-wide singletons
reading a hardcoded relative path.

`Error` was renamed to `RepgenError` — the original name shadows Kotlin's
built-in `kotlin.Error` (a `Throwable`), confusing at any call site outside
this package.

The tokenizer core is ported as `RepgenTokenizer` (the `parse()` state
machine + DB-name/field token merging) plus free functions `getFullString`,
`isNumber`, `lineColAt`, `containsWord`, and `VariableRegistry` (the
`rebuildVars` scan). Same algorithm, same quirks preserved on purpose
(documented inline where a quirk looks like a bug — e.g. a stale
`first.after`/`first.after.after` re-read in the token-merge buffering step
that should probably read `last.after` instead; left as-is for behavior
parity, flagged in a comment, not silently "fixed"). One actual bug *was*
fixed: `:` at end-of-file no longer throws `ArrayIndexOutOfBoundsException`
on the `:(`-lookahead (bounds-checked instead of assumed in-range).

`BackgroundSectionParser`'s pure algorithm (walk tokens, track do/end depth,
emit a `SectionInfo` per top-level section) is ported as `parseSections()` +
`sectionAt()` in `SectionParser.kt`.

**Explicitly deferred, not silently dropped:**
- `Formatter.java` — needs `EditorComposite.getTabStr()`, a UI-layer concept
  (what string represents "one indent level" in the live editor). Port once
  the Compose editor owns that setting.
- `HiddenTextProvider` consumers (fold-region view↔model offset translation)
  — the interface itself already exists as forward-looking documentation;
  wiring it up needs the Compose editor's own fold-region model.
- `BackgroundIncludeParser` / `BackgroundSymitarErrorChecker` (inner
  `Thread` subclasses in `RepgenParser.java` touching `RepDevMain.mainShell`'s
  SWT `Table`s and a live `SymitarSession`) — need redesigning as coroutines
  once `SymitarFile`/`SymitarSession` and the Compose Problems-panel exist.
  Their pure sub-pieces (duplicate/unused-variable detection logic, task-
  comment scanning) can be extracted from them at that point; the threading
  and table-painting cannot be ported as-is because neither `Table` nor
  `RepDevMain` exist in the new app.
- The redraw-range/`HiddenTextProvider` translation that lived inside the
  original `parse()` — "what to repaint and how" is a UI decision, not part
  of tokenization; the ported `RepgenTokenizer.parse()` returns updated
  tokens only, no redraw side effect.

Next: `SymitarSession`/`DirectSymitarSession` (the screen-scraping session
layer) to unlock session-connect, Run Reports/Run FM, and the deferred
error-checker's server-side compile-check integration.

## 5. `SymitarSession` / `DirectSymitarSession` ported

Both files ported to `engine/`. `SymitarSession.kt` is the abstract contract
(unchanged shape, SWT `ProgressBar`/`Text` params replaced with
`RepgenProgressListener`/`PromptListener` function interfaces).
`DirectSymitarSession.kt` is the concrete AIX screen-scraper — the riskiest
file in the app to port (2k lines, no spec, no upstream tests, years of
AIX-quirk handling as literal byte sequences). Translated behavior first,
verified with `DirectSymitarSessionHelpersTest.kt` on the pieces that don't
need a live socket (queue selection, Symitar date parsing, EASE menu
parsing).

The wire-protocol methods now have a fake-terminal integration test too:
`DirectSymitarSessionIntegrationTest.kt` scripts a plain `ServerSocket` (the
Telnet path, port != 22) that writes a canned byte sequence — the
`Password:`/`[c` prompt markers plus a run of WINDOWSLEVEL=3 command frames
(`ESC 0xfe <body> 0xfc`, matching `Command.parse`'s own format) — and drives
`connect()` through the full AIX-login → SYM-login → BankingDate/ConsoleNumber
handshake. SSH's own auth handshake is already covered by
`SshTransportIntegrationTest`, so this test's job is purely the
screen-scraping protocol on top, not SSH; a second test drives the
`invalid login name or password` rejection path. Both pass:

```
DirectSymitarSessionIntegrationTest > connect and loginUser succeed against a scripted Telnet AIX host() PASSED
DirectSymitarSessionIntegrationTest > connect rejects an invalid AIX password() PASSED
```

One real trap found writing it: the fake server must not close its socket
right after writing the script (`.use { }` did, and the client's later reads
saw the connection reset instead of its own scripted bytes) — the socket has
to stay open until the client drops it. Also has to encode the script with
`Charset.defaultCharset()`, not assume ISO-8859-1: `DirectSymitarSession`
reads via a charset-less `InputStreamReader`, and its byte-oriented framing
(`0x1b`/`0xfe`/`0xfc` markers) only round-trips if both ends agree on the same
default codec, whatever it happens to be on the running JVM.

Decisions made along the way:

- **SSH and Telnet both kept.** `MODERNIZATION_PLAN.md`'s security list says
  Telnet, if kept, should be opt-in and loudly flagged — not deleted. Some
  consoles genuinely have no SSH enabled, and dropping Telnet would silently
  remove a feature the plan says to preserve. The original's `port == 22 →
  SSH, else → Telnet` branch is kept as-is; SSH now goes exclusively through
  `SshTransport` (no jsch anywhere in this file), and every Telnet connect
  now logs a loud plaintext-transport warning the original never printed.
- **`SymitarUserInfo.java` needs no port.** Its username-then-password
  round-tracking logic for jsch's keyboard-interactive callback is already
  reimplemented in `SshTransport.kt`'s private `SymitarKeyboardInteractive`.
- **`getReportSeqs`/`getFMSeqs` (on the base class) call `getFile()`
  directly** instead of the original's `new SymitarFile(sym, ""+seq,
  REPORT).getData()`, which used to reach back into a process-wide session
  registry to resolve `sym` to a live session. Same result, one fewer hidden
  global lookup.
- **One real bug fixed, not preserved:** `readUntil()`'s read loop used to
  keep appending `(char) -1` forever once the stream hit EOF instead of ever
  returning — a genuine hang, not a quirk worth keeping. It now throws
  `IOException` on end-of-stream, consistent with the "fix crashes/hangs,
  document behavioral quirks" rule this port has followed throughout.
- **The shared `lastActivity` clock is intentionally still shared** across
  every session instance (a companion object now, was a `static` field) —
  the external keep-alive watchdog uses it to avoid killing a console just
  because *that* console was quiet, as long as some other console on the
  same login was active. Not an accidental singleton; preserved on purpose.
- **A handful of numeric literals in the original source rendered as
  partially redacted** (`CONNECT_TIMEOUT_MS`, the keepalive poll interval,
  queue-array sizing, the message-ID counter seed, the `DecimalFormat`
  chunk-length pattern in `saveFile`) through every tool used to read the
  file. None of these are behaviorally load-bearing except the
  `DecimalFormat` pattern used in the save-file wire framing, which was
  reconstructed from its unmasked sibling (`DecimalFormat("000")`) as
  `DecimalFormat("X0000")`. The rest are buffer/array sizing or cosmetic
  values where any reasonable reconstruction behaves identically.

`com/repdev/SourceControl.java` (171 lines) has now been read in full: every
branch is an SWT `MessageBox` prompt ("files don't match, compare/overwrite
SYM/overwrite repo?") wrapping a call to `SymitarFile.getData()`/`.saveFile()`.
There's no pure logic to extract beyond what `SymitarFile.kt`'s doc comment
already anticipated — this is a UI controller, not an engine concern. It's
deferred to `app-desktop` (a service that diffs local vs. repo vs. SYM copies
and asks the *Compose* UI which action to take, then calls
`session.getFile`/`session.saveFile` directly), same as `Formatter.java` and
the `BackgroundIncludeParser`/`BackgroundSymitarErrorChecker` deferrals above.
Not silently dropped — no port needed in `engine/` at all.

## 6. Syntax highlighting split into `RepgenHighlighter` + `RepgenTheme`

`SyntaxHighlighter.java` (497 lines) mixed two concerns: *what a token is*
(classification tree) and *what color that means* (theme loading + SWT
`Color`/`Font` allocation). Ported as two pure, UI-independent files instead
of one, mirroring the split `RepgenTokenizer` already draws between
tokenizing and painting:

- **`RepgenHighlighter.kt`** — `Token` → `HighlightCategory`, a straight port
  of `getStyleAtView`'s branch order (comment/task → string → date → number →
  struct1/struct2 → function → keyword → special-var → local-var → normal).
  Dropped: `tok.getSpecialBackground()`, a live search-match/snippet-field
  paint overlay — per-render UI state owned by whoever holds the caret, not
  part of the language model (same reasoning `Token.kt` used to drop
  `specialBackground`).
- **`RepgenTheme.kt`** — a pure port of `Style.java`'s XML reader plus the
  theme-value half of `loadStyle`, returning an immutable data class instead
  of SWT `Color`/`Font` handles the caller must dispose. All 12 existing
  `styles/[name].xml` files load unmodified — same tags, same attributes, no
  migration.

Decisions made along the way:

- **Task-tag matching is case-insensitive in practice, not by explicit
  check.** `RepgenParser`'s `taskTokens` list and the original's comparison
  both look case-sensitive (`{"todo", "fixme", ...}` vs. lowercase string
  equality), but `RepgenTokenizer` already lowercases every identifier's
  `str` before the highlighter ever sees it — so `TODO:`, `Todo:`, and
  `todo:` all reach `RepgenHighlighter.classify` as the same `"todo"` token.
  Confirmed by test, not assumed from reading the Java.
- **Decimal-literal coloring is a preserved quirk, not a bug.** `"45.6"`
  tokenizes as three separate tokens (`"45"`, `"."`, `"6"`) — `RepgenTokenizer`
  never merges digits across a `.` — so only the digit runs match the number
  pattern and the `.` itself renders `NORMAL`. Original behavior, kept as-is
  and documented in the test rather than "fixed," since nothing about it
  crashes or hangs.
- **`$rand`/`$red`/`$green`/`$blue` random-color schemes ported; `$!RRGGBB`
  was not.** Grepped all 12 shipped themes for actual scheme usage before
  deciding: `rand`/`red`/`green`/`blue` are each used by a real theme file,
  the `$!RRGGBB`-with-16-as-wildcard scheme is used by none. Skipped per
  YAGNI — add it if a theme ever needs it.
- **3-digit hex shorthand's `*16`-not-`*17` expansion preserved as-is.**
  `Style.java` expands a 3-digit hex color by multiplying each nibble by 16
  (not the CSS-standard "duplicate the digit," i.e. `*17`), which slightly
  darkens every channel from what a web author would expect. No shipped
  theme uses the 3-digit form, so this is a dormant quirk, not a live bug —
  kept rather than silently "corrected" to CSS semantics.
- **`UITheme.java` needs no port.** Read in full (180 lines): pure SWT
  dialog-chrome dark-mode logic (title bar, scrollbar, menu colors via
  Win32 `DWM`/`uxtheme` calls). Fully superseded by Compose's
  `MaterialTheme` — there's no engine-side concept here to extract.
- **A tool-chain display bug hit freshly-authored code, not just old
  source.** Long hex-digit-run literals (6-digit hex colors) get silently
  mangled wherever they're redisplayed in this session — confirmed on both
  historical file reads and on hex literals I typed myself this session
  (a Bash `printf` of `styles/GhostRider.xml`'s own colors dropped every
  literal `C` character and altered digit groups in the same output that
  correctly showed the surrounding text). Two workarounds adopted: (1)
  `RepgenTheme.kt`'s constants are built from a decimal-channel `rgb(r, g,
  b)` helper instead of hex literals — decimal values ≤255 never trigger it;
  (2) `RepgenThemeTest.kt` derives its expected colors from the theme's own
  source XML via a small independent regex at test time, rather than from
  hex constants I'd have had to eyeball and retype.

`RepgenHighlighter`/`RepgenTheme` are now wired into `app-desktop`'s
`EditorPane` via `BasicTextField`'s `visualTransformation` — the underlying
text/cursor handling is untouched, only the painted `AnnotatedString` is
swapped in per recomposition. Definitions (`db.txt`/`functions.txt`/
`keywords.txt`/`vars.txt`) and the default style load from the same
repo-root-relative convention the original used (plain files next to the
running app), found by walking up from the working directory; missing files
degrade to empty definitions / `RepgenTheme.DEFAULT` rather than failing to
start. Known ceiling, flagged with a `ponytail:` comment: the whole file is
re-tokenized on every keystroke rather than incrementally around the edit —
fine at typical `.PRO`/`.SET` sizes, revisit with `RepgenTokenizer.parse`'s
`start`/`end`/`oldEnd` params if large files ever feel laggy. Theme selection
is hardcoded to `default.xml` for now — TODO once `Config`/a settings UI
exists in `app-desktop` to read `Config.style` instead.

## 7. Code folding: `FoldingModel` ported, gutter UI deferred

`FoldingManager.java` (1,208 lines) mixed three concerns: (1) detecting which
head/end token pairs are foldable, (2) physically excising/reinserting hidden
lines from a live SWT `StyledText` buffer and keeping a hand-rolled model/view
coordinate system consistent across that mutation, and (3) painting the
gutter (triangles, guide lines, indent guides) and handling gutter clicks.

Only (1) ported so far, as `FoldingModel.kt` (pure, unit-tested). (2) has no
equivalent to port: Compose's `EditorPane` never deletes text — `openFile.
content` is always the complete canonical source, and `RepgenTheme`/
`RepgenHighlighter` are already re-run from scratch on every recomposition
(§6). Folding fits the same shape: `FoldingModel.collapsedView` recomputes
the folded display text and a line-mapping fresh from the current text and
fold-state set, the same call already made for tokenizing on every keystroke
rather than incrementally patching. That eliminates essentially the entire
second concern — `viewToModel`/`modelToView`/`shiftFoldsBelow`/
`preBatchHiddenTexts`/orphan-head-and-closer skipping all existed solely to
keep a physically-mutated buffer's coordinates consistent; nothing here ever
needs them because nothing is ever hidden from the tokenizer.

(3) — gutter triangle/plusminus painting, always-on guide lines, VSCode-style
indent guides, click-to-toggle — isn't ported yet. `EditorPane` has no
gutter at all currently (no line numbers rendered either), so there's
nothing to wire a click handler into. Next step once that exists.

Decisions made along the way:

- **`computeFoldableRanges` is a straight port of `recomputeRanges`'s
  stack-based matching** (same token filters skipping string/date/paren
  openers and closers), verified against nested blocks, bracket comments,
  and the "same-line blocks don't fold" rule via test.
- **Fold state is just a `Set<Int>` of collapsed header lines**, not a list
  of `FoldRegion` objects carrying cached hidden text — there's nothing to
  cache when the source is never mutated; the hidden text is just
  `text.split("\n")` sliced by the range already computed from the same text.

Code folding's gutter UI, `EditorComposite`'s undo/redo integration
(`pushFoldUndo`), and `HiddenTextProvider`'s remaining consumers (Find/
Replace, goto-definition, unused-variable scanning) are still open — next
after the gutter exists to click.

## 8. `RepgenDiagnostics`: duplicate/unused-variable checks ported, the checker thread itself deferred

`RepgenParser.BackgroundSymitarErrorChecker` (a `Thread` subclass) mixes three
things: a server round-trip (`SymitarSession.errorCheckRepGen`), two purely
local scans (duplicate-variable and unused-variable detection), and painting
the results into an SWT `Table`. §4 already deferred the whole class pending
`SymitarFile`/a Compose Problems panel — this section only pulls the two pure
scans out as `RepgenDiagnostics.kt`, same two-stage split as `RepgenHighlighter`
(§6) and `FoldingModel` (§7): logic now, UI wiring once there's a panel to
paint into.

- **`findDuplicateVariables`** is a straight port of the original's
  `count-occurrences-by-equals` loop: for each variable declared in the file
  being checked, count how many `Variable`s across the whole list share its
  name (`Variable.equals` is name-only, see `Variable.kt`) and emit one
  warning per redundant declaration — not one per name. Preserved exactly,
  including that the count is scanned across *all* files, so it also flags a
  local variable shadowing one declared elsewhere.
- **`findUnusedVariables`** ports the three-pass "is this name referenced
  anywhere" scan: own tokens, then every cached include file's tokens, then
  (fallback) a case-insensitive whole-word search over hidden/folded text for
  a variable used only inside a collapsed fold — each pass skips tokens
  inside `DEFINE` bodies/date/string literals/comments and tokens that are
  really keyword or special-variable references, using the already-ported
  `Token` flags, `KeywordLayout.contains`, and `SpecialVariables.contains`.
  The `Config.getListUnusedVars()` on/off toggle is left to the caller (a
  UI-layer setting, not part of the detection logic).
- **Reuses the already-ported `RepgenError`** (`fileName`/`description`/
  `line`/`col`/`type`) rather than inventing a second row type — it's the
  same `com.repdev.parser.Error` the original's `errorList` held, already
  pulled out of the SWT `Table` when the syntax/theme split (§6) needed it.
- Six unit tests in `RepgenDiagnosticsTest.kt` cover both functions:
  duplicate declarations (one warning per occurrence, not per name), no
  warning for a single declaration, unused-vs-used via own tokens, used via
  an include file's tokens, used only inside a folded/hidden region, and a
  token that only coincidentally matches a keyword name not counting as a
  usage. All pass, and the full `./gradlew build` remains green.

The checker thread's scheduling/session plumbing and the `Table` painting
are still deferred (§4) — next after `SymitarFile` and a Compose Problems
panel exist to wire these two functions into.

## 9. `TaskScanner`: `[todo: ...]`-style comment tags ported

Same shape again: `RepgenParser`'s task-tag scan (the loop building `tblTasks`
rows for `[todo: ...]`/`[fixme: ...]`/etc. comments) is pure — a token-list
walk plus substring math over the canonical source, no `Table`/`Display`
touch — so it ports as `TaskScanner.findTasks(tokens, canonicalSource,
fileName): List<Task>` in `parser/`, leaving row-painting for the same future
Problems panel as §8's diagnostics.

- **`Task.kt` already existed** (a prior pass had ported the plain data
  class alongside `RepgenHighlighter`, which already needed the same
  `TASK_TOKENS` set to color these comments) — reused as-is rather than
  redeclared; `TaskScanner` also reuses `RepgenHighlighter.TASK_TOKENS`
  instead of a second copy of that literal list.
- **Preserved rather than fixed: a real off-by-one in the original.** When a
  task tag is closed by a `]` before the next newline, the original's
  `bracketPos = indexOf(']') - 1` combined with an exclusive-end substring
  drops the description's last real character too (`[todo: fix thing]` scans
  as `"todo: fix thin"`, not `"...thing"`). Cosmetic, not a crash, so it's
  carried over rather than silently corrected — documented in `TaskScanner`'s
  kdoc and pinned by a test asserting the truncated string.
- **`Task.line`/`Task.col` stay 0-based**, matching the original's own
  inconsistency: `RepgenParser` adds 1 to `lineColAt`'s result for its Error
  rows (§8's `Diagnostic`) but not for Task rows. Also preserved, also
  documented, since a display panel doesn't exist yet to normalize it at
  render time.
- Five unit tests cover: a found `[todo: ...]` tag with its (truncated)
  description, `fixme`/`bug`/`bugbug` mapping to their own `Task.Type`,
  description cut at a newline when there's no bracket first, a bare
  identifier named `todo` outside a comment not counting, and `todo` not
  followed by `:` not counting. All pass; `./gradlew build` remains green.

## 10. `SuggestionEngine`: autocomplete candidate list ported

`SuggestShell.update()` mixes two things: computing *what* to suggest (pure —
walks already-parsed `Variable`/`Function`/`Keyword`/`Record` lists and the
caret-context token) and painting the SWT popup `Table`/`StyleRange` tooltips
(not pure). Same split as §6/§8/§9: the former is
`SuggestionEngine.computeSuggestions(current, tokenStr, vars, specialVars,
functions, keywords, db): List<Suggestion>` in `parser/`; the latter stays
UI-layer, to be wired up whenever the Compose editor gets an autocomplete
popup.

- **Dropped from this port, not yet ported at all:** snippet-mode
  suggestions (`SnippetManager`/`Snippet` — that whole subsystem doesn't
  exist yet in `newdev`, see `MODERNIZATION_PLAN.md`'s dialog order) and the
  rich bold/italic `StyleRange` tooltip formatting (replaced with a plain
  `Suggestion.tooltip` string — a Compose tooltip can restyle at render time,
  same call made for `RepgenHighlighter`'s `specialBackground`). The
  original's "don't pop up inside a comment/string/date" guard also stays
  with the caller, since it needs live caret/StyledText state this function
  never sees.
- **DB-subfield completion's field-sort quirk done cleanly, not mechanically
  ported.** The original sorts `dRecord.getFields()` in place via an aliased
  `ArrayList` reference (`sortedFields = dRecord.getFields(); Collections
  .sort(sortedFields);`) and then iterates `dRecord.getFields()` again,
  silently reading back the now-sorted list. `Suggestion` computation instead
  just does `dRecord.fields.sortedWith(...)` directly — same observable
  result (sorted fields, filtered by prefix), without replicating the
  confusing double-reference mechanism. Preserving *behavior* here doesn't
  require preserving that particular Java aliasing trick.
- **New types, checked against the existing codebase first** (the
  `Diagnostic`/`RepgenError` duplication in §8 was a lesson learned):
  `Suggestion`/`SuggestionKind` have no prior equivalent anywhere in
  `engine/`, so they're declared fresh in `SuggestionEngine.kt`.
- Eight unit tests cover: variable-prefix filtering and sort order, the
  "@"-prefixed special-var crowding rule (hidden on an empty prefix, shown
  once the user types something matching), function suggestions with
  argument-name formatting and a trailing `(` in the insert value, record
  matching by prefix, DB-subfield listing after a bare `:` (sorted,
  unfiltered), the two-step `.before` walk back through the colon when
  typing mid-subfield (`account:num` tokenizes as three separate tokens
  since `"num"` alone isn't a real field name — the merge rule that would
  otherwise fuse `record:field` into one token only fires for known field
  names), and an unknown record after `:` yielding nothing. All pass; the
  full `./gradlew build` remains green.

Still pending before this is usable end-to-end: the Compose autocomplete
popup itself (§ deferred alongside the folding-gutter UI work — no way to
visually verify either without a screenshot, so both stay lower priority
than engine-side porting for now) and the snippet subsystem it would
eventually merge with.

## 11. `AutoIndenter`: Enter-key indent computation ported

`EditorComposite.computeAutoIndent` — what indent a freshly-inserted line
after Enter gets — is pure token/string math (a stack walk over
`Token.isRealHead()`/`isRealEnd()` plus leading-whitespace measurement), with
exactly one piece of SWT-adjacent state woven in: the fold view→model offset
translation, needed only because the original editor physically deletes
folded text from the buffer. Ported as `AutoIndenter.computeIndent(tokens,
text, caretOffset, tabStr): String` in `parser/`.

- **The fold-offset translation is dropped entirely, not replaced.** §7's
  `FoldingModel` design note already established that the Compose editor
  never physically hides folded lines — `openFile.content` is always the
  full canonical text, folding only changes what's *painted*. So there's
  never a second "view" coordinate space for a caret offset to need
  translating out of; `caretOffset` is always already a model offset here.
- **The `parser == null || !doParse` bail-out for non-RepGen files (help/
  letter) is left to the caller.** That's "should I even call this
  function," a UI-layer routing decision, not part of the indent
  computation — an empty `tokens` list already falls through to the same
  verbatim-copy-current-line-indent result the original produced for those
  files.
- **`getTabStr()` (the configured tab-vs-spaces string) is passed in as
  `tabStr` rather than read from `Config`** — keeps this function
  free of a global-singleton dependency; the caller (once there's an editor
  wired to `Config`) is responsible for computing it the same way
  `EditorComposite.getTabStr()` did.
- Six unit tests cover: no tokens → verbatim copy of the current line's
  indent, one level inside an open `do`, two levels nested, a closed block
  contributing no indent, a bare `then` bumping one extra level, and the
  indent-unit-measured-off-the-current-line behavior (a body already using
  two-space indents keeps getting two-space indents rather than the
  configured default). All pass via real `RepgenTokenizer` output, not
  hand-built tokens, same pattern as `TaskScannerTest`/`FoldingModelTest`.
  Full `./gradlew build` remains green.

Not yet wired to anything: `EditorPane`'s `BasicTextField` has no Enter-key
interception yet (Compose's `BasicTextField` doesn't expose one as directly
as SWT's `verifyText`) — this is pure logic ready for that hookup once the
editor grows real key handling instead of plain text-diffing.

## 12. `LineSurrounder`: Surround-With ported

`EditorComposite.surroundEachLineWith` (backing the Surround With dialog —
wrap each selected line in a prefix/suffix, e.g. for building a quoted RepGen
string line-by-line) is pure string work once the selection is already
resolved to a `[startLine, endLine]` pair: split on lines, prepend/append,
rejoin. Ported as `LineSurrounder.surround(text, startLine, endLine, start,
end, escapeBadChars): String` in `parser/`.

- **The original's manual trailing-`\n`/`\r` trim loop has no equivalent
  here.** It existed only because `StyledText.getText(startOffset,
  endOffset - 1)` could still include a line terminator depending on how the
  offsets landed; Kotlin's `String.split("\n")` never includes the
  delimiter in the first place, so there's nothing left to strip.
- **Selection resolution stays with the caller** (`getSelectionCount() == 0
  ? current line : selection.x/y → line numbers` in the original) — that's
  UI cursor/selection state, not part of turning a line range into wrapped
  text.
- **`escapeBadChars`'s single-entry bad-char list (`"`) is preserved as-is**,
  including the original's own `//TODO: Add more to list later` — not
  expanded, since nothing downstream has asked for more than the one
  RepGen-string-breaking character.
- Four unit tests cover: wrapping only the selected range, a single current
  line (start == end), the `"` → `"+CTRLCHR(34)+"` escape, and an end line
  past the buffer's last line getting clamped the same way the original's
  `Math.max(lineCount - 1, startLine + 1)` did. All pass; full
  `./gradlew build` remains green.

Not yet wired to a dialog: `SurroundWithShell.java` (the UI that collects
start/end strings and calls this) hasn't been ported to Compose yet — next
in the dialog-porting order per `MODERNIZATION_PLAN.md` §6 step 4.

## 13. `BlockMatcher`: caret block-matching ported

The token-stack walk inside `EditorComposite.handleCaretChange` — given the
token under the caret, find its matching head/end (`do`→`end`, `[`→`]`,
etc.) so the editor can highlight the pair — is pure token-list logic.
Ported as `BlockMatcher.findMatch(tokens, caretTokenIndex): Int?` in
`parser/`. Everything else in `handleCaretChange` (snippet-mode caret exit
checks, `Section`-list refresh on line change, `redrawRange`/
`specialBackground` painting) is editor display/session state, left for the
UI layer once there's a Compose caret to attach it to.

- **Kept the original's own hand-rolled guard conditions, not
  `isRealHead`/`isRealEnd`.** Those two answer "is this token unambiguously
  real" using only the token's own flags; the original's inline stack-walk
  guards answer a different question — "is this real *or* is it the specific
  quote that closes what's already on the stack" — which needs the stack's
  current top, not just the token's own state (a `"` two lines into a string
  isn't a second real open-quote, but the string's *closing* `"` is, judged
  against what opened it). Ported verbatim rather than collapsed into the
  other helper, since they're genuinely different questions despite the
  visual overlap.
- **Splits the original's single method into `findForward`/`findBackward`**
  (head-matches-forward vs. end-matches-backward) purely for readability —
  same two stack loops the original had inline in an if/else, no behavior
  change.
- **Token index in, token index out** rather than the original's `Token`
  object plus a separately-tracked `tokloc` int (the original needed both
  because `tokloc` was already one past the found token when the loop
  broke, from a *different* preceding search over the whole token list) —
  the caller finds the caret's token index however it locates the caret
  (`EditorComposite`'s own linear scan is itself pure and small enough not
  to need a named port; TODO if it turns out to want one later).
- Six unit tests, all against real `RepgenTokenizer` output: head→end
  forward, end→head backward, nested blocks resolving to the correct pair
  (not the nearest end), an unmatched head returning null, a non-head/end
  token returning null, and a `"`-containing bracket comment not confusing
  the quote-nesting guards. All pass; full `./gradlew build` remains green.

Not yet wired to anything: no Compose caret-position tracking exists in
`EditorPane` yet to call this from — same "logic ready, UI pending" state as
§11's `AutoIndenter`.

## 14. `FoldSymbolMatcher`: fold-expansion-on-jump-to-definition ported

`EditorComposite.expandFoldContainingSymbol` — the "jump to definition landed
nowhere, maybe it's inside a folded block, try expanding one and retry" fallback
used by `gotoDefinition` — has three private one-line-of-text classifiers
underneath the fold-list walk/toggle/retry orchestration:
`isDefineHeaderLine`, `isProcedureHeaderForSymbol`, `containsAssignmentOf`.
All three are pure string predicates over an already-trimmed-and-lowercased
line; ported as `FoldSymbolMatcher` in `parser/`. The orchestration around
them (`folding.getFoldedRegions()`, `folding.toggleAtLine`, caret
positioning, the retry loop, the `sec`/section-list refresh) stays with the
editor/session layer that owns fold state and the caret — none of it exists
in `newdev` yet since there's no Compose fold-toggle UI (§7's `FoldingModel`
design note) or `SymitarSession`-backed jump-to-definition.

- **Trim/lowercase stays the caller's job**, same contract the original had
  (`trimmedLowerLine` was itself a private helper wrapping
  `txt.getLine(line).trim().toLowerCase()` with an `IllegalArgumentException`
  guard for an out-of-range line) — that bounds-checked buffer read is
  StyledText-specific and doesn't need a pure-logic port; whatever a
  Compose caller uses for the same "the buffer might have changed under me"
  guard, string-level normalization happens before calling these three.
- Straight one-to-one port otherwise, no logic changes — three small unit
  tests (define-header detection, procedure-header exact-symbol matching
  including the no-partial-match-on-`foobar`-vs-`foo` boundary check, and
  whole-word assignment detection including the `xy=` false-positive guard)
  covering the interesting edge cases each already had documented inline in
  the original's Javadoc. All pass; full `./gradlew build` remains green.

Not yet wired to anything: same as `AutoIndenter`/`BlockMatcher` above —
needs a Compose editor with real fold state and a jump-to-definition action
before there's a caller.

## 15. `UndoManager`: undo/redo history ported, fold-undo dropped

`EditorComposite`'s undo/redo (`TextChange`, `undos`/`redos` stacks, `undo`/
`redo`/`commitUndo`) is captured live off `StyledText` via an
`ExtendedModifyListener`: every real edit pushes `(start, newLength,
oldText)` onto whichever stack `undoMode` currently points at, and commit
markers group a run of edits into one logical undo step. Ported as
`UndoManager` in `parser/` — a plain `text: String` buffer plus `edit`/
`editSilently`/`commit`/`undo`/`redo`, no widget involved.

- **The core trick carries over unchanged:** replaying an edit during `undo`/
  `redo` goes through the exact same capture path as a live edit, just
  pointed at the *other* stack — so the reverse of a reverse is automatically
  the original edit again, with no separate "compute the inverse" branch.
  `RecordTarget` (`NONE`/`UNDO`/`REDO`) is that one flag, same job as the
  original's three-state `undoMode` (0/1/2).
- **Fold-op undo (`FOLD_OP_*`, `pushFoldUndo`, `applyFoldUndo`/
  `applyFoldRedo`) is dropped, not ported.** It existed only because the old
  editor physically deleted folded text from the buffer, so a fold/unfold had
  to be its own undo step. §7 already established the new `FoldingModel`
  never mutates the buffer — folding is purely a view computation over the
  full canonical text — so there is nothing left for a fold operation to
  undo. This isn't a "defer for later" item like the other UI hookups below;
  the thing it used to solve doesn't exist anymore.
- **Caret offset and scroll position (`topIndex`) are dropped, not carried
  through.** Same split as every other module here: `undo`/`redo` return the
  `TextEdit`s they replayed so a caller can move its own caret to the last
  one's `start`, but this class only ever tracks `text`.
- **A real quirk preserved, not fixed:** the original's `undo()` has a
  `redos.clear()` call commented out — new edits after an undo deliberately
  do *not* wipe the stale redo stack. Kept as-is (a test locks this in) since
  it's a preserved quirk, not a crash.
- Five unit tests cover: a single edit round-tripping through undo and redo,
  uncommitted edits grouping into one undo step while `commit()` separates
  them, `editSilently` leaving no undo history, the stale-redo-after-edit
  quirk above, and the size limit evicting the oldest entry. All pass; full
  `./gradlew build` remains green.

Not yet wired to anything: no Compose caret or live-edit-capture model
exists in `EditorPane` yet (it currently diffs whole `TextFieldValue`s on
recomposition rather than reporting discrete edits) — needs that before
`edit()` has a real caller.

## 16. `lineHighlight`/`setLineColor`: skipped, no port

`EditorComposite.lineHighlight()` is SWT `StyledText` incremental-redraw
bookkeeping (`setLineBackground`, `redrawRange`, `redraw()`,
`getOffsetAtLine`) tracking `lastLine` purely to avoid repainting the whole
buffer on every caret move. The one fragment that looks like "logic" —
compute which two line ranges need redrawing, the old current line and the
new one — only exists to feed manual partial-redraw calls Compose doesn't
have; `Main.kt` already recomputes styling from scratch on every
recomposition (same call made for syntax highlighting). Nothing here
survives the platform change, so nothing was ported.

## 17. `Formatter`: whole-file reformat ported, `isAlphaNumeric`/`isAlpha`/`isNum` skipped

`com.repdev.parser.Formatter` (backing `EditorComposite.sendToFormatter`) rewrites an
already-tokenized file: collapse horizontal whitespace to one space between tokens (per the
`noSpaceBefore`/`noSpaceAfter` punctuation lists), force a reindented newline after/before
`do`/`end`, and otherwise leave string/comment contents and the source's own line breaks alone
(reindented). Entirely pure token/string work — its only non-pure dependency was a static call to
`EditorComposite.getTabStr()`, now a constructor parameter (`tabStr`), same as §11's
`AutoIndenter`/§12/13/14. Ported as `Formatter(oldFile, tokens, tabStr).format()` in `parser/`.

- **Quirks preserved exactly, not cleaned up** (documented in the class KDoc rather than repeated
  here): the punctuation-list checks are plain substring `contains`, so the compound token `:(`
  never matches either list (its characters appear in the wrong order in `noSpaceAfter`'s literal
  string) and so never gets its surrounding space suppressed; the `else`/`end`-after-`end`
  newline-suppression looks only at what the *next* token's text is, not whether the *current*
  token is actually `end` (harmless in practice, since `do else`/`do end` isn't valid RepGen).
- **Every formatted file ends with a trailing space** (unless the last token is itself in
  `noSpaceAfter`) — the original's space-or-not check for the last token takes the same "no
  `after` token to check against noSpaceBefore" path as any other token, which falls through to
  appending a space. Not a bug, preserved; caught by writing tests against a hand-trace of the
  algorithm rather than a guess, after the first draft's guessed expected strings all failed.
- **`isAlphaNumeric`/`isAlpha`/`isNum` skipped entirely, no port.** All three are one-line
  char-class checks (`str.isNotEmpty() && str.all { it.isLetter() && it.isDigit() }`-shaped) with
  no state and no RepGen-specific meaning — stdlib already covers them (`Char.isLetter()`/
  `isDigit()`/`isLetterOrDigit()`, `String.all { }`). Whoever ports `gotoDefinition` (the only
  caller, still unported) can inline the one-liner rather than call a dedicated wrapper.
- Nine unit tests, built from a hand-trace of the algorithm against real `RepgenTokenizer`
  output (first draft's expectations were wrong, not the port - see above): horizontal-whitespace
  collapsing, no-space-before punctuation with the comma's asymmetric spacing, the newline+indent
  forced after `do`, the double-newline-and-outdent forced after `end`, the `else`-after-`end`
  suppression, two-level nested indent, blank-line preservation outside any block, and untouched
  string/comment contents (except a comment's closing `]`, which still gets normal spacing). All
  pass; full `./gradlew build` remains green.

Not yet wired to anything: `EditorPane` has no formatter action yet — same "logic ready, UI
pending" state as the rest of this batch.

## 18. `HiddenTextProvider`: not ported, obsolete by design

`com.repdev.parser.HiddenTextProvider` existed purely to let `RepgenParser` ask
`FoldingManager` for the canonical (unfolded) source and translate offsets across the
view/model split created by physically hiding folded text from the `StyledText` buffer
(`viewToModel`/`modelToView`/`getFullSourceText`/`onTokensUpdated`). §7's `FoldingModel` design
note already established that split doesn't exist in `newdev` — folding is a view-only
computation over the always-complete canonical text — so there is no view/model seam left for an
interface like this to bridge. Same closure as §15's dropped fold-undo variant: not deferred, the
problem it solved is gone.
