package com.yingcolor.lumanga.ui

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope





















internal suspend fun PointerInputScope.observeTaps(
    tolerancePx: Float,
    gestureGuardPx: Float,
    onTap: (Offset) -> Unit
) {
    awaitPointerEventScope {
        while (true) {
            
            
            
            var down: PointerInputChange? = null
            while (down == null) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
            }
            val first = down!!
            val id = first.id
            val start = first.position
            val startedAt = SystemClock.uptimeMillis()
            
            
            
            val inGestureStrip = gestureGuardPx > 0f &&
                (start.x < gestureGuardPx || start.x > size.width - gestureGuardPx)
            var abandoned = inGestureStrip
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                
                if (event.changes.count { it.pressed } > 1) abandoned = true
                val change = event.changes.firstOrNull { it.id == id }
                if (change == null) {
                    
                    finished = true
                    abandoned = true
                } else {
                    val distance = (change.position - start).getDistance()
                    if (distance > tolerancePx) abandoned = true
                    if (!change.pressed) {
                        finished = true
                        val held = SystemClock.uptimeMillis() - startedAt
                        
                        
                        val accepted = !abandoned && held >= 25L && held <= 1200L
                        if (accepted) {
                            onTap(change.position)
                        } else {
                            
                            
                            
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
