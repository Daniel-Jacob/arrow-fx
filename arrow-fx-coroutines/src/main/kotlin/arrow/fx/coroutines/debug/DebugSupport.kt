package arrow.fx.coroutines.debug

import kotlinx.atomicfu.atomic
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.jvm.internal.CoroutineStackFrame

internal object DebugProbesImpl {

  private const val ARTIFICIAL_FRAME_MESSAGE = "Coroutine creation stacktrace"
  private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss")

  var sanitizeStackTraces: Boolean = true
  var enableCreationStackTraces: Boolean = true

  @Volatile
  private var installations = 0

  internal val isInstalled: Boolean
    inline get() = installations > 0

  private val capturedCoroutinesMap = ConcurrentHashMap<CoroutineOwner<*>, Boolean>()
  private val capturedCoroutines: Set<CoroutineOwner<*>>
    inline get() = capturedCoroutinesMap.keys

  // To sort coroutines by creation order, used as unique id
  private val sequenceNumber = atomic(0L)

  /*
   * Substitute for service loader, DI between core and debug modules.
   * If the agent was installed via command line -javaagent parameter, do not use byte-byddy to avoud
   */
  private val dynamicAttach = getDynamicAttach()

  /*
   * RW-lock that guards all debug probes state changes.
   * All individual coroutine state transitions are guarded by read-lock
   * and do not interfere with each other.
   * All state reads are guarded by the write lock to guarantee a strongly-consistent
   * snapshot of the system.
   */
  private val coroutineStateLock = ReentrantReadWriteLock()

  /*
   * This is an optimization in the face of KT-29997:
   * Consider suspending call stack a()->b()->c() and c() completes its execution and every call is
   * "almost" in tail position.
   *
   * Then at least three RUNNING -> RUNNING transitions will occur consecutively and complexity of each is O(depth).
   * To avoid that quadratic complexity, we are caching lookup result for such chains in this map and update it incrementally.
   *
   * [DebugCoroutineInfoImpl] keeps a lot of auxiliary information about a coroutine, so we use a weak reference queue
   * to promptly release the corresponding memory when the reference to the coroutine itself was already collected.
   */
  private val callerInfoCache = ConcurrentHashMap<CoroutineStackFrame, DebugCoroutineInfoImpl>()

  @Suppress("UNCHECKED_CAST")
  private fun getDynamicAttach(): Function1<Boolean, Unit>? = runCatching {
    val clz = Class.forName("arrow.fx.coroutines.debug.ByteBuddyDynamicAttach")
    val ctor = clz.constructors[0]
    ctor.newInstance() as Function1<Boolean, Unit>
  }.getOrNull()

  fun install(): Unit = coroutineStateLock.write {
    if (++installations > 1) return
//    if (AgentPremain.isInstalledStatically) return
    dynamicAttach?.invoke(true) // attach
  }

  fun uninstall(): Unit = coroutineStateLock.write {
    check(isInstalled) { "Agent was not installed" }
    if (--installations != 0) return
    capturedCoroutinesMap.clear()
    callerInfoCache.clear()
//    if (AgentPremain.isInstalledStatically) return
    dynamicAttach?.invoke(false) // detach
  }

  // Not guarded by the lock at all, does not really affect consistency
  internal fun <T> probeCoroutineCreated(completion: Continuation<T>): Continuation<T> {
    if (!isInstalled) return completion

    /*
     * If completion already has an owner, it means that we are in scoped coroutine (coroutineScope, withContext etc.),
     * then piggyback on its already existing owner and do not replace completion
     */
    val owner = completion.owner()
    if (owner != null) return completion

    /*
     * Here we replace completion with a sequence of StackTraceFrame objects
     * which represents creation stacktrace, thus making stacktrace recovery mechanism
     * even more verbose (it will attach coroutine creation stacktrace to all exceptions),
     * and then using CoroutineOwner completion as unique identifier of coroutineSuspended/resumed calls.
     */
    val frame = if (enableCreationStackTraces) sanitizeStackTrace(Exception()).toStackTraceFrame()
    else null

    return createOwner(completion, frame)
  }

  internal fun probeCoroutineResumed(frame: Continuation<*>) = updateState(frame, IS_RUNNING)

  internal fun probeCoroutineSuspended(frame: Continuation<*>) = updateState(frame, IS_SUSPENDED)

  // Not guarded by the lock at all, does not really affect consistency
  private fun probeCoroutineCompleted(owner: CoroutineOwner<*>) {
    capturedCoroutinesMap.remove(owner)
    /*
     * This removal is a guard against improperly implemented CoroutineStackFrame
     * and bugs in the compiler.
     */
    val caller = owner.info.lastObservedFrame?.realCaller() ?: return
    callerInfoCache.remove(caller)
  }

  private fun Continuation<*>.owner(): CoroutineOwner<*>? =
    (this as? CoroutineStackFrame)?.owner()

  private tailrec fun CoroutineStackFrame.owner(): CoroutineOwner<*>? =
    if (this is CoroutineOwner<*>) this else callerFrame?.owner()

  private fun <T : Throwable> sanitizeStackTrace(throwable: T): List<StackTraceElement> {
    val stackTrace = throwable.stackTrace
    val size = stackTrace.size
    val probeIndex = stackTrace.indexOfLast { it.className == "kotlin.coroutines.jvm.internal.DebugProbesKt" }

    if (!sanitizeStackTraces) {
      return List(size - probeIndex) {
        if (it == 0) createArtificialFrame(ARTIFICIAL_FRAME_MESSAGE) else stackTrace[it + probeIndex]
      }
    }

    /*
     * Trim intervals of internal methods from the stacktrace (bounds are excluded from trimming)
     * E.g. for sequence [e, i1, i2, i3, e, i4, e, i5, i6, e7]
     * output will be [e, i1, i3, e, i4, e, i5, i7]
     */
    val result = ArrayList<StackTraceElement>(size - probeIndex + 1)
    result += createArtificialFrame(ARTIFICIAL_FRAME_MESSAGE)
    var includeInternalFrame = true
    for (i in (probeIndex + 1) until size - 1) {
      val element = stackTrace[i]
      if (!element.isInternalMethod()) {
        includeInternalFrame = true
        result += element
        continue
      }

      if (includeInternalFrame) {
        result += element
        includeInternalFrame = false
      } else if (stackTrace[i + 1].isInternalMethod()) {
        continue
      } else {
        result += element
        includeInternalFrame = true
      }

    }

    result += stackTrace[size - 1]
    return result
  }

  private fun StackTraceElement.isInternalMethod(): Boolean =
    className.startsWith("arrow.fx.coroutines")

  private fun List<StackTraceElement>.toStackTraceFrame(): StackTraceFrame? =
    foldRight<StackTraceElement, StackTraceFrame?>(null) { frame, acc ->
      StackTraceFrame(acc, frame)
    }

  private fun <T> createOwner(completion: Continuation<T>, frame: StackTraceFrame?): Continuation<T> {
    if (!isInstalled) return completion
    val info = DebugCoroutineInfoImpl(completion.context, frame, sequenceNumber.incrementAndGet())
    val owner = CoroutineOwner(completion, info, frame)
    capturedCoroutinesMap[owner] = true
    if (!isInstalled) capturedCoroutinesMap.clear()
    return owner
  }

  private fun updateState(frame: Continuation<*>, state: String) {
    if (!isInstalled) return

    // KT-29997 is here only since 1.3.30
    if (state == IS_RUNNING && KotlinVersion.CURRENT.isAtLeast(1, 3, 30)) {
      val stackFrame = frame as? CoroutineStackFrame ?: return
      updateRunningState(stackFrame, state)
      return
    }

    // Find ArtificialStackFrame of the coroutine
    val owner = frame.owner() ?: return
    updateState(owner, frame, state)
  }

  private fun updateState(owner: CoroutineOwner<*>, frame: Continuation<*>, state: String) = coroutineStateLock.read {
    if (!isInstalled) return
    owner.info.updateState(state, frame)
  }

  // See comment to callerInfoCache
  private fun updateRunningState(frame: CoroutineStackFrame, state: String): Unit = coroutineStateLock.read {
    if (!isInstalled) return
    // Lookup coroutine info in cache or by traversing stack frame
    val info: DebugCoroutineInfoImpl
    val cached = callerInfoCache.remove(frame)

    if (cached != null) {
      info = cached
    } else {
      info = frame.owner()?.info ?: return
      // Guard against improper implementations of CoroutineStackFrame and bugs in the compiler
      val realCaller = info.lastObservedFrame?.realCaller()
      if (realCaller != null) callerInfoCache.remove(realCaller)
    }

    info.updateState(state, frame as Continuation<*>)
    // Do not cache it for proxy-classes such as ScopeCoroutines
    val caller = frame.realCaller() ?: return
    callerInfoCache[caller] = info
  }

  private tailrec fun CoroutineStackFrame.realCaller(): CoroutineStackFrame? {
    val caller = callerFrame ?: return null
    return if (caller.getStackTraceElement() != null) caller else caller.realCaller()
  }

  /**
   * Private method that dumps coroutines so that different public-facing method can use
   * to produce different result types.
   */
  private inline fun <R : Any> dumpCoroutinesInfoImpl(create: (CoroutineOwner<*>, CoroutineContext) -> R): List<R> =
    coroutineStateLock.write {
      check(isInstalled) { "Debug probes are not installed" }
      capturedCoroutines
        // Stable ordering of coroutines by their sequence number
        .sortedBy { it.info.sequenceNumber }
        // Leave in the dump only the coroutines that were not collected while we were dumping them
        .mapNotNull { owner -> owner.info.context?.let { context -> create(owner, context) } }
    }

  /*
   * Internal (JVM-public) method to be used by IDEA debugger in the future (not used as of 1.4-M3).
   * It is equivalent to [dumpCoroutinesInfo], but returns serializable (and thus less typed) objects.
   */
  fun dumpDebuggerInfo(): List<DebuggerInfo> =
    dumpCoroutinesInfoImpl { owner, context -> DebuggerInfo(owner.info, context) }

  fun dumpCoroutines(out: PrintStream): Unit = synchronized(out) {
    /*
     * This method synchronizes both on `out` and `this` for a reason:
     * 1) Taking a write lock is required to have a consistent snapshot of coroutines.
     * 2) Synchronization on `out` is not required, but prohibits interleaving with any other
     *    (asynchronous) attempt to write to this `out` (System.out by default).
     * Yet this prevents the progress of coroutines until they are fully dumped to the out which we find acceptable compromise.
     */
    dumpCoroutinesSynchronized(out)
  }

  private fun dumpCoroutinesSynchronized(out: PrintStream): Unit = coroutineStateLock.write {
    check(isInstalled) { "Debug probes are not installed" }
    out.print("Coroutines dump ${dateFormat.format(System.currentTimeMillis())}")
    capturedCoroutines
      .sortedBy { it.info.sequenceNumber }
      .forEach { owner ->
        val info = owner.info
        val observedStackTrace = info.lastObservedStackTrace()
        val enhancedStackTrace = enhanceStackTraceWithThreadDumpImpl(info.state, info.lastObservedThread, observedStackTrace)
        val state = if (info.state == IS_RUNNING && enhancedStackTrace === observedStackTrace)
          "${info.state} on ${info.lastObservedThread?.name} (Last suspension stacktrace, not an actual stacktrace)"
        else
          info.state

        out.print("\n\nCoroutine ${owner.delegate}, state: $state")
        if (observedStackTrace.isEmpty()) {
          out.print("\n\tat ${createArtificialFrame(ARTIFICIAL_FRAME_MESSAGE)}")
          printStackTrace(out, info.creationStackTrace)
        } else {
          printStackTrace(out, enhancedStackTrace)
        }
      }
  }

  private fun printStackTrace(out: PrintStream, frames: List<StackTraceElement>) {
    frames.forEach { frame ->
      out.print("\n\tat $frame")
    }
  }

  /**
   * Tries to enhance [coroutineTrace] (obtained by call to [DebugCoroutineInfoImpl.lastObservedStackTrace]) with
   * thread dump of [DebugCoroutineInfoImpl.lastObservedThread].
   *
   * Returns [coroutineTrace] if enhancement was unsuccessful or the enhancement result.
   */
  private fun enhanceStackTraceWithThreadDumpImpl(
    state: String,
    thread: Thread?,
    coroutineTrace: List<StackTraceElement>
  ): List<StackTraceElement> {
    if (state != IS_RUNNING || thread == null) return coroutineTrace
    // Avoid security manager issues
    val actualTrace = runCatching { thread.stackTrace }.getOrNull()
      ?: return coroutineTrace

    /*
     * Here goes heuristic that tries to merge two stacktraces: real one
     * (that has at least one but usually not so many suspend function frames)
     * and coroutine one that has only suspend function frames.
     *
     * Heuristic:
     * 1) Dump lastObservedThread
     * 2) Find the next frame after BaseContinuationImpl.resumeWith (continuation machinery).
     *   Invariant: this method is called under the lock, so such method **should** be present
     *   in continuation stacktrace.
     * 3) Find target method in continuation stacktrace (metadata-based)
     * 4) Prepend dumped stacktrace (trimmed by target frame) to continuation stacktrace
     *
     * Heuristic may fail on recursion and overloads, but it will be automatically improved
     * with KT-29997.
     */
    val indexOfResumeWith = actualTrace.indexOfFirst {
      it.className == "kotlin.coroutines.jvm.internal.BaseContinuationImpl" &&
        it.methodName == "resumeWith" &&
        it.fileName == "ContinuationImpl.kt"
    }

    val (continuationStartFrame, frameSkipped) = findContinuationStartIndex(
      indexOfResumeWith,
      actualTrace,
      coroutineTrace
    )

    if (continuationStartFrame == -1) return coroutineTrace

    val delta = if (frameSkipped) 1 else 0
    val expectedSize = indexOfResumeWith + coroutineTrace.size - continuationStartFrame - 1 - delta
    val result = ArrayList<StackTraceElement>(expectedSize)
    for (index in 0 until indexOfResumeWith - delta) {
      result += actualTrace[index]
    }

    for (index in continuationStartFrame + 1 until coroutineTrace.size) {
      result += coroutineTrace[index]
    }

    return result
  }

  /**
   * Tries to find the lowest meaningful frame above `resumeWith` in the real stacktrace and
   * its match in a coroutines stacktrace (steps 2-3 in heuristic).
   *
   * This method does more than just matching `realTrace.indexOf(resumeWith) - 1`:
   * If method above `resumeWith` has no line number (thus it is `stateMachine.invokeSuspend`),
   * it's skipped and attempt to match next one is made because state machine could have been missing in the original coroutine stacktrace.
   *
   * Returns index of such frame (or -1) and flag indicating whether frame with state machine was skipped
   */
  private fun findContinuationStartIndex(indexOfResumeWith: Int, actualTrace: Array<StackTraceElement>, coroutineTrace: List<StackTraceElement>): Pair<Int, Boolean> {
    val result = findIndexOfFrame(indexOfResumeWith - 1, actualTrace, coroutineTrace)
    return if (result == -1) findIndexOfFrame(indexOfResumeWith - 2, actualTrace, coroutineTrace) to true
    else result to false
  }

  private fun findIndexOfFrame(frameIndex: Int, actualTrace: Array<StackTraceElement>, coroutineTrace: List<StackTraceElement>): Int =
    actualTrace.getOrNull(frameIndex)?.run {
      coroutineTrace.indexOfFirst {
        it.fileName == fileName &&
          it.className == className &&
          it.methodName == methodName
      }
    } ?: -1

  /**
   * This class is injected as completion of all continuations in [probeCoroutineCompleted].
   * It is owning the coroutine info and responsible for managing all its external info related to debug agent.
   */
  internal class CoroutineOwner<T>(
    @JvmField val delegate: Continuation<T>,
    @JvmField val info: DebugCoroutineInfoImpl,
    private val frame: CoroutineStackFrame?
  ) : Continuation<T> by delegate, CoroutineStackFrame {

    override val callerFrame: CoroutineStackFrame?
      get() = frame?.callerFrame

    override fun getStackTraceElement(): StackTraceElement? = frame?.getStackTraceElement()

    override fun resumeWith(result: Result<T>) {
      probeCoroutineCompleted(this)
      delegate.resumeWith(result)
    }

    override fun toString(): String = delegate.toString()
  }

  private fun createArtificialFrame(message: String): StackTraceElement =
    StackTraceElement("\b\b\b($message", "\b", "\b", -1)

}
