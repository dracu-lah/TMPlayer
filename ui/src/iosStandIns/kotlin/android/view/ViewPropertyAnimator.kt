package android.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator

/** A View's fades, moves and scales, Android's: each property from where it stands to its target. */
class ViewPropertyAnimator internal constructor(private val view: View) {
    private val targets = LinkedHashMap<String, Float>()
    private var duration = 300L
    private var delay = 0L
    private var interpolator: TimeInterpolator? = null
    private var endAction: java.lang.Runnable? = null
    private var startAction: java.lang.Runnable? = null
    private var listener: Animator.AnimatorListener? = null

    /** What each started animation still moves: a later start takes over the properties it sets. */
    private val running = ArrayList<Pair<ValueAnimator, MutableSet<String>>>()

    fun alpha(value: Float) = to("alpha", value)

    fun alphaBy(value: Float) = to("alpha", view.alpha + value)

    fun translationX(value: Float) = to("translationX", value)

    fun translationY(value: Float) = to("translationY", value)

    fun translationXBy(value: Float) = to("translationX", view.translationX + value)

    fun translationYBy(value: Float) = to("translationY", view.translationY + value)

    fun scaleX(value: Float) = to("scaleX", value)

    fun scaleY(value: Float) = to("scaleY", value)

    fun rotation(value: Float) = to("rotation", value)

    fun rotationBy(value: Float) = to("rotation", view.rotation + value)

    fun x(value: Float) = to("translationX", value - view.left)

    fun y(value: Float) = to("translationY", value - view.top)

    fun translationZ(value: Float) = this

    fun setDuration(duration: Long) = apply { this.duration = duration }

    fun getDuration(): Long = duration

    fun setStartDelay(delay: Long) = apply { this.delay = delay }

    fun setInterpolator(interpolator: TimeInterpolator?) = apply { this.interpolator = interpolator }

    fun setListener(listener: Animator.AnimatorListener?) = apply { this.listener = listener }

    fun withEndAction(action: java.lang.Runnable) = apply { endAction = action }

    fun withStartAction(action: java.lang.Runnable) = apply { startAction = action }

    fun withLayer() = this

    fun setUpdateListener(listener: ValueAnimator.AnimatorUpdateListener?) = this

    private fun to(property: String, value: Float): ViewPropertyAnimator {
        targets[property] = value
        return this
    }

    /**
     * Starts what was set since the last start. As Android's: an animation still running gives up
     * only the properties this one sets and carries on with the rest (a chip fading in keeps
     * fading while a lift moves it up), and one left with nothing to move is cancelled, its end
     * action not run.
     */
    fun start() {
        val wanted = LinkedHashMap(targets)
        targets.clear()
        if (wanted.isEmpty()) return
        for ((animator, properties) in running.toList()) {
            properties.removeAll(wanted.keys)
            if (properties.isEmpty()) animator.cancel()
        }
        val starts = wanted.keys.associateWith { read(it) }
        val moving = wanted.keys.toMutableSet()
        val endAction = endAction.also { this.endAction = null }
        val startAction = startAction.also { this.startAction = null }
        val listener = listener
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = duration
        animator.startDelay = delay
        animator.interpolator = interpolator
        animator.addUpdateListener { a ->
            val t = a.animatedValue as Float
            wanted.forEach { (property, target) ->
                if (property in moving) write(property, starts.getValue(property) + (target - starts.getValue(property)) * t)
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationStart(animation: Animator) {
                startAction?.run()
                listener?.onAnimationStart(animation)
            }

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
                listener?.onAnimationCancel(animation)
            }

            override fun onAnimationEnd(animation: Animator) {
                running.removeAll { it.first === animation }
                listener?.onAnimationEnd(animation)
                if (!cancelled) endAction?.run()
            }
        })
        running += animator to moving
        animator.start()
    }

    /** Cancels everything running and set, as Android's. */
    fun cancel() {
        targets.clear()
        running.toList().forEach { it.first.cancel() }
        running.clear()
    }

    private fun read(property: String): Float = when (property) {
        "alpha" -> view.alpha
        "translationX" -> view.translationX
        "translationY" -> view.translationY
        "scaleX" -> view.scaleX
        "scaleY" -> view.scaleY
        "rotation" -> view.rotation
        else -> 0f
    }

    private fun write(property: String, value: Float) {
        when (property) {
            "alpha" -> view.alpha = value
            "translationX" -> view.translationX = value
            "translationY" -> view.translationY = value
            "scaleX" -> view.scaleX = value
            "scaleY" -> view.scaleY = value
            "rotation" -> view.rotation = value
        }
    }
}
