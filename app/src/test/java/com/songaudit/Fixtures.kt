package com.songaudit

import org.junit.Assume.assumeTrue
import java.io.File

/** Test audio from tools/fixtures.py. Tests that need it are skipped, not failed, when it has not been generated. */
object Fixtures {
    fun file(name: String): File {
        val url = Fixtures::class.java.getResource("/fixtures/$name")
        assumeTrue("run python3 tools/fixtures.py to generate $name", url != null)
        return File(url!!.toURI())
    }
}
