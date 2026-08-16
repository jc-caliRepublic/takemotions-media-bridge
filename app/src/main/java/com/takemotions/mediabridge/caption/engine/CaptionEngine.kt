package com.takemotions.mediabridge.caption.engine

/**
 * Where an engine reports back. All callbacks may be invoked from engine-owned
 * threads; implementations must be thread-safe.
 */
class EngineSink(
    val onPartial: (String) -> Unit,
    val onFinal: (String) -> Unit,
    val onLog: (String) -> Unit,
    /** Fired once when the engine is loaded and actually recognizing (lets the
     *  status feed distinguish "starting" — e.g. a model download — from "silent"). */
    val onReady: () -> Unit = {},
)

/**
 * A continuous speech-to-text engine fed with the captured playback mix.
 *
 * Contract:
 *  - [start] and [feed] are called on the service's single engine thread;
 *    [start] may block while a model loads or downloads.
 *  - [feed] receives 16 kHz mono 16-bit PCM.
 *  - [stop] must be idempotent.
 */
interface CaptionEngine {
    val label: String
    fun start()
    fun feed(samples: ShortArray, n: Int)
    fun stop()
}
