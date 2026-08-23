package io.github.valnesfjord.tgwsproxyrs

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A secret spelled out instead of generated.
 *
 * `Config::with_defaults` invents a random one when `--secret` is absent, and a
 * fixed value is what lets the link test assert the whole `secret=` field
 * rather than just its shape.
 */
internal const val TEST_SECRET = "00112233445566778899aabbccddeeff"

/**
 * Arguments that bring a listener up with no startup network I/O whatsoever.
 *
 * Deliberately not [ProxyService.DEFAULT_ARGS]: that carries
 * `--default-domains`, which fetches a domain list over HTTPS *before* the bind
 * in `src/server.rs`, so on a CI emulator with no route out every start would
 * first have to wait out that failure before `onNativeListening` could fire.
 *
 * `--host`/`--link-ip` pin both addresses so neither goes through LAN-IP
 * autodetect, `--port 0` lets the kernel choose so nothing can collide with a
 * socket a previous test has not finished releasing, and `--pool-size 0` drops
 * the upstream warm-up, which is the only outbound work left after the bind.
 */
internal const val OFFLINE_ARGS =
    "--host 127.0.0.1 --port 0 --link-ip 127.0.0.1 --pool-size 0 --secret $TEST_SECRET"

/** How long a start is given to reach `onNativeListening`. */
internal const val LISTEN_TIMEOUT_MS = 15_000L

/**
 * How long the native worker is given to wind down after a stop.
 *
 * Generous on purpose: the shim's own tail is a bounded
 * `rt.shutdown_timeout(2s)`, and this is the budget for "the worker eventually
 * goes away", which is a different question from the tightly bounded one
 * [NativeProxyStopLatencyTest] asks about the calling thread.
 */
internal const val SHUTDOWN_TIMEOUT_MS = 20_000L

/**
 * Start the proxy, tolerating the one error that is purely a timing artefact.
 *
 * `stop_proxy` deliberately does not join the worker (joining on the Android
 * main thread is the ANR this suite exists to prevent), so for a moment after a
 * stop the shim still refuses to start with "still stopping". Every other
 * error — a rejected argument, a failed bind — fails the test where it happens
 * instead of being retried into a timeout.
 */
internal fun startProxy(args: String) {
    val deadline = SystemClock.elapsedRealtime() + SHUTDOWN_TIMEOUT_MS
    while (true) {
        val error = NativeProxy.nativeStart(args) ?: return
        if (!error.contains("still stopping")) {
            throw AssertionError("nativeStart(\"$args\") failed: $error")
        }
        if (SystemClock.elapsedRealtime() > deadline) {
            throw AssertionError("previous run never finished winding down: $error")
        }
        Thread.sleep(50)
    }
}

/** Wait for the `tg://` link the `on_listen` callback publishes. */
internal fun awaitLink(): String = runBlocking {
    withTimeout(LISTEN_TIMEOUT_MS) { ProxyBridge.tgLink.filterNotNull().first() }
}

/**
 * Stop, and wait until the run has actually reported itself finished.
 *
 * `nativeIsRunning()` answers false the instant Stop is requested — that is
 * what keeps the UI from repainting "Running" over a proxy with no listener —
 * so it cannot be the signal here. `ProxyBridge.running` can: it only goes
 * false once the worker has called `onNativeStopped` (or `onNativeError`),
 * which is after `run_with_listen` has returned and the port is free.
 */
internal fun stopAndWait() {
    if (NativeProxy.nativeIsRunning()) {
        NativeProxy.nativeStop()
    }
    runBlocking { withTimeout(SHUTDOWN_TIMEOUT_MS) { ProxyBridge.running.first { !it } } }
}

/**
 * Drop the state a previous test left behind.
 *
 * [ProxyBridge.tgLink] is a `StateFlow`, so without this a test could satisfy
 * [awaitLink] instantly with the *previous* run's link and never actually wait
 * for its own listener. `setRunning(false)` is what nulls it; call this only
 * once [stopAndWait] has confirmed nothing is running.
 */
internal fun resetBridge() {
    ProxyBridge.setRunning(false)
    ProxyBridge.clearError()
}

/**
 * Collects [ProxyBridge.logs] from before the proxy is started.
 *
 * The flow replays nothing, so a collector attached after `nativeStart` would
 * miss the startup banner and everything racing it. `onSubscription` fires
 * once the collector is registered and is the only race-free "I am attached"
 * signal; sleeping here instead would be the flakiest line in the suite.
 *
 * Replay-0 also cuts the other way, which is what makes the negative assertion
 * safe: a line a *previous* run emits before this capture subscribes can never
 * be delivered to it.
 */
internal class LogCapture : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val collected = CopyOnWriteArrayList<String>()
    private val firstLine = CountDownLatch(1)
    private val job: Job

    init {
        val attached = CountDownLatch(1)
        job = scope.launch {
            ProxyBridge.logs
                .onSubscription { attached.countDown() }
                .collect { line ->
                    collected += line
                    firstLine.countDown()
                }
        }
        check(attached.await(5, TimeUnit.SECONDS)) { "log collector never attached" }
    }

    /** Everything delivered so far. */
    val lines: List<String> get() = collected.toList()

    /** Block until the first line arrives, so "logging works" is a wait, not a sleep. */
    fun awaitAnyLine(timeoutMs: Long = LISTEN_TIMEOUT_MS): Boolean =
        firstLine.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        job.cancel()
    }
}
