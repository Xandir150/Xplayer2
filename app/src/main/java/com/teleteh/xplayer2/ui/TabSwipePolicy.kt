package com.teleteh.xplayer2.ui

/**
 * Which tab may be left by swiping the page sideways.
 *
 * Two of the three pages own a horizontal gesture of their own: Recent and PC-Mirror both forget a
 * row by swiping it away. They cannot share the finger with the pager. ViewPager2's RecyclerView
 * sees every MotionEvent before the row's `ItemTouchHelper` does *and* uses a bigger threshold
 * (`TOUCH_SLOP_PAGING`, twice the ordinary slop), while the helper's disallow-intercept only goes
 * out once it has itself passed the smaller one — so whichever way the finger moves first past
 * 16dp wins, and MOVE events arrive one per frame. A slow drag forgets the PC; a flick at the
 * speed people actually dismiss things at changes tab instead. Same movement, two outcomes, on the
 * destructive gesture.
 *
 * So paging by swipe belongs to Sources alone. The other two are left by tapping their tab, or by
 * turning your head — `MainActivity.switchTab` sets `currentItem` programmatically, which
 * `isUserInputEnabled` does not gate.
 */
object TabSwipePolicy {

    const val PAGE_RECENT = 0
    const val PAGE_SOURCES = 1
    const val PAGE_PC_MIRROR = 2

    /** True if [position] may be left by dragging the page itself. */
    fun pagesBySwipe(position: Int): Boolean = position == PAGE_SOURCES
}
