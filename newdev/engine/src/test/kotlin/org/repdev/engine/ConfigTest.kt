package org.repdev.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun `round-trips through disk as JSON, including the Int-keyed session map`() {
        val file = Files.createTempFile("repdev-config", ".json").toFile()
        val original = Config(
            syms = listOf(1999, 2000),
            sessionInfo = mapOf(
                1999 to SessionInfo(description = "Prod", server = "aix1.example.com", aixUsername = "jsmith"),
            ),
            darkMode = false,
        )

        Config.save(original, file)
        val loaded = Config.load(file)

        assertEquals(original, loaded)
        assertEquals("aix1.example.com", loaded.sessionInfo.getValue(1999).server)
    }

    @Test
    fun `defaults to SSH port, not the original's Telnet 23`() {
        assertEquals(22, Config().port)
    }

    @Test
    fun `missing file loads as defaults instead of erroring`() {
        val file = Files.createTempFile("repdev-config-missing", ".json").toFile()
        file.delete()
        assertEquals(Config(), Config.load(file))
    }
}
