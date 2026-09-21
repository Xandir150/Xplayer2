package com.teleteh.xplayer2.ui.fold

/** Window coordinates in, local coordinates out. Zero-width folds are valid. */
internal object FoldGeometry {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
    data class Panes(val first: Box, val second: Box)

    fun split(content: Box, fold: Box, horizontal: Boolean): Panes? {
        if (content.width <= 0 || content.height <= 0) return null
        return if (horizontal) {
            if (fold.left > content.left || fold.right < content.right ||
                fold.top <= content.top || fold.bottom >= content.bottom) return null
            Panes(Box(content.left, content.top, content.right, fold.top),
                Box(content.left, fold.bottom, content.right, content.bottom))
        } else {
            if (fold.top > content.top || fold.bottom < content.bottom ||
                fold.left <= content.left || fold.right >= content.right) return null
            Panes(Box(content.left, content.top, fold.left, content.bottom),
                Box(fold.right, content.top, content.right, content.bottom))
        }
    }
}
