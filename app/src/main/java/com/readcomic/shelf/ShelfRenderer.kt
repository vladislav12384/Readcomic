package com.readcomic.shelf

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Process
import com.readcomic.curl.GlProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the wall of shelves: first the depth of everything as seen from the key light (the
 * shadow map), then the wall, the planks with their labels and the comics, the one in hand last.
 */
class ShelfRenderer(
    private val assets: AssetManager,
    private val controller: ShelfController,
    private val art: ShelfArtCache,
    private val requestRender: () -> Unit,
) : GLSurfaceView.Renderer {

    private class Mesh(val vao: Int, val count: Int)

    private lateinit var program: GlProgram
    private lateinit var depthProgram: GlProgram
    private lateinit var box: Mesh
    private lateinit var quad: Mesh
    private val comicMeshes = HashMap<String, Mesh>()
    private var blank = 0
    private var shadowTexture = 0
    private var shadowFramebuffer = 0
    private var shadowSize = 2048
    private var width = 1
    private var height = 1
    private var lastFrameNanos = 0L

    /** Background behind the scene (where nothing is drawn). */
    @Volatile
    var clearColor = floatArrayOf(0.05f, 0.05f, 0.06f)

    /** Paint of the wall behind the shelves, linear colour. */
    @Volatile
    var wallColor = floatArrayOf(0.17f, 0.22f, 0.17f)

    /** The display refresh the frame is drawn for (System.nanoTime clock), 0 when unknown. */
    var frameClock: () -> Long = { 0L }

    /** Called at the start of every frame with its time, to apply the finger's current position. */
    var beforeFrame: ((frameNanos: Long) -> Unit)? = null

    @Volatile
    var touching = false

    /** Set at the end of every frame: whether the next one is needed. */
    @Volatile
    var wantsFrames = false
        private set

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        program = GlProgram.fromAssets(assets, "shelf.vert", "shelf.frag")
        depthProgram = GlProgram.fromAssets(assets, "shelf_depth.vert", "shelf_depth.frag")
        comicMeshes.clear()
        box = createMesh(boxVertices(), boxIndices())
        quad = createMesh(quadVertices(), shortArrayOf(0, 1, 2, 0, 2, 3))
        blank = createBlankTexture()
        createShadowMap()
        art.onContextCreated()
        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = max(width, 1)
        this.height = max(height, 1)
        controller.setViewSize(this.width, this.height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = frameClock().takeIf { it > 0L } ?: System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 1f / 20f)
        beforeFrame?.invoke(now)
        var animating = controller.step(dt)
        if (art.uploadPending(budgetMillis = if (animating || touching) 3L else 10L)) animating = true
        lastFrameNanos = if (animating || touching) now else 0L

        val frame = controller.frame()
        for (comic in frame.comics) {
            art.request("cover:${comic.id}")
            art.request("spine:${comic.id}")
            if (comic.held) {
                art.request("front:${comic.id}")
                art.request("back:${comic.id}")
            }
        }
        frame.labels.forEach { art.request("label:${it.text}") }
        frame.signs.forEach { art.request(signKey(it)) }
        if (frame.plaque != null) art.request("plaque:${frame.count}")

        renderShadows(frame)
        renderScene(frame)
        wantsFrames = animating
        if (animating) requestRender()
    }

    private fun renderShadows(frame: ShelfFrame) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFramebuffer)
        GLES30.glViewport(0, 0, shadowSize, shadowSize)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.5f, 4f)
        depthProgram.use()
        GLES30.glUniformMatrix4fv(depthProgram["uLightViewProj"], 1, false, frame.lightViewProj, 0)
        GLES30.glBindVertexArray(box.vao)
        fun cast(model: FloatArray) {
            GLES30.glUniformMatrix4fv(depthProgram["uModel"], 1, false, model, 0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, box.count, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        frame.planks.forEach(::cast)
        frame.cornice?.let(::cast)
        for (comic in frame.comics) {
            // A comic lifted off the shelf would throw a hard shadow far behind it; real ones blur away.
            if (comic.held && frame.dim > 0.05f) continue
            comic.ribbon?.let(::cast)
            val mesh = meshFor(comic)
            GLES30.glBindVertexArray(mesh.vao)
            GLES30.glUniformMatrix4fv(depthProgram["uModel"], 1, false, comic.model, 0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.count, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glBindVertexArray(box.vao)
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun renderScene(frame: ShelfFrame) {
        GLES30.glViewport(0, 0, width, height)
        val c = clearColor
        GLES30.glClearColor(c[0], c[1], c[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        val p = program
        p.use()
        GLES30.glUniformMatrix4fv(p["uViewProj"], 1, false, frame.viewProj, 0)
        GLES30.glUniformMatrix4fv(p["uLightViewProj"], 1, false, frame.lightViewProj, 0)
        GLES30.glUniform3f(p["uEye"], frame.eye.x, frame.eye.y, frame.eye.z)
        GLES30.glUniform3f(p["uLightDir"], frame.light.x, frame.light.y, frame.light.z)
        GLES30.glUniform1f(p["uShadowTexel"], 1f / shadowSize)
        GLES30.glUniform1f(p["uRowHeight"], ShelfController.ROW_HEIGHT)
        GLES30.glUniform4f(
            p["uPlank"],
            ShelfController.PLANK_THICKNESS,
            ShelfController.PLANK_DEPTH,
            ShelfController.BOTTOM_Z,
            ShelfController.CORNICE_HEIGHT,
        )
        GLES30.glUniform1f(p["uLightsTime"], frame.lightsTime)
        val wall = wallColor
        GLES30.glUniform3f(p["uWall"], wall[0], wall[1], wall[2])
        GLES30.glUniform1i(p["uCover"], 0)
        GLES30.glUniform1i(p["uBack"], 1)
        GLES30.glUniform1i(p["uLabel"], 2)
        GLES30.glUniform1i(p["uShadow"], 3)
        GLES30.glUniform1i(p["uSpine"], 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTexture)

        GLES30.glUniform1f(p["uHeld"], 0f)
        draw(quad, frame.wall, MATERIAL_WALL, frame.dim)
        frame.planks.forEach { draw(box, it, MATERIAL_WOOD, frame.dim) }
        frame.cornice?.let { draw(box, it, MATERIAL_WOOD, frame.dim) }
        frame.plaque?.let { draw(quad, it, MATERIAL_PLAQUE, frame.dim, label = art["plaque:${frame.count}"]) }
        for (label in frame.labels) {
            val texture = art["label:${label.text}"]
            draw(quad, label.model, MATERIAL_LABEL, frame.dim, label = texture)
        }
        for (sign in frame.signs) {
            draw(quad, sign.model, MATERIAL_PLAQUE, frame.dim, label = art[signKey(sign)])
        }
        for (comic in frame.comics) {
            val dim = when {
                comic.held -> 0f
                comic.faded -> max(frame.dim, frame.searchDim)
                else -> frame.dim
            }
            val cover = (if (comic.held) art["front:${comic.id}"] else null) ?: art["cover:${comic.id}"]
            val back = if (comic.held) art["back:${comic.id}"] else null
            GLES30.glUniform4f(
                p["uBook"],
                comic.binding.ordinal.toFloat(),
                comic.pages.toFloat(),
                min(ComicShape.BOARD, comic.size.z * 0.2f),
                ComicShape.spineRadius(comic.binding, comic.size.z),
            )
            // Once out of the shelf's light, the comic in hand is lit like in a photo studio.
            GLES30.glUniform1f(p["uHeld"], if (comic.held && frame.dim > 0.25f) 1f else 0f)
            draw(meshFor(comic), comic.model, MATERIAL_COMIC, dim, cover = cover, back = back, spine = art["spine:${comic.id}"])
            comic.ribbon?.let { draw(box, it, MATERIAL_RIBBON, dim) }
        }
        GLES30.glBindVertexArray(0)
    }

    private fun draw(
        mesh: Mesh,
        model: FloatArray,
        material: Int,
        dim: Float,
        cover: Int? = null,
        back: Int? = null,
        label: Int? = null,
        spine: Int? = null,
    ) {
        val p = program
        GLES30.glUniformMatrix4fv(p["uModel"], 1, false, model, 0)
        GLES30.glUniformMatrix3fv(p["uNormalMatrix"], 1, false, normalMatrix(model), 0)
        GLES30.glUniform1i(p["uMaterial"], material)
        GLES30.glUniform1f(p["uDim"], dim)
        bind(0, cover, p["uHasCover"])
        bind(1, back, p["uHasBack"])
        bind(2, label, p["uHasLabel"])
        bind(4, spine, p["uHasSpine"])
        GLES30.glBindVertexArray(mesh.vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.count, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    private fun signKey(sign: SignDraw) = "sign:${"%.1f".format(java.util.Locale.ROOT, controller.signAspect(sign.span))}:${sign.text}"

    /** Binds a texture (or a white pixel, so no sampler ever points at the shadow map) to [unit]. */
    private fun bind(unit: Int, texture: Int?, flag: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture ?: blank)
        GLES30.glUniform1f(flag, if (texture != null) 1f else 0f)
    }

    /** The shape of a comic, built once for every size and binding. */
    private fun meshFor(comic: ComicDraw): Mesh {
        val size = comic.size
        val key = "${comic.binding}:${(size.x * 1e4f).toInt()}:${(size.y * 1e4f).toInt()}:${(size.z * 1e5f).toInt()}"
        return comicMeshes.getOrPut(key) {
            val shape = ComicShape.build(size.x, size.y, size.z, comic.binding)
            createMesh(shape.vertices, shape.indices)
        }
    }

    /** Inverse transpose of the upper 3x3 of [m], column-major. */
    private fun normalMatrix(m: FloatArray): FloatArray {
        val a00 = m[0]; val a01 = m[1]; val a02 = m[2]
        val a10 = m[4]; val a11 = m[5]; val a12 = m[6]
        val a20 = m[8]; val a21 = m[9]; val a22 = m[10]
        val b01 = a22 * a11 - a12 * a21
        val b11 = -a22 * a10 + a12 * a20
        val b21 = a21 * a10 - a11 * a20
        val det = a00 * b01 + a01 * b11 + a02 * b21
        val d = if (det == 0f) 1f else 1f / det
        val inv = floatArrayOf(
            b01 * d, (-a22 * a01 + a02 * a21) * d, (a12 * a01 - a02 * a11) * d,
            b11 * d, (a22 * a00 - a02 * a20) * d, (-a12 * a00 + a02 * a10) * d,
            b21 * d, (-a21 * a00 + a01 * a20) * d, (a11 * a00 - a01 * a10) * d,
        )
        return floatArrayOf(inv[0], inv[3], inv[6], inv[1], inv[4], inv[7], inv[2], inv[5], inv[8])
    }

    private fun createShadowMap() {
        val size = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, size, 0)
        shadowSize = min(2048, size[0])
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        shadowTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTexture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_DEPTH_COMPONENT24, shadowSize, shadowSize)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        GLES30.glGenFramebuffers(1, ids, 0)
        shadowFramebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFramebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTexture, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun createBlankTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        val pixel = ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1)).position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        return ids[0]
    }

    private fun createMesh(vertices: FloatArray, indices: ShortArray): Mesh {
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(vertices).position(0)
        val indexBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        indexBuffer.put(indices).position(0)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        val vao = ids[0]
        GLES30.glBindVertexArray(vao)
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)
        val stride = 9 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 32)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, indexBuffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        return Mesh(vao, indices.size)
    }

    private companion object {
        const val MATERIAL_COMIC = 0
        const val MATERIAL_WOOD = 1
        const val MATERIAL_WALL = 2
        const val MATERIAL_LABEL = 3
        const val MATERIAL_RIBBON = 4
        const val MATERIAL_PLAQUE = 5

        /**
         * Unit box, -0.5..0.5. Per vertex: position, normal, uv, face (0 front, 1 back, 2 left,
         * 3 right, 4 top, 5 bottom). Texture v runs from the top down, like bitmaps.
         */
        fun boxVertices(): FloatArray {
            val out = ArrayList<Float>(24 * 9)
            val corners = listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f)
            for (face in 0..5) {
                for ((a, b) in corners) {
                    val (position, normal, uv) = when (face) {
                        0 -> Triple(floatArrayOf(a, b, 0.5f), floatArrayOf(0f, 0f, 1f), floatArrayOf(a + 0.5f, 0.5f - b))
                        1 -> Triple(floatArrayOf(a, b, -0.5f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0.5f - a, 0.5f - b))
                        2 -> Triple(floatArrayOf(-0.5f, b, a), floatArrayOf(-1f, 0f, 0f), floatArrayOf(a + 0.5f, 0.5f - b))
                        3 -> Triple(floatArrayOf(0.5f, b, a), floatArrayOf(1f, 0f, 0f), floatArrayOf(a + 0.5f, 0.5f - b))
                        4 -> Triple(floatArrayOf(a, 0.5f, b), floatArrayOf(0f, 1f, 0f), floatArrayOf(a + 0.5f, b + 0.5f))
                        else -> Triple(floatArrayOf(a, -0.5f, b), floatArrayOf(0f, -1f, 0f), floatArrayOf(a + 0.5f, b + 0.5f))
                    }
                    position.forEach { out += it }
                    normal.forEach { out += it }
                    uv.forEach { out += it }
                    out += face.toFloat()
                }
            }
            return out.toFloatArray()
        }

        fun boxIndices(): ShortArray {
            val out = ShortArray(36)
            for (face in 0..5) {
                val base = face * 4
                val i = face * 6
                out[i] = base.toShort()
                out[i + 1] = (base + 1).toShort()
                out[i + 2] = (base + 2).toShort()
                out[i + 3] = base.toShort()
                out[i + 4] = (base + 2).toShort()
                out[i + 5] = (base + 3).toShort()
            }
            return out
        }

        /** Unit quad in the xy plane facing +z, same layout as the box. */
        fun quadVertices(): FloatArray {
            val out = ArrayList<Float>(4 * 9)
            for ((a, b) in listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f)) {
                out += listOf(a, b, 0f, 0f, 0f, 1f, a + 0.5f, 0.5f - b, 0f)
            }
            return out.toFloatArray()
        }
    }
}
