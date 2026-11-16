package com.yingcolor.lumanga.data

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * Turns the system's memory-pressure callbacks into one "drop what can be rebuilt" action.
 *
 * The reader used to ignore these callbacks and learned about pressure from failed allocations
 * instead, which costs a full GC on the main thread and freezes input for seconds.
 */
internal object MemoryPressure {

    private fun isTight(level: Int): Boolean = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> true
        else -> false
    }

    /** Registers [release] on [app] and returns the callback so it can be unregistered later. */
    fun watch(app: Application, release: () -> Unit): ComponentCallbacks2 {
        val guard = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = release()
            override fun onTrimMemory(level: Int) {
                if (isTight(level)) release()
            }
        }
        app.registerComponentCallbacks(guard)
        return guard
    }
}
