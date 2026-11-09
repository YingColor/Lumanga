package com.yingcolor.lumanga.ui

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
 *  - Near the screen edges the system back gesture lives. On many phones the
 *    app receives only DOWN followed immediately by ACTION_CANCEL, with no move
 *    events at all, so the distance stays zero and a "tighter tolerance" is not
 *    enough to reject it. The whole system-gesture strip is therefore ignored
 *    outright, using the width the OS actually reports. A press that ends
 *    inside one frame is rejected too, as a second line of defence.
 */
internal suspend fun PointerInputScope.observeTaps(
    tolerancePx: Float,
    gestureGuardPx: Float,
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
            // Ignore the strip the OS reserves for swipe-back. A real tap there
            // is impossible to tell apart from "DOWN, then the system cancelled
            // us before any move", which is exactly what used to turn a page.
            val inGestureStrip = gestureGuardPx > 0f &&
                (start.x < gestureGuardPx || start.x > size.width - gestureGuardPx)
            var abandoned = inGestureStrip
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
                    val distance = (change.position - start).getDistance()
                    if (distance > tolerancePx) abandoned = true
                    if (!change.pressed) {
                        finished = true
                        val held = SystemClock.uptimeMillis() - startedAt
                        // A press that ends within one frame is the system
                        // cancelling our stream, not a human tapping.
                        val accepted = !abandoned && held >= 25L && held <= 1200L
                        if (accepted) {
                            onTap(change.position)
                        } else {
                            // Kept deliberately: if a phone still turns a page
                            // on swipe-back, `adb logcat -s ReaderTap` shows the
                            // exact down position, guard width and press length.
                            android.util.Log.d(
                                "ReaderTap",
                                "ignored x=${start.x.toInt()} guard=${gestureGuardPx.toInt()} " +
                                    "heldMs=$held distPx=${distance.toInt()}"
                            )
                        }
                    }
                }
            }
        }
    }
}
