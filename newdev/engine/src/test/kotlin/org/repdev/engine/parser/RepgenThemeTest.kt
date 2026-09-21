package org.repdev.engine.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Gradle runs :engine:test with the engine module dir (newdev/engine) as the working directory,
// and styles/ lives at the repo root (RepDev/styles, a sibling of newdev/) — two levels up.
private val STYLES_DIR = File("../../styles")

private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

// Deliberately not hand-copied hex literals: this session's tool chain has been observed to
// silently mangle hex-digit runs wherever they're displayed back (dropped 'C's, altered digit
// groups) — confirmed to hit freshly-typed text too, not just old source reads. Pulling the
// expected color straight out of the theme's own source XML via a tiny independent regex sidesteps
// ever needing me to eyeball/retype a hex constant.
private fun expectedColor(file: File, tag: String, attr: String): Int? {
    val m = Regex("""<$tag\b[^>]*\b$attr="#([0-9A-Fa-f]{6})"""").find(file.readText()) ?: return null
    val hex = m.groupValues[1]
    return rgb(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
}

class RepgenThemeTest {
    @Test
    fun `loads the shipped GhostRider theme's hex colors and categories`() {
        val file = File(STYLES_DIR, "GhostRider.xml")
        val theme = RepgenTheme.load(file)

        assertEquals("GhostRider", theme.name)
        assertEquals(expectedColor(file, "editor", "bgColor"), theme.editorBg)
        assertEquals(expectedColor(file, "editor", "fgColor"), theme.editorFg)
        assertEquals("Consolas", theme.fontName)
        assertEquals(11, theme.fontSize)
        assertEquals(expectedColor(file, "comments", "fgColor"), theme.comment.fg)
        assertTrue(theme.comment.italic)
        assertEquals(expectedColor(file, "variables", "fgColor"), theme.variable.fg)
        assertTrue(theme.variable.bold)
        assertEquals(expectedColor(file, "numbers", "fgColor"), theme.number.fg) // GhostRider has an explicit <numbers> tag
    }

    @Test
    fun `falls back to DEFAULT numbers color for a theme with no numbers tag`() {
        val theme = RepgenTheme.load(File(STYLES_DIR, "default.xml"))
        assertEquals(RepgenTheme.DEFAULT.number.fg, theme.number.fg)
    }

    @Test
    fun `missing file falls back to DEFAULT`() {
        val theme = RepgenTheme.load(File(STYLES_DIR, "does-not-exist.xml"))
        assertEquals(RepgenTheme.DEFAULT, theme)
    }

    @Test
    fun `dollar-scheme themes resolve to a real color, not null`() {
        val theme = RepgenTheme.load(File(STYLES_DIR, "blue.xml"))
        // $blue randomizes the blue channel but pins red/green to the original's fixed constant
        // (7 * 16 + 7, i.e. 0x77) — computed, not hand-typed, for the same reason as expectedColor.
        val fixed = 7 * 16 + 7
        assertEquals(fixed, (theme.editorBg shr 16) and 255)
        assertEquals(fixed, (theme.editorBg shr 8) and 255)
    }

    @Test
    fun `blend moves from base toward target by the given amount`() {
        val black = rgb(0, 0, 0)
        val white = rgb(255, 255, 255)
        assertEquals(rgb(127, 127, 127), RepgenTheme.blend(black, white, 0.5))
        assertEquals(black, RepgenTheme.blend(black, white, 0.0))
    }
}
