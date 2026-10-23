package com.readcomic.curl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlProgramTest {
    @Test
    fun definesGoRightAfterTheVersionLine() {
        val source = "#version 300 es\n\nprecision highp float;\n"
        assertEquals("#version 300 es\n#define STYLE 3\n\nprecision highp float;\n", GlProgram.withDefines(source, "#define STYLE 3"))
    }

    @Test
    fun withoutDefinesTheSourceIsAsItWas() {
        val source = "#version 300 es\nvoid main() {}\n"
        assertEquals(source, GlProgram.withDefines(source, ""))
    }

    @Test
    fun aSourceWithoutVersionGetsThemFirst() {
        assertEquals("#define A 1\nvoid main() {}", GlProgram.withDefines("void main() {}", "#define A 1"))
    }

    @Test
    fun theShelfShadersStartWithTheirVersion() {
        // Else the defines could not go after it, and the program would not compile.
        for (name in listOf("shelf.frag", "unbox.frag", "shelf.vert")) {
            val file = File("src/main/assets/shaders/$name")
            assertTrue(name, file.readText().startsWith("#version 300 es\n"))
        }
    }
}
