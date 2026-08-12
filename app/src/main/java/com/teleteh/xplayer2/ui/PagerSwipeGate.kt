package com.teleteh.xplayer2.ui

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Lets a list with swipe-to-delete live inside a swipeable pager without either one losing.
 *
 * Both want the same gesture, and the pager wins by default because it intercepts the horizontal
 * drag before the list sees it. The obvious remedy — turning the pager's input off on any page
 * that has such a list — is what was done first, and it costs too much: on those pages a swipe
 * anywhere, including the empty space below the last row, then does nothing at all, and the tab
 * strip becomes the only way to move. The owner noticed within a day.
 *
 * So the question is not *which page* but *where the finger landed*. A drag that starts on a row
 * belongs to the row; a drag that starts anywhere else belongs to the pager. That is decided at
 * the one moment when it can be decided cheaply and without guessing — `ACTION_DOWN`, before any
 * movement — by asking the list whether there is a child under that point.
 *
 * The pager is re-enabled on `UP` and on `CANCEL`, and `CANCEL` is the one that matters: a gesture
 * the list takes over ends that way, and forgetting it would leave paging off for good.
 */
object PagerSwipeGate {

    /**
     * Hands [list]'s rows the horizontal gesture and leaves the rest of the page to [pager].
     *
     * Safe to call for a list without swipe-to-delete — it only ever narrows what the pager sees,
     * and only while a finger is down on a row.
     */
    fun attach(pager: ViewPager2, list: RecyclerView) {
        list.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        pager.isUserInputEnabled = rv.findChildViewUnder(e.x, e.y) == null
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        pager.isUserInputEnabled = true
                }
                // Never consume: this decides who may act, it does not act.
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
        })
    }
}
