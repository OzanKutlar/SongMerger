package me.Integer.Mediaplayer.ui.widgets

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Reads the system animator duration scale, so animations driven by hand (frame loops
 * rather than Animator objects) honour "remove animations" just like framework ones.
 */
internal object Motion {

    private const val TAG = "Motion"

    /** 1 by default, 0 when the user turned animations off. */
    fun durationScale(context: Context?): Float {
        if (context == null) return 1f
        return try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot read animator scale: ${e.message}")
            1f
        }
    }

    /** Scales a duration; 0 means "jump to the end". */
    fun scaledNanos(context: Context?, nanos: Long): Long {
        val scale = durationScale(context)
        return if (scale <= 0f) 0L else (nanos * scale).toLong()
    }
}
