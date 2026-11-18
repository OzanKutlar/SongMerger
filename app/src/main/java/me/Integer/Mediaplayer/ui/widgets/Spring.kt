package me.Integer.Mediaplayer.ui.widgets

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A damped spring with unit mass: pulled towards [target] by [stiffness] and slowed by a
 * damping ratio (1 settles without overshoot, below 1 bounces). It advances in small fixed
 * sub-steps, so it behaves the same at any frame rate. Pure Kotlin, so it is unit-tested.
 */
internal class Spring(private val stiffness: Float, dampingRatio: Float) {

    private val damping: Float

    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f
        private set

    init {
        require(stiffness > 0f && stiffness.isFinite()) { "stiffness must be positive" }
        require(dampingRatio >= 0f && dampingRatio.isFinite()) { "damping ratio must not be negative" }
        damping = 2f * dampingRatio * sqrt(stiffness)
    }

    fun reset(value: Float, velocity: Float, target: Float) {
        this.value = if (value.isFinite()) value else 0f
        this.velocity = if (velocity.isFinite()) velocity else 0f
        this.target = if (target.isFinite()) target else 0f
    }

    /** Advances by [seconds]; zero, negative or broken times are ignored. */
    fun step(seconds: Float) {
        if (!seconds.isFinite() || seconds <= 0f) return
        val steps = ceil(seconds / MAX_STEP_SECONDS).toInt().coerceIn(1, MAX_SUB_STEPS)
        val h = seconds / steps
        for (i in 0 until steps) {
            val acceleration = -stiffness * (value - target) - damping * velocity
            velocity += acceleration * h
            value += velocity * h
        }
    }

    /** True once it is at rest on the target, to within what anyone could see. */
    val isSettled: Boolean
        get() = abs(value - target) < REST_DISTANCE && abs(velocity) < REST_SPEED

    /** Jumps to rest on the target. */
    fun settle() {
        value = target
        velocity = 0f
    }

    private companion object {
        const val MAX_STEP_SECONDS = 1f / 240f
        const val MAX_SUB_STEPS = 64
        const val REST_DISTANCE = 0.001f
        const val REST_SPEED = 0.01f
    }
}
