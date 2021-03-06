package ru.fix.stdlib.ratelimiter

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.lessThanOrEqualTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.aggregating.profiler.NoopProfiler
import ru.fix.aggregating.profiler.ProfiledCall
import ru.fix.dynamic.property.api.DynamicProperty
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiFunction
import java.util.stream.Collectors

class RateLimitedDispatcherTest {

    @Test
    fun testSubmitIncrementThroughput() {
        createDispatcher(2000).use {
            assertTimeoutPreemptively(Duration.ofSeconds(15), {
                testThroughput(BiFunction { call, counter -> this.submitIncrement(it, call, counter) })
            })
        }
    }

    @Test
    fun testComposeIncrementThroughput() {
        createDispatcher(2000).use {
            assertTimeoutPreemptively(Duration.ofSeconds(15), {
                testThroughput(BiFunction { call, counter -> this.composeIncrement(it, call, counter) })
            })
        }
    }

    /**
     * When dispatcher closingTimeout is anought for pending tasks to complete
     * such tasks will complete normally
     */
    @Test
    fun shutdown_tasksCompletedInTimeout_areCompletedNormally() {

        createDispatcher(5_000).use { dispatch ->
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {

                val blockingTaskIsStarted = CountDownLatch(1)


                dispatch.submit {
                    blockingTaskIsStarted.countDown()
                    //Due to blocking nature of dispatch.close we hae to use sleep
                    Thread.sleep(1000)
                }
                val futures = ArrayList<CompletableFuture<*>>()
                for (i in 1..3) {
                    futures.add(dispatch.submit({ }))
                }

                blockingTaskIsStarted.await()
                dispatch.close()

                CompletableFuture.allOf(*futures.toTypedArray()).exceptionally { null } .join()

                futures.forEach { future: CompletableFuture<*> ->
                    assertTrue(future.isDone)
                    assertFalse(future.isCompletedExceptionally)
                }
            }
        }
    }

    @Test
    fun shutdown_tasksNotCompletedInTimeout_areCompletedExceptionally() {
        createDispatcher(0).use { dispatch ->
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                val blockingTaskIsStarted = CountDownLatch(1)

                dispatch.submit {
                    blockingTaskIsStarted.countDown()
                    //Due to blocking nature of dispatch.close we hae to use sleep
                    Thread.sleep(1000)
                }

                val futures = ArrayList<CompletableFuture<*>>()
                for (i in 1..3) {
                    futures.add(dispatch.submit { })
                }

                blockingTaskIsStarted.await()
                dispatch.close()

                CompletableFuture.allOf(*futures.toTypedArray()).exceptionally { null }.join()

                futures.forEach { future: CompletableFuture<*> ->
                    assertTrue(future.isDone)
                    assertTrue(future.isCompletedExceptionally)
                }
            }
        }
    }

    private fun createDispatcher(closingTimeout: Long): RateLimitedDispatcher {
        val limiter = ConfigurableRateLimiter("test-rate-limiter", RATE_LIMIT)
        return RateLimitedDispatcher("test-rate-limiter-dispatcher", limiter, NoopProfiler(),
                DynamicProperty.of(closingTimeout))
    }


    private fun testThroughput(biFunction: BiFunction<ProfiledCall, AtomicInteger, CompletableFuture<Int>>) {
        val counter = AtomicInteger(0)
        val profiler = AggregatingProfiler()
        val profilerReporter = profiler.createReporter()

        profilerReporter.buildReportAndReset()
        val profiledCall = profiler.profiledCall(RateLimitedDispatcher::class.toString())

        val features = ArrayList<CompletableFuture<Int>>()
        for (i in 0 until ITERATIONS) {
            features.add(biFunction.apply(profiledCall, counter))
        }

        CompletableFuture.allOf(*features.toTypedArray()).join()
        val report = profilerReporter.buildReportAndReset().profilerCallReports[0]

        val results = features
                .stream()
                .map<Int>({ it.join() })
                .collect(Collectors.toList())

        for (i in 0 until ITERATIONS) {
            assertTrue(results.contains(i))
        }

        LOGGER.info("Current throughput " + report.stopThroughputAvg)

        assertThat(report.stopThroughputAvg, lessThanOrEqualTo((RATE_LIMIT * 1.25 * 1000.toDouble())))


        assertEquals(ITERATIONS, counter.get())
    }

    private fun submitIncrement(dispatcher: RateLimitedDispatcher,
                                call: ProfiledCall,
                                counter: AtomicInteger): CompletableFuture<Int> {
        return dispatcher.submit {
            call.start()
            val result = counter.getAndIncrement()
            call.stop()
            result
        }
    }

    private fun composeIncrement(dispatcher: RateLimitedDispatcher,
                                 call: ProfiledCall,
                                 counter: AtomicInteger): CompletableFuture<Int> {
        return dispatcher.compose {
            call.start()
            val future = CompletableFuture.supplyAsync<Int>({ counter.getAndIncrement() })
            call.stop()
            future
        }
    }

    companion object {

        private val LOGGER = LoggerFactory.getLogger(RateLimitedDispatcherTest::class.java)
        private val RATE_LIMIT = 570
        private val ITERATIONS = 5 * RATE_LIMIT
    }

}
