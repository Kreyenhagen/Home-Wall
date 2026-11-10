package com.kreyenhagen.homewall.led

import com.kreyenhagen.homewall.util.LogBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The one app-wide owner of what the wall shows (§9.1), and the only thing that talks to the
 * [LedController].
 *
 * Latest wins: while a frame is in flight, newer frames replace the pending one, so fast swiping
 * only ever sends the newest route once the previous write finishes. Identical frames aren't
 * resent, and every new controller session resends the current frame.
 *
 * Brightness changes resend the palette (§9.3); the firmware re-renders straight away.
 */
class LedSync(
    private val controller: LedController,
    palette: Flow<Map<Int, Rgb>>,
    scope: CoroutineScope,
    private val log: LogBuffer? = null,
) {
    /** Null means never set: leave the wall alone. */
    private val desired = MutableStateFlow<LedFrame?>(null)

    @Volatile
    private var lastRoute: LedFrame? = null

    private val _lastSent = MutableStateFlow<LedFrame?>(null)

    /** The last frame the controller actually accepted, for the on-screen LED preview (§7.3). */
    val lastSent: StateFlow<LedFrame?> = _lastSent.asStateFlow()

    /** What the wall should show right now. */
    val desiredFrame: StateFlow<LedFrame?> = desired.asStateFlow()

    fun show(frame: LedFrame) {
        desired.value = frame
    }

    /** Shows a route and remembers it as the last route (§9.2). */
    fun showRoute(frame: LedFrame) {
        lastRoute = frame
        show(frame)
    }

    /** For screens without LED content of their own. Does nothing until a route has been shown. */
    fun showLastRoute() {
        lastRoute?.let(::show)
    }

    fun lightsOff() {
        lastRoute = LedFrame.OFF
        show(LedFrame.OFF)
    }

    init {
        scope.launch {
            var sentSession: Int? = null
            var sentFrame: LedFrame? = null
            combine(controller.session, desired, ::Pair)
                .distinctUntilChanged()
                .conflate()
                .collect { (session, frame) ->
                    if (session == null || frame == null) return@collect
                    if (session == sentSession && frame == sentFrame) return@collect
                    try {
                        controller.sendFrame(frame) // suspends until every packet is acked
                        sentSession = session
                        sentFrame = frame
                        _lastSent.value = frame
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The controller reconnects on its own; the new session resends.
                        log?.log(TAG, "Frame not sent: ${e.message}")
                    }
                }
        }
        scope.launch {
            palette
                .distinctUntilChanged()
                .drop(1) // the controller sends the palette itself on every connect
                .conflate()
                .collect { slots ->
                    if (controller.session.value == null) return@collect
                    try {
                        controller.sendPalette(slots)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log?.log(TAG, "Palette not sent: ${e.message}")
                    }
                }
        }
    }

    private companion object {
        const val TAG = "LED"
    }
}
