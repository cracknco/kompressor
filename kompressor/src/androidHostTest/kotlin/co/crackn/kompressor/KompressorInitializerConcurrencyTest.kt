package co.crackn.kompressor

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class KompressorInitializerConcurrencyTest {

    private val threadCount = 32

    @BeforeTest
    fun resetBefore() {
        KompressorContext.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        KompressorContext.resetForTest()
    }

    @Test
    fun concurrentInit_singleExecution_sameReference() {
        val mockContext = mockk<Context> {
            every { applicationContext } returns this
        }

        val barrier = CyclicBarrier(threadCount)
        val errors = AtomicInteger(0)
        val latch = CountDownLatch(threadCount)
        val results = arrayOfNulls<Context>(threadCount)

        val threads = List(threadCount) { index ->
            Thread {
                try {
                    barrier.await()
                    KompressorInitializer().create(mockContext)
                    results[index] = KompressorContext.appContext
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        latch.await()

        errors.get() shouldBe 0
        KompressorContext.initCount shouldBe 1

        val expected = results[0]
        results.forEach { it shouldBe expected }
    }
}
