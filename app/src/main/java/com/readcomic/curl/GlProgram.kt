package com.readcomic.curl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log

/** A linked GLSL program with its uniform locations looked up by name. */
class GlProgram(vertexSource: String, fragmentSource: String) {
    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glBindAttribLocation(id, 0, "aUV")
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw IllegalStateException("Program link failed: $log")
        }
        val count = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_ACTIVE_UNIFORMS, count, 0)
        val size = IntArray(1)
        val type = IntArray(1)
        for (i in 0 until count[0]) {
            val name = GLES30.glGetActiveUniform(id, i, size, 0, type, 0)
            uniforms[name] = GLES30.glGetUniformLocation(id, name)
        }
    }

    fun use() = GLES30.glUseProgram(id)

    fun delete() = GLES30.glDeleteProgram(id)

    /** -1 for uniforms the compiler optimised away; GL ignores writes to -1. */
    operator fun get(name: String): Int = uniforms[name] ?: -1

    companion object {
        private const val TAG = "GlProgram"

        /**
         * Shaders from the assets; a line "#include name" brings in shaders/name.glsl, and
         * [defines] (lines such as "#define STYLE 3") go in before everything but "#version".
         */
        fun fromAssets(assets: AssetManager, vertex: String, fragment: String, defines: String = ""): GlProgram {
            fun load(name: String): String =
                withDefines(read(assets, name).replace(INCLUDE) { read(assets, "${it.groupValues[1]}.glsl") }, defines)
            return GlProgram(load(vertex), load(fragment))
        }

        /** [source] with [defines] put in right after its "#version" line, which must stay the first. */
        internal fun withDefines(source: String, defines: String): String {
            if (defines.isEmpty()) return source
            val end = source.indexOf('\n')
            return if (source.startsWith("#version") && end >= 0) {
                source.substring(0, end + 1) + defines + "\n" + source.substring(end + 1)
            } else {
                defines + "\n" + source
            }
        }

        private val INCLUDE = Regex("#include\\s+(\\w+)")

        private fun read(assets: AssetManager, name: String): String =
            assets.open("shaders/$name").bufferedReader().use { it.readText() }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                Log.e(TAG, "Shader compile failed: $log\n$source")
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("Shader compile failed: $log")
            }
            return shader
        }
    }
}
