package android.animation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

// Android's property animation on iOS: animators stepped on the main thread about every frame, as
// Android's Choreographer steps them, until they end or are cancelled.

/** How time maps to progress, Android's. */
fun interface TimeInterpolator {
    fun getInterpolation(input: Float): Float
}

/** An animation, Android's. */
abstract class Animator {
    /** What an animation tells as it starts and ends, Android's. */
    interface AnimatorListener {
        fun onAnimationStart(animation: Animator)

        fun onAnimationEnd(animation: Animator)

        fun onAnimationCancel(animation: Animator)

        fun onAnimationRepeat(animation: Animator)
    }

    protected val listeners = ArrayList<AnimatorListener>()

    fun addListener(listener: AnimatorListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AnimatorListener) {
        listeners.remove(listener)
    }

    fun removeAllListeners() = listeners.clear()

    abstract fun start()

    abstract fun cancel()

    abstract fun end()

    abstract fun isRunning(): Boolean

    open fun isStarted(): Boolean = isRunning()

    /** Java's isRunning() and isStarted(), as Kotlin also reads them. */
    val isRunning: Boolean get() = isRunning()

    val isStarted: Boolean get() = isStarted()

    abstract fun setDuration(duration: Long): Animator

    abstract fun getDuration(): Long

    open fun setStartDelay(startDelay: Long) {}

    open fun setInterpolator(value: TimeInterpolator?) {}
}

/** Android's listener with every callback empty. */
abstract class AnimatorListenerAdapter : Animator.AnimatorListener {
    override fun onAnimationStart(animation: Animator) {}

    override fun onAnimationEnd(animation: Animator) {}

    override fun onAnimationCancel(animation: Animator) {}

    override fun onAnimationRepeat(animation: Animator) {}
}

/** An animation of numbers over time, Android's. */
open class ValueAnimator : Animator() {
    /** Each step's value, Android's. */
    fun interface AnimatorUpdateListener {
        fun onAnimationUpdate(animation: ValueAnimator)
    }

    private var from = 0f
    private var to = 1f
    private var ints = false
    private var durationMs = 300L
    private var delayMs = 0L
    private var interpolatorValue: TimeInterpolator = AccelerateDecelerate
    private val updates = ArrayList<AnimatorUpdateListener>()
    private var job: Job? = null
    private var fraction = 0f

    var animatedValue: Any = 0f
        private set

    val animatedFraction: Float get() = fraction

    var duration: Long
        get() = durationMs
        set(value) {
            durationMs = value
        }

    var interpolator: TimeInterpolator?
        get() = interpolatorValue
        set(value) {
            interpolatorValue = value ?: Linear
        }

    var startDelay: Long
        get() = delayMs
        set(value) {
            delayMs = value
        }

    var repeatCount: Int = 0
    var repeatMode: Int = RESTART

    fun setFloatValues(vararg values: Float) {
        from = values.firstOrNull() ?: 0f
        to = values.lastOrNull() ?: 0f
        ints = false
    }

    fun setIntValues(vararg values: Int) {
        from = (values.firstOrNull() ?: 0).toFloat()
        to = (values.lastOrNull() ?: 0).toFloat()
        ints = true
    }

    fun addUpdateListener(listener: AnimatorUpdateListener) {
        updates.add(listener)
    }

    fun removeUpdateListener(listener: AnimatorUpdateListener) {
        updates.remove(listener)
    }

    fun removeAllUpdateListeners() = updates.clear()

    override fun setDuration(duration: Long): ValueAnimator = apply { durationMs = duration }

    override fun getDuration(): Long = durationMs

    override fun setStartDelay(startDelay: Long) {
        delayMs = startDelay
    }

    override fun setInterpolator(value: TimeInterpolator?) {
        interpolatorValue = value ?: Linear
    }

    override fun isRunning(): Boolean = job?.isActive == true

    override fun start() {
        job?.cancel()
        listeners.toList().forEach { it.onAnimationStart(this) }
        step(0f)
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            var round = 0
            while (true) {
                val began = TimeSource.Monotonic.markNow()
                while (true) {
                    val elapsed = began.elapsedNow().inWholeMilliseconds
                    val t = if (durationMs <= 0) 1f else (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                    val forward = repeatMode == RESTART || round % 2 == 0
                    step(if (forward) t else 1f - t)
                    if (t >= 1f) break
                    delay(FRAME_MS)
                }
                if (repeatCount != INFINITE && round >= repeatCount) break
                round++
                listeners.toList().forEach { it.onAnimationRepeat(this@ValueAnimator) }
            }
            job = null
            listeners.toList().forEach { it.onAnimationEnd(this@ValueAnimator) }
        }
    }

    override fun cancel() {
        val running = job ?: return
        job = null
        running.cancel()
        listeners.toList().forEach { it.onAnimationCancel(this) }
        listeners.toList().forEach { it.onAnimationEnd(this) }
    }

    override fun end() {
        val running = job ?: return
        job = null
        running.cancel()
        step(1f)
        listeners.toList().forEach { it.onAnimationEnd(this) }
    }

    private fun step(t: Float) {
        fraction = t
        val v = from + (to - from) * interpolatorValue.getInterpolation(t)
        animatedValue = if (ints) v.toInt() else v
        updates.toList().forEach { it.onAnimationUpdate(this) }
    }

    companion object {
        const val RESTART = 1
        const val REVERSE = 2
        const val INFINITE = -1
        private const val FRAME_MS = 16L

        internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun ofFloat(vararg values: Float): ValueAnimator = ValueAnimator().also { it.setFloatValues(*values) }

        fun ofInt(vararg values: Int): ValueAnimator = ValueAnimator().also { it.setIntValues(*values) }

        private val Linear = TimeInterpolator { it }

        private val AccelerateDecelerate = TimeInterpolator { (kotlin.math.cos((it + 1) * kotlin.math.PI) / 2.0 + 0.5).toFloat() }
    }
}

/** One property's values over an animation, Android's: by name, or by a Property object. */
class PropertyValuesHolder private constructor(
    internal val name: String,
    internal val property: android.util.Property<Any, Float>?,
    internal val floats: FloatArray?,
    internal val ints: IntArray?,
) {
    /** The value at [fraction] (already interpolated), through each pair of keyframes in turn. */
    internal fun valueAt(fraction: Float): Number {
        fun <T> along(count: Int, at: (Int) -> T, mix: (T, T, Float) -> T): T {
            if (count == 1) return at(0)
            val scaled = fraction.coerceIn(0f, 1f) * (count - 1)
            val index = scaled.toInt().coerceAtMost(count - 2)
            return mix(at(index), at(index + 1), scaled - index)
        }
        floats?.let { values -> return along(values.size, { values[it] }) { a, b, t -> a + (b - a) * t } }
        val values = ints ?: return 0
        return along(values.size, { values[it] }) { a, b, t -> (a + (b - a) * t).toInt() }
    }

    companion object {
        fun ofFloat(propertyName: String, vararg values: Float): PropertyValuesHolder = PropertyValuesHolder(propertyName, null, values, null)

        @Suppress("UNCHECKED_CAST")
        fun <T> ofFloat(property: android.util.Property<T, Float>, vararg values: Float): PropertyValuesHolder =
            PropertyValuesHolder(property.name, property as android.util.Property<Any, Float>, values, null)

        fun ofInt(propertyName: String, vararg values: Int): PropertyValuesHolder = PropertyValuesHolder(propertyName, null, null, values)
    }
}

/**
 * An animation of a target's properties, Android's ObjectAnimator. Kotlin on iOS has no reflection,
 * so a property named by text is one of a View's that Android's player animates (alpha, the
 * translations, the scales, rotation, a ProgressBar's progress); a Property object sets itself.
 */
class ObjectAnimator private constructor(private var target: Any?, private val holders: List<PropertyValuesHolder>) : ValueAnimator() {
    init {
        addUpdateListener {
            val fraction = interpolator?.getInterpolation(animatedFraction) ?: animatedFraction
            for (holder in holders) apply(holder, holder.valueAt(fraction))
        }
    }

    fun setTarget(target: Any?) {
        this.target = target
    }

    private fun apply(holder: PropertyValuesHolder, value: Number) {
        val on = target ?: return
        holder.property?.let {
            it.set(on, value.toFloat())
            return
        }
        val view = on as? android.view.View ?: return
        when (holder.name) {
            "alpha" -> view.alpha = value.toFloat()
            "translationX" -> view.translationX = value.toFloat()
            "translationY" -> view.translationY = value.toFloat()
            "scaleX" -> view.scaleX = value.toFloat()
            "scaleY" -> view.scaleY = value.toFloat()
            "rotation" -> view.rotation = value.toFloat()
            "progress" -> (view as? android.widget.ProgressBar)?.progress = value.toInt()
        }
    }

    companion object {
        fun ofInt(target: Any?, propertyName: String, vararg values: Int): ObjectAnimator =
            ObjectAnimator(target, listOf(PropertyValuesHolder.ofInt(propertyName, *values))).also { it.setIntValues(*values) }

        fun ofFloat(target: Any?, propertyName: String, vararg values: Float): ObjectAnimator =
            ObjectAnimator(target, listOf(PropertyValuesHolder.ofFloat(propertyName, *values))).also { it.setFloatValues(*values) }

        fun <T> ofFloat(target: T, property: android.util.Property<T, Float>, vararg values: Float): ObjectAnimator =
            ObjectAnimator(target, listOf(PropertyValuesHolder.ofFloat(property, *values))).also { it.setFloatValues(*values) }

        fun ofPropertyValuesHolder(target: Any?, vararg values: PropertyValuesHolder): ObjectAnimator =
            ObjectAnimator(target, values.toList()).also { it.setFloatValues(0f, 1f) }
    }
}
