package androidx.fragment.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

// androidx's Fragments on iOS, for the player's (TrackPickerFragment): added to a container View
// of their activity by a transaction run after the current work, as Android runs it, with the
// lifecycle calls in Android's order and a back stack that Back and popBackStack unwind.

/** A piece of an activity's screen with a lifecycle of its own, androidx's. */
open class Fragment : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    var arguments: Bundle? = null

    var activity: FragmentActivity? = null
        internal set

    internal var manager: FragmentManager? = null

    var tag: String? = null
        internal set

    var id: Int = 0
        internal set

    var view: View? = null
        internal set

    val isAdded: Boolean get() = activity != null && manager != null

    val context: Context? get() = activity

    fun requireArguments(): Bundle = arguments ?: throw IllegalStateException("Fragment $this does not have any arguments.")

    fun requireContext(): Context = activity ?: throw IllegalStateException("Fragment $this not attached to a context.")

    fun requireActivity(): FragmentActivity = activity ?: throw IllegalStateException("Fragment $this not attached to an activity.")

    fun requireView(): View = view ?: throw IllegalStateException("Fragment $this did not return a View from onCreateView() or this was called before onCreateView().")

    val parentFragmentManager: FragmentManager
        get() = manager ?: throw IllegalStateException("Fragment $this not associated with a fragment manager.")

    open fun onAttach(context: Context) {}

    open fun onCreate(savedInstanceState: Bundle?) {}

    open fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = null

    open fun onViewCreated(view: View, savedInstanceState: Bundle?) {}

    open fun onStart() {}

    open fun onResume() {}

    open fun onPause() {}

    open fun onStop() {}

    open fun onDestroyView() {}

    open fun onDestroy() {}

    open fun onDetach() {}

    internal fun moveTo(state: Lifecycle.State) {
        if (registry.currentState != Lifecycle.State.DESTROYED || state == Lifecycle.State.DESTROYED) registry.currentState = state
    }
}

/** Adds and removes an activity's Fragments, androidx's. */
class FragmentManager internal constructor(private val activity: FragmentActivity) {
    /** A step of the back stack: what one committed transaction did, to undo on a pop. */
    private class Entry(val name: String?, val added: List<Fragment>, val removed: List<Fragment>)

    private val added = ArrayList<Fragment>()
    private val backStack = ArrayList<Entry>()
    private val listeners = ArrayList<OnBackStackChangedListener>()

    /** What a back stack change tells, androidx's. */
    fun interface OnBackStackChangedListener {
        fun onBackStackChanged()
    }

    /** Never saved on iOS: the activity is not torn down and rebuilt as Android's can be. */
    val isStateSaved: Boolean get() = false

    val isDestroyed: Boolean get() = false

    val fragments: List<Fragment> get() = added.toList()

    val backStackEntryCount: Int get() = backStack.size

    fun beginTransaction(): FragmentTransaction = FragmentTransaction(this)

    fun findFragmentByTag(tag: String?): Fragment? = added.lastOrNull { it.tag == tag }

    fun findFragmentById(id: Int): Fragment? = added.lastOrNull { it.id == id }

    fun addOnBackStackChangedListener(listener: OnBackStackChangedListener) {
        listeners.add(listener)
    }

    fun removeOnBackStackChangedListener(listener: OnBackStackChangedListener) {
        listeners.remove(listener)
    }

    /** Undoes the newest transaction on the back stack, after the current work, as Android's. */
    fun popBackStack() {
        android.os.Handler().post { popBackStackImmediate() }
    }

    /** Undoes back to the transaction named [name], and that one too with [POP_BACK_STACK_INCLUSIVE]. */
    fun popBackStack(name: String?, flags: Int) {
        android.os.Handler().post { popBackStackImmediate(name, flags) }
    }

    fun popBackStackImmediate(): Boolean {
        val entry = backStack.removeLastOrNull() ?: return false
        undo(entry)
        listeners.toList().forEach { it.onBackStackChanged() }
        return true
    }

    fun popBackStackImmediate(name: String?, flags: Int): Boolean {
        val index = if (name == null) {
            if (flags and POP_BACK_STACK_INCLUSIVE != 0) 0 else backStack.size
        } else {
            val at = backStack.indexOfLast { it.name == name }
            if (at < 0) return false
            if (flags and POP_BACK_STACK_INCLUSIVE != 0) at else at + 1
        }
        if (index >= backStack.size) return false
        while (backStack.size > index) undo(backStack.removeAt(backStack.lastIndex))
        listeners.toList().forEach { it.onBackStackChanged() }
        return true
    }

    private fun undo(entry: Entry) {
        entry.added.reversed().forEach(::detach)
        entry.removed.forEach { attach(it, it.id, it.tag) }
    }

    internal fun run(ops: List<FragmentTransaction.Op>, backStackName: String?, onBackStack: Boolean) {
        val added = ArrayList<Fragment>()
        val removed = ArrayList<Fragment>()
        for (op in ops) when (op) {
            is FragmentTransaction.Op.Add -> {
                attach(op.fragment, op.containerId, op.tag)
                added += op.fragment
            }
            is FragmentTransaction.Op.Remove -> if (op.fragment in this.added) {
                detach(op.fragment)
                removed += op.fragment
            }
            is FragmentTransaction.Op.Replace -> {
                this.added.filter { it.id == op.containerId }.forEach {
                    detach(it)
                    removed += it
                }
                attach(op.fragment, op.containerId, op.tag)
                added += op.fragment
            }
        }
        if (onBackStack) {
            backStack += Entry(backStackName, added, removed)
            listeners.toList().forEach { it.onBackStackChanged() }
        }
    }

    /** Android's order: attach, create, the view into its container, then started and resumed with the activity. */
    private fun attach(fragment: Fragment, containerId: Int, tag: String?) {
        fragment.activity = activity
        fragment.manager = this
        fragment.id = containerId
        fragment.tag = tag
        added += fragment
        fragment.onAttach(activity)
        fragment.onCreate(null)
        fragment.moveTo(Lifecycle.State.CREATED)
        val container = if (containerId != 0) activity.findViewById<ViewGroup>(containerId) else null
        val view = fragment.onCreateView(LayoutInflater.from(activity), container, null)
        fragment.view = view
        if (view != null) {
            container?.addView(view)
            fragment.onViewCreated(view, null)
        }
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            fragment.onStart()
            fragment.moveTo(Lifecycle.State.STARTED)
        }
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            fragment.onResume()
            fragment.moveTo(Lifecycle.State.RESUMED)
        }
    }

    private fun detach(fragment: Fragment) {
        if (!added.remove(fragment)) return
        if (fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) fragment.onPause()
        if (fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) fragment.onStop()
        fragment.view?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        fragment.onDestroyView()
        fragment.view = null
        fragment.onDestroy()
        fragment.moveTo(Lifecycle.State.DESTROYED)
        fragment.onDetach()
        fragment.activity = null
        fragment.manager = null
    }

    /** The activity's lifecycle reaching its Fragments. */
    internal fun dispatch(state: Lifecycle.State) {
        for (fragment in added.toList()) {
            when (state) {
                Lifecycle.State.STARTED -> if (!fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    fragment.onStart()
                    fragment.moveTo(Lifecycle.State.STARTED)
                } else if (fragment.lifecycle.currentState == Lifecycle.State.RESUMED) {
                    fragment.onPause()
                    fragment.moveTo(Lifecycle.State.STARTED)
                }
                Lifecycle.State.RESUMED -> {
                    fragment.onResume()
                    fragment.moveTo(Lifecycle.State.RESUMED)
                }
                Lifecycle.State.CREATED -> if (fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    fragment.onStop()
                    fragment.moveTo(Lifecycle.State.CREATED)
                }
                Lifecycle.State.DESTROYED -> detach(fragment)
                else -> Unit
            }
        }
    }

    companion object {
        const val POP_BACK_STACK_INCLUSIVE = 1
    }
}

/** A set of Fragment changes run together, androidx's. */
class FragmentTransaction internal constructor(private val manager: FragmentManager) {
    internal sealed interface Op {
        class Add(val containerId: Int, val fragment: Fragment, val tag: String?) : Op

        class Replace(val containerId: Int, val fragment: Fragment, val tag: String?) : Op

        class Remove(val fragment: Fragment) : Op
    }

    private val ops = ArrayList<Op>()
    private var backStackName: String? = null
    private var onBackStack = false
    private var committed = false

    fun add(containerViewId: Int, fragment: Fragment, tag: String?): FragmentTransaction = apply { ops += Op.Add(containerViewId, fragment, tag) }

    fun add(containerViewId: Int, fragment: Fragment): FragmentTransaction = add(containerViewId, fragment, null)

    fun add(fragment: Fragment, tag: String?): FragmentTransaction = add(0, fragment, tag)

    fun replace(containerViewId: Int, fragment: Fragment, tag: String?): FragmentTransaction = apply { ops += Op.Replace(containerViewId, fragment, tag) }

    fun replace(containerViewId: Int, fragment: Fragment): FragmentTransaction = replace(containerViewId, fragment, null)

    fun remove(fragment: Fragment): FragmentTransaction = apply { ops += Op.Remove(fragment) }

    fun addToBackStack(name: String?): FragmentTransaction = apply {
        onBackStack = true
        backStackName = name
    }

    fun setReorderingAllowed(reorderingAllowed: Boolean): FragmentTransaction = this

    fun setCustomAnimations(enter: Int, exit: Int): FragmentTransaction = this

    fun setCustomAnimations(enter: Int, exit: Int, popEnter: Int, popExit: Int): FragmentTransaction = this

    /** Runs after the current work on the main thread, as Android's commit. */
    fun commit(): Int {
        check(!committed) { "commit already called" }
        committed = true
        val run = ops.toList()
        android.os.Handler().post { manager.run(run, backStackName, onBackStack) }
        return 0
    }

    fun commitAllowingStateLoss(): Int = commit()

    /** Runs now, as Android's; not for a transaction on the back stack, as there. */
    fun commitNow() {
        check(!onBackStack) { "This transaction is already being added to the back stack" }
        check(!committed) { "commit already called" }
        committed = true
        manager.run(ops.toList(), null, false)
    }

    fun commitNowAllowingStateLoss() = commitNow()
}

/** An activity that holds Fragments, androidx's. */
open class FragmentActivity : ComponentActivity() {
    val supportFragmentManager: FragmentManager = FragmentManager(this)

    override fun onStart() {
        super.onStart()
        supportFragmentManager.dispatch(Lifecycle.State.STARTED)
    }

    override fun onResume() {
        super.onResume()
        supportFragmentManager.dispatch(Lifecycle.State.RESUMED)
    }

    override fun onPause() {
        supportFragmentManager.dispatch(Lifecycle.State.STARTED)
        super.onPause()
    }

    override fun onStop() {
        supportFragmentManager.dispatch(Lifecycle.State.CREATED)
        super.onStop()
    }

    override fun onDestroy() {
        supportFragmentManager.dispatch(Lifecycle.State.DESTROYED)
        super.onDestroy()
    }
}
