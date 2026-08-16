package com.takemotions.mediabridge.caption.engine

import android.os.Build
import android.os.ParcelFileDescriptor
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

/**
 * Engine A': ML Kit GenAI Speech Recognition (1.0.0-alpha1), MODE_BASIC.
 * Google's on-device recognizer with a designed-in external-PCM input: we pump the
 * captured playback mix into a pipe and hand the read end over as [AudioSource.fromPfd].
 *
 * Expected format per docs: raw headerless 16-bit PCM, mono, 16 kHz, delivered at
 * real-time rate — which is exactly what the capture service produces.
 */
class MlKitEngine(
    private val sink: EngineSink,
    private val recognitionLocale: Locale = Locale.US,
) : CaptionEngine {

    companion object {
        /** ML Kit GenAI speech recognition is Android 12+; the caption feature gates on this. */
        const val MIN_API = 31
    }

    override val label = "ML Kit (Google on-device, ${recognitionLocale.toLanguageTag()})"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var writeStream: ParcelFileDescriptor.AutoCloseOutputStream? = null

    @Volatile
    private var wantRunning = false
    private var sessionCount = 0
    private val restartTimes = ArrayDeque<Long>()
    private var byteBuf = ByteArray(0)

    override fun start() {
        if (Build.VERSION.SDK_INT < MIN_API) {
            sink.onLog("ML Kit: needs Android 12+ (this device is API ${Build.VERSION.SDK_INT})")
            return
        }
        val options = SpeechRecognizerOptions.Builder().apply {
            locale = recognitionLocale
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }.build()
        val rec = SpeechRecognition.getClient(options)
        recognizer = rec

        // Blocking bring-up is fine: we own the engine thread, and the capture side
        // buffers (and eventually drops) audio while this runs.
        val ready = runBlocking {
            try {
                when (val status = rec.checkStatus()) {
                    FeatureStatus.AVAILABLE -> {
                        sink.onLog("ML Kit: model available")
                        true
                    }
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                        sink.onLog("ML Kit: downloading recognition model (one-time)…")
                        var failed = false
                        var lastProgressLog = 0L
                        rec.download().collect { ds ->
                            when (ds) {
                                is DownloadStatus.DownloadFailed -> {
                                    failed = true
                                    sink.onLog("ML Kit: model download FAILED: $ds")
                                }
                                is DownloadStatus.DownloadProgress -> {
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressLog > 3000) {
                                        lastProgressLog = now
                                        sink.onLog("ML Kit: downloading…")
                                    }
                                }
                                else -> sink.onLog("ML Kit: ${ds.javaClass.simpleName}")
                            }
                        }
                        !failed
                    }
                    FeatureStatus.UNAVAILABLE -> {
                        sink.onLog("ML Kit: speech recognition is UNAVAILABLE on this device — captions cannot run here")
                        false
                    }
                    else -> {
                        sink.onLog("ML Kit: unknown feature status $status")
                        false
                    }
                }
            } catch (t: Throwable) {
                sink.onLog("ML Kit: status check failed: $t")
                false
            }
        }
        if (!ready) return

        wantRunning = true
        startSession()
    }

    /** Opens a fresh pipe and recognition flow. Called again if a session ends early. */
    private fun startSession() {
        val rec = recognizer ?: return
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            sink.onLog("ML Kit: pipe creation failed: $e")
            return
        }
        writeStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        sessionCount++
        sink.onLog("ML Kit: recognition session #$sessionCount started")
        if (sessionCount == 1) sink.onReady()

        val request = SpeechRecognizerRequest.Builder().apply {
            audioSource = AudioSource.fromPfd(pipe[0])
        }.build()

        scope.launch {
            try {
                rec.startRecognition(request).collect { r ->
                    when (r) {
                        is SpeechRecognizerResponse.PartialTextResponse -> sink.onPartial(r.text)
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            if (r.text.isNotBlank()) sink.onFinal(r.text.trim())
                            sink.onPartial("")
                        }
                        is SpeechRecognizerResponse.ErrorResponse ->
                            sink.onLog("ML Kit: error ${r.e.errorCode}: ${r.e.message}")
                        is SpeechRecognizerResponse.CompletedResponse ->
                            sink.onLog("ML Kit: session #$sessionCount completed")
                        else -> sink.onLog("ML Kit: ${r.javaClass.simpleName}")
                    }
                }
            } catch (t: Throwable) {
                if (wantRunning) sink.onLog("ML Kit: recognition flow error: $t")
            }

            // The flow ended. If we still want captions, splice in a new session —
            // this is the seam the HANDOFF worried about; the log makes it countable.
            if (wantRunning) {
                closeWriteQuietly()
                val now = System.currentTimeMillis()
                restartTimes.addLast(now)
                while (restartTimes.isNotEmpty() && now - restartTimes.first() > 60_000) {
                    restartTimes.removeFirst()
                }
                if (restartTimes.size >= 6) {
                    sink.onLog("ML Kit: sessions are dying repeatedly (${restartTimes.size} in 60 s) — giving up; stop and start captions again")
                    wantRunning = false
                    return@launch
                }
                delay(300)
                if (wantRunning) startSession()
            }
        }
    }

    override fun feed(samples: ShortArray, n: Int) {
        val ws = writeStream ?: return
        if (byteBuf.size < n * 2) byteBuf = ByteArray(n * 2)
        var j = 0
        for (i in 0 until n) {
            val v = samples[i].toInt()
            byteBuf[j++] = (v and 0xFF).toByte()
            byteBuf[j++] = ((v shr 8) and 0xFF).toByte()
        }
        try {
            ws.write(byteBuf, 0, n * 2)
        } catch (_: IOException) {
            // Pipe closed mid-session-cycle; the restart path replaces writeStream.
        }
    }

    private fun closeWriteQuietly() {
        try {
            writeStream?.close()
        } catch (_: IOException) {
        }
        writeStream = null
    }

    override fun stop() {
        wantRunning = false
        closeWriteQuietly()
        val rec = recognizer ?: return
        recognizer = null
        try {
            runBlocking { withTimeoutOrNull(2000) { rec.stopRecognition() } }
        } catch (t: Throwable) {
            sink.onLog("ML Kit: stopRecognition: $t")
        }
        try {
            rec.close()
        } catch (_: Throwable) {
        }
        scope.cancel()
    }
}
