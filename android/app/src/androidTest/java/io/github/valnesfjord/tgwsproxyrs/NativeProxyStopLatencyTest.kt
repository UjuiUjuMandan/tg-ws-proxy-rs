package io.github.valnesfjord.tgwsproxyrs

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * `nativeStop()` must not block the thread that calls it.
 *
 * Kept in its own class, and not merged into [NativeProxyContractTest], for one
 * reason: it is the only test here that asserts on wall-clock time, so it is
 * the only one that a pathologically slow runner could turn red on its own. A
 * separate class can be excluded by name
 * (`-Pandroid.testInstrumentationRunnerArguments.notClass=…`) without also
 * dropping the contract tests, which are the ones that catch a JNI symbol
 * mismatch and have no timing component at all.
 */
@RunWith(AndroidJUnit4::class)
class NativeProxyStopLatencyTest {
    @Before
    fun startFromIdle() {
        stopAndWait()
        resetBridge()
    }

    @After
    fun leaveIdle() {
        stopAndWait()
        resetBridge()
    }

    /**
     * The regression test for the ANR: `stop_proxy` used to `join()` the worker
     * thread, which waits not just for `block_on` to return but for the whole
     * multi-thread runtime's `Drop` — and that blocks on any outstanding
     * blocking task, such as a `getaddrinfo` the network has stalled. On the
     * main thread that is an ANR, not a pause.
     */
    @Test
    fun stopReturnsPromptlyOnTheMainThread() {
        startProxy(OFFLINE_ARGS)
        awaitLink()
        // Without this the test could pass vacuously against a stop that had
        // nothing to stop.
        assertTrue("the proxy should be running before it is stopped", NativeProxy.nativeIsRunning())

        val blockedMs = AtomicLong()
        // runOnMainSync blocks this thread until the block completes, and the
        // block itself runs on the real main looper. Timing inside it, rather
        // than around it, keeps the runner's own dispatch latency — the part
        // that genuinely varies under load — out of the measurement.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val started = SystemClock.elapsedRealtime()
            NativeProxy.nativeStop()
            blockedMs.set(SystemClock.elapsedRealtime() - started)
        }
        // Logged so the real margin is visible in the CI output rather than
        // guessed at the next time this budget is questioned.
        Log.i(TAG, "nativeStop() blocked the main thread for ${blockedMs.get()} ms")
        assertTrue(
            "nativeStop() blocked the main thread for ${blockedMs.get()} ms," +
                " budget is $MAIN_THREAD_BUDGET_MS ms",
            blockedMs.get() < MAIN_THREAD_BUDGET_MS,
        )

        // A non-blocking stop necessarily returns before the worker is gone, so
        // the shutdown itself gets its own, far more generous budget. Asserting
        // it here is what stops "returned quickly" from being satisfiable by a
        // stop that never took effect.
        stopAndWait()
        assertFalse("the worker should be gone after a stop", NativeProxy.nativeIsRunning())
    }

    private companion object {
        const val TAG = "NativeProxyStopLatency"

        /**
         * With no join on the caller, `nativeStop()` is a `watch::Sender::send`
         * behind one uncontended mutex — microseconds, not milliseconds. The
         * budget is set three orders of magnitude above that so a cold,
         * software-rendered emulator cannot trip it, and still well under the
         * 5 s input-dispatch ANR threshold so a regression to joining the
         * runtime's teardown fails here instead of shipping. If it ever does
         * misfire, raise it toward 3000 rather than deleting the assertion:
         * anything under 5 s still catches the regression.
         */
        const val MAIN_THREAD_BUDGET_MS = 1_500L
    }
}
