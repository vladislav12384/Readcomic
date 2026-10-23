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
import kotlin.math.ceil
import kotlin.math.floor
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

    // The shelves' program is built for the bookcase on screen only ("#define STYLE"): the code
    // of the other bookcases is left out, so it compiles faster. Built on the first frame, and
    // again only when the bookcase is changed.
    private var shelfProgram: GlProgram? = null
    private var shelfProgramStyle = -1
    private lateinit var depthProgram: GlProgram

    // The box of new comics is drawn by its own program, built the first time a box is there:
    // the shelves keep a lighter one. It too is for the bookcase on screen only.
    private var unboxProgram: GlProgram? = null
    private var unboxProgramStyle = -1

    /** The program being drawn with. */
    private lateinit var program: GlProgram
    private lateinit var box: Mesh
    private lateinit var quad: Mesh

    // Bookmarks change shape every frame: one buffer, filled anew for each.
    private var ribbonVao = 0
    private var ribbonBuffer = 0
    private val ribbonData = ByteBuffer.allocateDirect((Ribbon.ROWS + 1) * 3 * 9 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val comicMeshes = HashMap<String, Mesh>()
    private var blank = 0
    private var shadowTexture = 0
    private var shadowFramebuffer = 0

    // The comics' own shadows, without the shelves: no shelf throws its shadow over the covers.
    private var comicShadowTexture = 0
    private var comicShadowFramebuffer = 0
    private var shadowSize = 2048

    // The paper round new comics: two halves each, rebuilt as the paper is torn.
    private class WrapperGl(val left: Int, val right: Int, val buffers: IntArray, var counts: IntArray, var version: Int)

    private val wrappers = HashMap<String, WrapperGl>()

    // What is torn of the wrapping in hand, and which piece of paper took it, one byte a cell.
    private class MaskGl(val texture: Int, val owner: Int, val columns: Int, val rows: Int, var version: Int)

    private val masks = HashMap<WrapTear, MaskGl>()
    private var blankMask = 0
    private var blankOwner = 0
    private var maskData: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024)

    // Torn strips in the air, all in one buffer, filled anew every frame.
    private var stripVao = 0
    private val stripBuffers = IntArray(2)
    private var width = 1
    private var height = 1
    private var lastFrameNanos = 0L
    private var freshFrames = 0

    /** Background behind the scene (where nothing is drawn). */
    @Volatile
    var clearColor = floatArrayOf(0.05f, 0.05f, 0.06f)

    /** The look of the bookcase, as the shaders call it ([ShelfStyle.shader]); its program is built when it is drawn. */
    @Volatile
    var style = 0

    /** The shelves mirror the comics standing on them ([ShelfStyle.mirrors]). */
    @Volatile
    var mirrors = false

    /** How bright the display case itself is lit ([CaseLight.glow]); other bookcases ignore it. */
    @Volatile
    var glow = 1f

    /** Where the mirrored comic is put together, so that no frame makes garbage. */
    private val mirrored = FloatArray(16)

    /** Paint of the wall behind the shelves, linear colour. */
    @Volatile
    var wallColor = floatArrayOf(0.14f, 0.19f, 0.25f)

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
        // A new context: the programs of the old one are gone with it.
        shelfProgram = null
        shelfProgramStyle = -1
        unboxProgram = null
        unboxProgramStyle = -1
        depthProgram = GlProgram.fromAssets(assets, "shelf_depth.vert", "shelf_depth.frag")
        comicMeshes.clear()
        box = createMesh(boxVertices(), boxIndices())
        quad = createMesh(quadVertices(), shortArrayOf(0, 1, 2, 0, 2, 3))
        createRibbonMesh()
        createStripMesh()
        wrappers.clear()
        masks.clear()
        blank = createBlankTexture()
        blankMask = createBlankMask(GLES30.GL_NEAREST)
        blankOwner = createBlankMask(GLES30.GL_NEAREST)
        createShadowMaps()
        art.onContextCreated()
        lastFrameNanos = 0L
        freshFrames = 0
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
        // Just opened, everything waits for its pictures: let them in faster for a moment.
        val budget = when {
            freshFrames < 12 -> 16L
            animating || touching -> 3L
            else -> 10L
        }
        freshFrames++
        val frame = controller.frame()
        // The big pictures are only for the comic in hand and the one coming out of the box.
        val heldId = frame.comics.lastOrNull { it.held }?.id
        val boxFront = frame.unbox?.frontId
        if (art.uploadPending(budget) { id -> id == heldId || id == boxFront }) animating = true
        lastFrameNanos = if (animating || touching) now else 0L

        for (comic in frame.comics) {
            art.request("cover:${comic.id}")
            art.request("spine:${comic.id}")
            if (comic.held) {
                art.request("front:${comic.id}")
                art.request(backKey(comic))
            }
        }
        frame.labels.forEach { art.request("label:${it.text}") }
        frame.unbox?.let { box ->
            art.request("box:${box.count}")
            box.comics.forEach { art.request("cover:${it.comic.id}") }
            box.frontId?.let { art.request("front:$it") }
        }
        frame.signs.forEach { art.request(signKey(it)) }
        if (frame.plaque != null) art.request("plaque:${frame.count}")

        renderShadows(frame)
        renderScene(frame)
        wantsFrames = animating
        if (animating) requestRender()
    }

    /**
     * Two shadow maps from the room light: one with everything for the wall and the shelves, one
     * with the comics alone for the comics, so that the shelf above leaves them in its light.
     */
    private fun renderShadows(frame: ShelfFrame) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.5f, 4f)
        depthProgram.use()
        GLES30.glUniformMatrix4fv(depthProgram["uLightViewProj"], 1, false, frame.lightViewProj, 0)
        // Neither map may stay bound for sampling while it is drawn into.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blank)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blank)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, comicShadowFramebuffer)
        GLES30.glViewport(0, 0, shadowSize, shadowSize)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        castComics(frame)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFramebuffer)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glBindVertexArray(box.vao)
        fun cast(model: FloatArray) {
            GLES30.glUniformMatrix4fv(depthProgram["uModel"], 1, false, model, 0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, box.count, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        frame.planks.forEach(::cast)
        frame.cornice?.let(::cast)
        castComics(frame)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun castComics(frame: ShelfFrame) {
        for (comic in frame.comics) {
            // A comic lifted off the shelf would throw a hard shadow far behind it; real ones blur away.
            if (comic.held && frame.dim > 0.05f) continue
            comic.ribbon?.let { vertices ->
                fillRibbon(vertices)
                GLES30.glUniformMatrix4fv(depthProgram["uModel"], 1, false, identity, 0)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, Ribbon.INDICES.size, GLES30.GL_UNSIGNED_SHORT, 0)
            }
            val mesh = meshFor(comic)
            GLES30.glBindVertexArray(mesh.vao)
            GLES30.glUniformMatrix4fv(depthProgram["uModel"], 1, false, comic.model, 0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.count, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glBindVertexArray(box.vao)
        }
    }

    private fun renderScene(frame: ShelfFrame) {
        GLES30.glViewport(0, 0, width, height)
        val c = clearColor
        GLES30.glClearColor(c[0], c[1], c[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        // The bookcase is read once for the whole frame.
        val look = style
        use(shelfProgramFor(look), frame)
        val p = program
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
        if (mirrors) renderReflections(frame)
        for (comic in frame.comics) {
            val dim = when {
                comic.held -> 0f
                comic.faded -> max(frame.dim, frame.searchDim)
                else -> frame.dim
            }
            val cover = (if (comic.held) art["front:${comic.id}"] else null) ?: art["cover:${comic.id}"]
            val back = if (comic.held) art[backKey(comic)] else null
            GLES30.glUniform4f(
                p["uBook"],
                comic.binding.ordinal.toFloat(),
                comic.pages.toFloat(),
                min(ComicShape.BOARD, comic.size.z * 0.2f),
                ComicShape.spineRadius(comic.binding, comic.size.z),
            )
            // Once out of the shelf's light, the comic in hand is lit like in a photo studio.
            GLES30.glUniform1f(p["uHeld"], if (comic.held) studioLight(frame.dim) else 0f)
            GLES30.glUniform1f(p["uShine"], comic.shine)
            draw(meshFor(comic), comic.model, MATERIAL_COMIC, dim, cover = cover, back = back, spine = art["spine:${comic.id}"])
            GLES30.glUniform1f(p["uShine"], -1f)
            comic.ribbon?.let { vertices ->
                fillRibbon(vertices)
                draw(Mesh(ribbonVao, Ribbon.INDICES.size), identity, MATERIAL_RIBBON, dim)
            }
        }
        if (frame.glows.isNotEmpty()) {
            // The light strips' glow is light added on top, behind whatever is in front of it.
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glDepthMask(false)
            frame.glows.forEach { draw(quad, it, MATERIAL_GLOW, frame.dim) }
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        frame.unbox?.let { box ->
            // Built with the box's first frame: with the first frame of the shelves when the box is
            // there already (hidden by the curtain), not a few frames into the box coming in,
            // where it would stop it for a moment.
            if (unboxProgramStyle != look) {
                unboxProgram?.delete()
                unboxProgram = null
            }
            val unbox = unboxProgram ?: GlProgram.fromAssets(assets, "shelf.vert", "unbox.frag", styleDefine(look)).also {
                unboxProgram = it
                unboxProgramStyle = look
            }
            use(unbox, frame)
            renderUnbox(box)
        }
        if (frame.unbox == null && (wrappers.isNotEmpty() || masks.isNotEmpty())) {
            // The box is gone: so is the paper.
            wrappers.values.forEach(::deleteWrapper)
            wrappers.clear()
            masks.values.forEach(::deleteMask)
            masks.clear()
        }
        GLES30.glBindVertexArray(0)
    }

    /** The shelves' program for the bookcase [style]: the one built already, or a new one in its place. */
    private fun shelfProgramFor(style: Int): GlProgram {
        shelfProgram?.let { built ->
            if (shelfProgramStyle == style) return built
            built.delete()
        }
        return GlProgram.fromAssets(assets, "shelf.vert", "shelf.frag", styleDefine(style)).also {
            shelfProgram = it
            shelfProgramStyle = style
        }
    }

    private fun styleDefine(style: Int) = "#define STYLE $style"

    /** Starts drawing with [next]: everything the frame sets once, and the plain state of the rest. */
    private fun use(next: GlProgram, frame: ShelfFrame) {
        program = next
        val p = next
        p.use()
        GLES30.glUniformMatrix4fv(p["uViewProj"], 1, false, frame.viewProj, 0)
        GLES30.glUniformMatrix4fv(p["uLightViewProj"], 1, false, frame.lightViewProj, 0)
        GLES30.glUniform3f(p["uEye"], frame.eye.x, frame.eye.y, frame.eye.z)
        GLES30.glUniform3f(p["uLightDir"], frame.light.x, frame.light.y, frame.light.z)
        GLES30.glUniform1f(p["uShadowTexel"], 1f / shadowSize)
        GLES30.glUniform1f(p["uRowHeight"], frame.rowHeight)
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
        GLES30.glUniform1f(p["uGlow"], glow)
        GLES30.glUniform2f(p["uCells"], frame.cellWidth, frame.cellOrigin)
        GLES30.glUniform1f(p["uMirror"], 0f)
        GLES30.glUniform1i(p["uCover"], 0)
        GLES30.glUniform1i(p["uBack"], 1)
        GLES30.glUniform1i(p["uLabel"], 2)
        GLES30.glUniform1i(p["uShadow"], 3)
        GLES30.glUniform1i(p["uSpine"], 4)
        GLES30.glUniform1i(p["uComicShadow"], 5)
        GLES30.glUniform1i(p["uMask"], 6)
        GLES30.glUniform1i(p["uOwner"], 7)
        bindMask(null)
        GLES30.glUniform1f(p["uReleased"], 0f)
        GLES30.glUniform1f(p["uShine"], -1f)
        GLES30.glUniform4f(p["uWrap"], 0f, 0f, 0f, 0f)
        GLES30.glUniform1f(p["uAlpha"], 1f)
        GLES30.glUniform1i(p["uPrintFace"], -1)
        GLES30.glUniform1i(p["uInnerFace"], -1)
        GLES30.glUniform2f(p["uTapeCut"], 0f, 0f)
        GLES30.glUniform1f(p["uPaperLift"], 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, comicShadowTexture)

        GLES30.glUniform1f(p["uHeld"], 0f)
    }

    /** The box of new comics, the comics in their paper, the torn strips and the glint, over everything. */
    private fun renderUnbox(box: UnboxDraw) {
        val p = program
        // Lit like the comic in hand: in front of the darkened room.
        GLES30.glUniform1f(p["uHeld"], 1f)
        val print = art["box:${box.count}"]
        for (part in box.parts) {
            if (part.kind == CardboardBox.KIND_TAPE) {
                GLES30.glUniform2f(p["uTapeCut"], box.tapeCut[0], box.tapeCut[1])
                // The halves on the flaps know which side of them the seam is.
                GLES30.glUniform1i(p["uInnerFace"], part.innerFace)
                draw(this.box, part.model, MATERIAL_TAPE, 0f)
            } else {
                GLES30.glUniform1i(p["uPrintFace"], part.printFace)
                GLES30.glUniform1i(p["uInnerFace"], part.innerFace)
                draw(this.box, part.model, MATERIAL_CARDBOARD, 0f, label = if (part.printFace >= 0) print else null)
            }
        }
        GLES30.glUniform1i(p["uPrintFace"], -1)
        GLES30.glUniform1i(p["uInnerFace"], -1)

        for (wrapped in box.comics) {
            val comic = wrapped.comic
            val cover = (if (comic.held) art["front:${comic.id}"] else null) ?: art["cover:${comic.id}"]
            val wrapper = wrapped.wrapper
            val tear = wrapper?.tear
            val mask = tear?.let(::maskFor)
            bindMask(mask)
            val margin = WrapperShape.margin(comic.size.z)
            GLES30.glUniform4f(p["uWrap"], comic.size.x, comic.size.y, margin, if (mask != null && wrapper.alpha > 0.99f && !wrapper.released) 1f else 0f)
            if (wrapped.drawComic) {
                GLES30.glUniform4f(
                    p["uBook"],
                    comic.binding.ordinal.toFloat(),
                    comic.pages.toFloat(),
                    min(ComicShape.BOARD, comic.size.z * 0.2f),
                    ComicShape.spineRadius(comic.binding, comic.size.z),
                )
                GLES30.glUniform1f(p["uShine"], wrapped.shine)
                draw(meshFor(comic), comic.model, MATERIAL_COMIC, 0f, cover = cover, spine = art["spine:${comic.id}"])
                GLES30.glUniform1f(p["uShine"], -1f)
            }
            if (wrapper != null && wrapper.alpha > 0.01f) {
                GLES30.glUniform4f(p["uWrap"], comic.size.x, comic.size.y, margin, if (mask != null) 1f else 0f)
                GLES30.glUniform1f(p["uPaperLift"], WrapperShape.radius(comic.size.z))
                GLES30.glUniform1f(p["uAlpha"], wrapper.alpha)
                GLES30.glUniform1f(p["uReleased"], if (wrapper.released) 1f else 0f)
                val blend = wrapper.alpha < 0.999f
                if (blend) {
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                }
                val gl = wrapperFor(wrapper)
                draw(Mesh(gl.left, gl.counts[0]), wrapper.left, MATERIAL_PAPER, 0f, cover = cover)
                draw(Mesh(gl.right, gl.counts[1]), wrapper.right, MATERIAL_PAPER, 0f, cover = cover)
                if (blend) GLES30.glDisable(GLES30.GL_BLEND)
                GLES30.glUniform1f(p["uAlpha"], 1f)
                GLES30.glUniform1f(p["uReleased"], 0f)
            }
        }
        // The pieces of paper torn off, each drawn from the cells it took.
        for (pieces in box.pieces) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            bindMask(maskFor(pieces.tear))
            GLES30.glUniform4f(p["uWrap"], pieces.size.x, pieces.size.y, WrapperShape.margin(pieces.size.z), 1f)
            val cover = art["front:${pieces.comicId}"] ?: art["cover:${pieces.comicId}"]
            fillStrips(pieces.vertices, pieces.indices)
            draw(Mesh(stripVao, pieces.indices.size), identity, MATERIAL_STRIP, 0f, cover = cover)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        GLES30.glUniform4f(p["uWrap"], 0f, 0f, 0f, 0f)
        bindMask(null)
        // Forget the paper of comics no longer there.
        val shown = box.comics.mapNotNull { it.wrapper?.id }.toSet()
        wrappers.keys.filter { it !in shown }.forEach { id -> wrappers.remove(id)?.let(::deleteWrapper) }
        val tears = (box.comics.mapNotNull { it.wrapper?.tear } + box.pieces.map { it.tear }).toSet()
        masks.keys.filter { it !in tears }.forEach { t -> masks.remove(t)?.let(::deleteMask) }

        box.star?.let { star ->
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glDepthMask(false)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glUniform1f(p["uShine"], star.t)
            val turn = Quat.axisAngle(Vec3(0f, 0f, 1f), 0.5f * star.t)
            draw(quad, Mat4.compose(star.position, turn, Vec3(star.size, star.size, 1f)), MATERIAL_STAR, 0f)
            GLES30.glUniform1f(p["uShine"], -1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        GLES30.glUniform1f(p["uHeld"], 0f)
    }

    private fun wrapperFor(wrapper: WrapperDraw): WrapperGl {
        val version = wrapper.tear?.version ?: -1
        val existing = wrappers[wrapper.id]
        if (existing != null && existing.version == version) return existing
        val shape = WrapperShape.build(wrapper.size, wrapper.tear, wrapper.seed)
        val gl = existing ?: run {
            val vaos = IntArray(2)
            GLES30.glGenVertexArrays(2, vaos, 0)
            val buffers = IntArray(4)
            GLES30.glGenBuffers(4, buffers, 0)
            for (k in 0..1) {
                GLES30.glBindVertexArray(vaos[k])
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[k * 2])
                vertexLayout()
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[k * 2 + 1])
            }
            GLES30.glBindVertexArray(0)
            WrapperGl(vaos[0], vaos[1], buffers, IntArray(2), version).also { wrappers[wrapper.id] = it }
        }
        upload(gl.left, gl.buffers[0], gl.buffers[1], shape.left, shape.leftIndices)
        upload(gl.right, gl.buffers[2], gl.buffers[3], shape.right, shape.rightIndices)
        gl.counts = intArrayOf(shape.leftIndices.size, shape.rightIndices.size)
        gl.version = version
        return gl
    }

    private fun deleteWrapper(gl: WrapperGl) {
        GLES30.glDeleteVertexArrays(2, intArrayOf(gl.left, gl.right), 0)
        GLES30.glDeleteBuffers(4, gl.buffers, 0)
    }

    private fun upload(vao: Int, vertexBuffer: Int, indexBuffer: Int, vertices: FloatArray, indices: ShortArray) {
        val v = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        v.put(vertices).position(0)
        val i = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        i.put(indices).position(0)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, v, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, i, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    /** The textures of what is torn of [tear] and by which piece, updated when it changed. */
    private fun maskFor(tear: WrapTear): MaskGl {
        val existing = masks[tear]
        if (existing != null && existing.version == tear.version) return existing
        val gl = existing ?: run {
            val ids = IntArray(2)
            GLES30.glGenTextures(2, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            val levels = 1 + (31 - Integer.numberOfLeadingZeros(maxOf(tear.columns, tear.rows)))
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_R8, tear.columns, tear.rows)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            // Which piece took a cell: read cell by cell, never blended.
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[1])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R8, tear.columns, tear.rows)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            MaskGl(ids[0], ids[1], tear.columns, tear.rows, -1).also { masks[tear] = it }
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        if (maskData.capacity() < tear.mask.size) maskData = ByteBuffer.allocateDirect(tear.mask.size)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gl.texture)
        maskData.clear()
        maskData.put(tear.mask).position(0)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, gl.columns, gl.rows, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskData)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gl.owner)
        maskData.clear()
        maskData.put(tear.owner).position(0)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, gl.columns, gl.rows, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskData)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        gl.version = tear.version
        return gl
    }

    private fun bindMask(mask: MaskGl?) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE6)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mask?.texture ?: blankMask)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE7)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mask?.owner ?: blankOwner)
    }

    private fun deleteMask(mask: MaskGl) = GLES30.glDeleteTextures(2, intArrayOf(mask.texture, mask.owner), 0)

    private fun createBlankMask(filter: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        return ids[0]
    }

    private fun createStripMesh() {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        stripVao = ids[0]
        GLES30.glGenBuffers(2, stripBuffers, 0)
        GLES30.glBindVertexArray(stripVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, stripBuffers[0])
        vertexLayout()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, stripBuffers[1])
        GLES30.glBindVertexArray(0)
    }

    private fun fillStrips(vertices: FloatArray, indices: ShortArray) = upload(stripVao, stripBuffers[0], stripBuffers[1], vertices, indices)

    /** Position, normal, uv, part: the layout of every mesh of the shelf, on the bound buffer. */
    private fun vertexLayout() {
        val stride = 9 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 32)
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

    /**
     * The comics mirrored in the glossy tops of the shelves they stand on: each drawn once more,
     * upside down under its shelf, cheaply shaded and only where the shelf top is seen (the
     * shader cuts away the rest), faded with the distance from the shelf. Drawn over the shelves,
     * under the comics themselves.
     */
    private fun renderReflections(frame: ShelfFrame) {
        val p = program
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUniform1f(p["uMirror"], 1f)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        for (comic in frame.comics) {
            val floor = comic.standsOn
            // Only the tops of shelves below the eye are seen.
            if (floor.isNaN() || frame.eye.y <= floor || comic.mirror <= 0f) continue
            if (!scissorToShelfTop(frame, comic, floor)) GLES30.glScissor(0, 0, width, height)
            // Turned over the shelf top (y = floor): y goes to 2 floor - y.
            val m = comic.model
            for (c in 0 until 4) {
                mirrored[c * 4] = m[c * 4]
                mirrored[c * 4 + 1] = 2f * floor * m[c * 4 + 3] - m[c * 4 + 1]
                mirrored[c * 4 + 2] = m[c * 4 + 2]
                mirrored[c * 4 + 3] = m[c * 4 + 3]
            }
            GLES30.glUniform1f(p["uMirrorY"], floor)
            GLES30.glUniform1f(p["uMirrorFade"], comic.mirror)
            val dim = if (comic.faded) max(frame.dim, frame.searchDim) else frame.dim
            draw(meshFor(comic), mirrored, MATERIAL_COMIC, dim, cover = art["cover:${comic.id}"])
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glUniform1f(p["uMirror"], 0f)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /**
     * How far the comic in hand is in the studio's light, from how far the room is darkened
     * (0.5 with the comic in hand): 0 on its shelf, 1 in the hand, smoothly over most of the way
     * between, instead of switching halfway, which showed as a flash.
     */
    private fun studioLight(dim: Float): Float {
        val t = ((dim - 0.05f) / 0.4f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Keeps the drawing of [comic]'s reflection to where it can be seen: the top of its shelf, from
     * the comic over to the middle of the view (the eye meets the mirror image on the shelf top
     * between them). The shader cuts away the rest of the turned-over comic anyway, but its
     * pixels would still be worked on: most of the work of reflections. False when the box
     * cannot be told (a corner behind the eye); then it is all drawn, as before.
     */
    private fun scissorToShelfTop(frame: ShelfFrame, comic: ComicDraw, shelfY: Float): Boolean {
        val vp = frame.viewProj
        val cx = comic.model[12]
        val half = comic.size.x * 0.5f + comic.size.z + 0.01f
        val eyeX = frame.eye.x
        val xs = floatArrayOf(min(cx - half, eyeX), max(cx + half, eyeX))
        val zs = floatArrayOf(0f, ShelfController.PLANK_DEPTH)
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = Float.MAX_VALUE
        var top = -Float.MAX_VALUE
        for (x in xs) {
            for (z in zs) {
                val w = vp[3] * x + vp[7] * shelfY + vp[11] * z + vp[15]
                if (w <= 1e-4f) return false
                val sx = ((vp[0] * x + vp[4] * shelfY + vp[8] * z + vp[12]) / w + 1f) * 0.5f * width
                val sy = ((vp[1] * x + vp[5] * shelfY + vp[9] * z + vp[13]) / w + 1f) * 0.5f * height
                left = min(left, sx)
                right = max(right, sx)
                bottom = min(bottom, sy)
                top = max(top, sy)
            }
        }
        val x0 = (floor(left).toInt() - 2).coerceIn(0, width)
        val x1 = (ceil(right).toInt() + 2).coerceIn(0, width)
        val y0 = (floor(bottom).toInt() - 2).coerceIn(0, height)
        val y1 = (ceil(top).toInt() + 2).coerceIn(0, height)
        GLES30.glScissor(x0, y0, x1 - x0, y1 - y0)
        return true
    }

    /** The back cover of [comic] in hand: its own, or one drawn for it when the reader asked. */
    private fun backKey(comic: ComicDraw) = if (comic.drawnBack) "drawnback:${comic.id}" else "back:${comic.id}"

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

    private fun createRibbonMesh() {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        ribbonVao = ids[0]
        GLES30.glBindVertexArray(ribbonVao)
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        ribbonBuffer = buffers[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, ribbonData.capacity() * 4, null, GLES30.GL_DYNAMIC_DRAW)
        val stride = 9 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 32)
        val indices = ByteBuffer.allocateDirect(Ribbon.INDICES.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        indices.put(Ribbon.INDICES).position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, Ribbon.INDICES.size * 2, indices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    /** Puts the triangles of one bookmark into the ribbon buffer and binds it for drawing. */
    private fun fillRibbon(vertices: FloatArray) {
        ribbonData.position(0)
        ribbonData.put(vertices).position(0)
        GLES30.glBindVertexArray(ribbonVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ribbonBuffer)
        // A fresh store every time: no waiting for the GPU to finish with the previous bookmark.
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, ribbonData, GLES30.GL_DYNAMIC_DRAW)
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

    private fun createShadowMaps() {
        val size = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, size, 0)
        shadowSize = min(2048, size[0])
        createShadowMap().let { (texture, framebuffer) ->
            shadowTexture = texture
            shadowFramebuffer = framebuffer
        }
        createShadowMap().let { (texture, framebuffer) ->
            comicShadowTexture = texture
            comicShadowFramebuffer = framebuffer
        }
    }

    /** A depth texture for shadows and the framebuffer that draws into it. */
    private fun createShadowMap(): Pair<Int, Int> {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        // 16 bits are plenty for the few metres the light sees, and half the memory of 24.
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_DEPTH_COMPONENT16, shadowSize, shadowSize)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        GLES30.glGenFramebuffers(1, ids, 0)
        val framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return texture to framebuffer
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
        const val MATERIAL_CARDBOARD = 6
        const val MATERIAL_TAPE = 7
        const val MATERIAL_PAPER = 8
        const val MATERIAL_STRIP = 9
        const val MATERIAL_STAR = 10
        const val MATERIAL_GLOW = 13

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
