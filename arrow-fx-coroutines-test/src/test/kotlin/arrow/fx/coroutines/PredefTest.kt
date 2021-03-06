package arrow.fx.coroutines

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

class PredefTest : ArrowFxSpec(spec = {

  "suspended always suspends" {
    checkAll(Arb.int()) { i ->
      val promise = UnsafePromise<Int>()

      val x = i.suspended()
        .startCoroutineUninterceptedOrReturn(Continuation(EmptyCoroutineContext) {
          promise.complete(it)
        })

      x shouldBe COROUTINE_SUSPENDED
      promise.join() shouldBe i
    }
  }

  "shift" {
    checkAll(Arb.string(), Arb.string()) { a, b ->
      val t0 = threadName.invoke()

      Resource.singleThreadContext(a)
        .zip(Resource.singleThreadContext(b))
        .use { (ui, io) ->
          t0 shouldBe threadName.invoke()

          ui.shift()
          threadName.invoke() shouldBe a

          io.shift()
          threadName.invoke() shouldBe b
        }
    }
  }
})
