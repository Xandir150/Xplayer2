package com.teleteh.xplayer2.player

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.content.res.AppCompatResources
import com.teleteh.xplayer2.R

/**
 * The swap-eyes glyph: Material's "eyeglasses", each lens filled with the image that eye is being
 * given — left red, right blue, or the other way round once swapped — under a frame in the
 * button's own foreground colour.
 *
 * Layered at runtime rather than one vector per state because the lenses must keep their colours
 * while the frame follows the button: a checked row on the remote turns its foreground to
 * `colorOnPrimary`, and the one `iconTint` a `MaterialButton` has is forwarded to every layer of
 * whatever it is given. So the button's tint is cleared (`iconTint="@null"`), and the frame is
 * coloured with a colour filter, which a vector draws in preference to any tint.
 */
object StereoEyeSwapIcon {

    fun build(context: Context, swapped: Boolean, frameColor: Int): Drawable {
        val lenses = AppCompatResources.getDrawable(
            context,
            if (swapped) R.drawable.ic_swap_eyes_lenses_swapped_24 else R.drawable.ic_swap_eyes_lenses_24
        )!!.mutate()
        val frame = AppCompatResources.getDrawable(context, R.drawable.ic_swap_eyes_frame_24)!!.mutate()
        frame.colorFilter = PorterDuffColorFilter(frameColor, PorterDuff.Mode.SRC_IN)
        // Lenses first, frame over them: the ring's inner edge covers the seam of each fill.
        return LayerDrawable(arrayOf(lenses, frame))
    }
}
