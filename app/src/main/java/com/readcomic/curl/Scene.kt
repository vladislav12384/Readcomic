package com.readcomic.curl

/**
 * How a page image is printed on one side of a sheet.
 *
 * Sheet coordinates (u, v) start at the spine; [flip] turns them into the page's own left-to-right
 * coordinates. [rect] is where the page lies inside the sheet (letterboxing when the page has a
 * different shape than the sheet) and [sub] the part of the image texture that belongs to this
 * page (one half of a two-page spread image, for example). Both are (x, y, width, height).
 */
class Face(
    val image: Int,
    val rect: FloatArray,
    val sub: FloatArray,
    val flip: Boolean,
    /** Coated, glossy paper: the outside of the covers. */
    val glossy: Boolean = false,
) {
    val isBlank get() = image < 0

    companion object {
        val FULL = floatArrayOf(0f, 0f, 1f, 1f)
        val LEFT_HALF = floatArrayOf(0f, 0f, 0.5f, 1f)
        val RIGHT_HALF = floatArrayOf(0.5f, 0f, 0.5f, 1f)

        fun blank(flip: Boolean) = Face(-1, FULL, FULL, flip)
    }
}

/**
 * A sheet of paper. Geometry is described for a sheet lying to the right of its spine
 * (`rect.left` is the spine); [mirror] reflects it to the left-hand side of an open book.
 *
 * @param back what is printed on the other side; null means blank paper.
 */
class Sheet(
    val rect: PageRect,
    val mirror: Boolean,
    val front: Face,
    val back: Face?,
    val curl: CurlGeometry?,
) {
    val spineX get() = rect.left
}

/** Soft shadow of the comic on the table under [rect]; [alpha] fades it in and out. */
class DropShadow(val rect: PageRect, val alpha: Float)

/** Visible edge of a stack of sheets lying under [rect], shifted by ([dx], [dy]) at the bottom. */
class StackEdge(val rect: PageRect, val dx: Float, val dy: Float, val lines: Float)

class Scene(
    /** Pages lying flat, drawn first. */
    val flats: List<Sheet>,
    /** The sheet being turned, drawn on top with its shadow. */
    val turning: Sheet?,
    val stacks: List<StackEdge>,
    /** Where the comic lies on the table, one per side of an open book. */
    val drops: List<DropShadow>,
    /** Strength of the shading in the gutter next to the spine. */
    val gutter: Float,
    /** More sheets in the air (pages flying over while a comic opens at its bookmark), top-most last. */
    val flying: List<Sheet> = emptyList(),
)
