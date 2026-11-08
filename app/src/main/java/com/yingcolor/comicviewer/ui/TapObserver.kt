package com.yingcolor.comicviewer.ui

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Observes single-finger taps WITHOUT consuming a single pointer event.
 *
 * Why this exists:
 *  - A full-screen tap overlay on the normal dispatch pass swallows drags, so the
 *    pager never sees a swipe. A detector that calls consume() on a down event
 *    does the same thing. This one only watches the FINAL pass, i.e. after the
 *    pager / list / pinch detectors have already had their say, and never
 *    consumes anything. Reading/riding along is enough to recognise a tap.
 *  - A tap is a gesture that stays put. The distance is measured from the DOWN
 *    position, not frame-to-frame: a slow scroll moves only 1-2 px per frame,
 *    so per-frame deltas never exceed touchSlop and the scroll used to be
 *    mistaken for a tap (which popped the toolbar open).
 *  - Near the screen edges a much stricter distance is used. Android's back
 *    gesture starts there and the system cancels the stream after only a few
 *    pixels on some phones; that must not become a page turn. An ordinary,
 *    stationary tap in that strip still works.
 */
internal suspend fun PointerInputScope.observeTaps(
    tolerancePx: Float,
    edgeGuardPx: Float,
    edgeTolerancePx: Float,
    onTap: (Offset) -> Unit
) {
    awaitPointerEventScope {
        while (true) {
            // Wait for a fresh press. `pressed && !previousPressed` is used
            // instead of changedToDown() because a change may already have been
            // consumed by another detector before we get to see it.
            var down: PointerInputChange? = null
            while (down == null) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
            }
            val first = down!!
            val id = first.id
            val start = first.position
            val startedAt = SystemClock.uptimeMillis()
            val nearEdge = start.x < edgeGuardPx || start.x > size.width - edgeGuardPx
            val limit = if (nearEdge) edgeTolerancePx else tolerancePx
            var abandoned = false
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                // A second finger means pinch / pan, not a tap.
                if (event.changes.count { it.pressed } > 1) abandoned = true
                val change = event.changes.firstOrNull { it.id == id }
                if (change == null) {
                    // The pointer vanished without a release (system took over).
                    finished = true
                    abandoned = true
                } else {
                    if ((change.position - start).getDistance() > limit) abandoned = true
                    if (!change.pressed) {
                        finished = true
                        val heldTooLong = SystemClock.uptimeMillis() - startedAt > 1200L
                        if (!abandoned && !heldTooLong) onTap(change.position)
                    }
                }
            }
        }
    }
}
