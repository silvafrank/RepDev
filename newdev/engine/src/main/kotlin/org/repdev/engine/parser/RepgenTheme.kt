package org.repdev.engine.parser

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.random.Random

/** Packed R/G/B into one Int, no alpha — Compose callers add the alpha byte back themselves. */
typealias RgbColor = Int

/**
 * Packs three 0-255 channels into an [RgbColor]. Used instead of hex literals like `0x1E1E1EE`
 * for every constant in this file — this editor session's tool chain has a display-layer bug that
 * silently mangles long digit runs in hex literals wherever they're shown back (see
 * DECISIONS.md's note on the same issue hitting DirectSymitarSession.java's ported constants).
 * Decimal channel args under 256 never trip it, so that's the only literal form used here.
 */
private fun rgb(r: Int, g: Int, b: Int): RgbColor = (r shl 16) or (g shl 8) or b

data class CategoryStyle(val fg: RgbColor?, val bg: RgbColor? = null, val bold: Boolean = false, val italic: Boolean = false)

/**
 * Pure-Kotlin port of com.repdev.Style (the styles/[name].xml reader) plus the theme-value half of
 * SyntaxHighlighter.loadStyle — same file format and category tags, so all existing styles/[name].xml
 * load unmodified, no migration needed. What doesn't port: SWT Color/Font allocation+disposal and
 * the reload-in-place static fields — this is just an immutable value; the Compose side derives
 * paint from it however it likes and lets the GC handle the rest.
 *
 * `blend()` is kept for parity — the original derives the current-line tint from bg/fg at load
 * time rather than a style.xml attribute, so a new theme doesn't need one added by hand.
 */
data class RepgenTheme(
    val name: String,
    val editorBg: RgbColor,
    val editorFg: RgbColor,
    val currentLineTint: RgbColor,
    val tokenHighlight: RgbColor,
    val fontName: String,
    val fontSize: Int,
    val lineNumberFg: RgbColor,
    val foldFg: RgbColor,
    val foldGuide: RgbColor?,
    val foldShape: String,
    val comment: CategoryStyle,
    val variable: CategoryStyle,
    val function: CategoryStyle,
    val keyword: CategoryStyle,
    val number: CategoryStyle,
    val task: CategoryStyle,
    val string: CategoryStyle,
    val date: CategoryStyle,
    val struct1: CategoryStyle,
    val struct1Invalid: CategoryStyle,
    val struct2: CategoryStyle,
    val struct2Invalid: CategoryStyle,
) {
    companion object {
        /** Same fallback values as SyntaxHighlighter's static fields / catch block, just decimal instead of hex. */
        val DEFAULT = RepgenTheme(
            name = "default (built-in fallback)",
            editorBg = rgb(255, 255, 255),
            editorFg = rgb(0, 0, 0),
            currentLineTint = blend(rgb(255, 255, 255), rgb(0, 0, 0), 0.08),
            tokenHighlight = rgb(192, 192, 192),
            fontName = "Courier New", fontSize = 11,
            lineNumberFg = rgb(127, 127, 127),
            foldFg = rgb(90, 90, 90), foldGuide = null, foldShape = "triangle",
            comment = CategoryStyle(rgb(127, 127, 127)),
            variable = CategoryStyle(rgb(0, 0, 0), bold = true),
            function = CategoryStyle(rgb(0, 0, 255), bold = true),
            keyword = CategoryStyle(rgb(0, 0, 255)),
            number = CategoryStyle(rgb(181, 107, 0)),
            task = CategoryStyle(rgb(64, 64, 64), bold = true),
            string = CategoryStyle(rgb(255, 0, 0)),
            date = CategoryStyle(rgb(255, 0, 0), bold = true),
            struct1 = CategoryStyle(rgb(255, 0, 255)),
            struct1Invalid = CategoryStyle(rgb(255, 0, 255), bg = rgb(128, 0, 0)),
            struct2 = CategoryStyle(rgb(255, 128, 255)),
            struct2Invalid = CategoryStyle(rgb(255, 128, 255), bg = rgb(128, 0, 0)),
        )

        /** Falls back to [DEFAULT] on any parse failure, same as the original's try/catch. */
        fun load(file: File): RepgenTheme = try {
            parse(file)
        } catch (e: Exception) {
            DEFAULT
        }

        private fun parse(file: File): RepgenTheme {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val style = (doc.getElementsByTagName("RepDevStyle").item(0) as Element)
                .getElementsByTagName("style").item(0) as Element

            fun attr(item: String, attrib: String): String? {
                val nodes = style.childNodes
                for (i in 0 until nodes.length) {
                    val cur = nodes.item(i)
                    if (cur.nodeName != item) continue
                    val a = cur.attributes?.getNamedItem(attrib) ?: continue
                    return a.nodeValue
                }
                return null
            }

            fun color(item: String, attrib: String): RgbColor? {
                var hex = attr(item, attrib) ?: return null
                if (hex.startsWith("#")) hex = hex.substring(1)
                if (hex.startsWith("$")) return randomSchemeColor(hex.substring(1))
                return when (hex.length) {
                    // 3-digit shorthand: each nibble *16, NOT the CSS-style *17 "duplicate the
                    // digit" expansion — preserved from Style.java as-is (a quirk, not a bug worth
                    // fixing: no shipped theme actually uses the 3-digit form).
                    3 -> rgb(Character.digit(hex[0], 16) * 16, Character.digit(hex[1], 16) * 16, Character.digit(hex[2], 16) * 16)
                    6 -> rgb(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
                    else -> null
                }
            }

            fun styleAttr(item: String): Pair<Boolean, Boolean> {
                val s = attr(item, "style") ?: return false to false
                return s.equals("bold", ignoreCase = true) to s.equals("italic", ignoreCase = true)
            }

            fun category(item: String): CategoryStyle {
                val (bold, italic) = styleAttr(item)
                return CategoryStyle(color(item, "fgColor"), color(item, "bgColor"), bold, italic)
            }

            val bg = color("editor", "bgColor") ?: DEFAULT.editorBg
            val fg = color("editor", "fgColor") ?: DEFAULT.editorFg
            val numbersFg = color("numbers", "fgColor") ?: DEFAULT.number.fg

            return RepgenTheme(
                name = attr("header", "name") ?: file.nameWithoutExtension,
                editorBg = bg,
                editorFg = fg,
                currentLineTint = blend(bg, fg, 0.08),
                tokenHighlight = color("editor", "token") ?: DEFAULT.tokenHighlight,
                fontName = attr("editor", "font")?.ifEmpty { null } ?: "Courier New",
                fontSize = attr("editor", "fontSize")?.toIntOrNull() ?: 11,
                lineNumberFg = color("linenumber", "fgColor") ?: DEFAULT.lineNumberFg,
                foldFg = color("folding", "fgColor") ?: DEFAULT.foldFg,
                foldGuide = color("folding", "guideColor"),
                foldShape = attr("folding", "shape") ?: "triangle",
                comment = category("comments"),
                variable = category("variables"),
                function = category("functions"),
                keyword = category("keywords"),
                number = category("numbers").copy(fg = numbersFg),
                task = category("task"),
                string = category("typeChar"),
                date = category("typeDate"),
                struct1 = category("struct1"),
                struct1Invalid = category("struct1Inv"),
                struct2 = category("struct2"),
                struct2Invalid = category("struct2Inv"),
            )
        }

        // Ported from Style.getColor's "$" branch. Only the schemes actually used by a shipped
        // style (red/green/blue/rand — see blue.xml etc.) are implemented; the original's extra
        // "$!RRGGBB-with-16-as-wildcard" scheme has no shipped theme using it either, so it's
        // skipped rather than carried over speculatively — add it here if a theme needs it.
        private fun randomSchemeColor(scheme: String): RgbColor {
            fun rnd() = Random.nextInt(256)
            val fixed = 7 * 16 + 7 // the original's "other two channels" constant
            return when {
                scheme.contains("rand") -> rgb(rnd(), rnd(), rnd())
                scheme.contains("red") -> rgb(rnd(), fixed, fixed)
                scheme.contains("green") -> rgb(fixed, rnd(), fixed)
                scheme.contains("blue") -> rgb(fixed, fixed, rnd())
                else -> DEFAULT.editorFg
            }
        }

        fun blend(base: RgbColor, target: RgbColor, amount: Double): RgbColor {
            fun channel(shift: Int): Int {
                val b = (base shr shift) and 255
                val t = (target shr shift) and 255
                return (b + (t - b) * amount).toInt()
            }
            return rgb(channel(16), channel(8), channel(0))
        }
    }
}
