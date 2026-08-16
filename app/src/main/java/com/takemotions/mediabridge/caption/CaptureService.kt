package com.takemotions.mediabridge.caption

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.takemotions.mediabridge.MainActivity
import com.takemotions.mediabridge.R
import com.takemotions.mediabridge.caption.engine.CaptionEngine
import com.takemotions.mediabridge.caption.engine.EngineSink
import com.takemotions.mediabridge.caption.engine.MlKitEngine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10

/**
 * Foreground service (mediaProjection type) that owns the projection token, captures
 * the device playback mix via AudioPlaybackCapture, and pumps 16 kHz mono PCM into
 * [MlKitEngine], publishing English caption lines on :8767.
 *
 * Entirely separate from [com.takemotions.mediabridge.BridgeService] (:8766, media):
 * either can run without the other, and neither touches the other's state. The
 * activity observes progress by polling the companion-object state — the same
 * poll-don't-bind pattern the media side uses.
 *
 * Unlike the media bridge this cannot be a set-and-forget switch: MediaProjection
 * consent is per session and must be granted from a foreground activity, so captions
 * are always started by hand and never resume on their own.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.takemotions.mediabridge.CAPTION_START"
        const val ACTION_STOP = "com.takemotions.mediabridge.CAPTION_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /**
         * Captions need Android 12+: AudioPlaybackCapture itself is 10+, but the
         * recognizer ([MlKitEngine.MIN_API]) is the real floor. The app as a whole
         * still supports Android 8+ for the media bridge — the caption UI is simply
         * hidden below this.
         */
        const val MIN_API = MlKitEngine.MIN_API

        private const val CHANNEL_ID = "mediabridge_caption"

        /** Must differ from BridgeService's notification id (1) — both can be up at once. */
        private const val NOTIF_ID = 2
        private const val TAG = "CaptureService"

        /** 16 kHz mono is what the engine consumes. */
        private const val TARGET_RATE = 16000

        /** Cap the engine input queue at ~30 s; beyond that we drop oldest audio. */
        private const val MAX_QUEUE_CHUNKS = 300

        /** True when this device can run captions at all. */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_API

        // ---- state polled by MainActivity --------------------------------------
        @Volatile var isRunning = false; private set
        @Volatile var engineLabel = ""; private set
        @Volatile var formatLabel = ""; private set
        @Volatile var levelDb = -100.0; private set
        @Volatile var lagSeconds = 0.0; private set
        @Volatile var startedAtMs = 0L; private set
        @Volatile var lastLoudMs = 0L; private set
        @Volatile var partialText = ""; private set
        @Volatile var engineReady = false; private set
        @Volatile var lastPollMs = 0L; private set
        val finalCount = AtomicInteger(0)
        val droppedChunks = AtomicInteger(0)
        val revision = AtomicInteger(0)

        /** Bumps only on caption content changes (line / partial / run state) —
         *  exposed as "seq" on /caption so clients can skip unchanged polls. */
        val contentSeq = AtomicInteger(0)
        private val lineIdSeq = AtomicInteger(0)

        private val finalLines = ArrayDeque<CaptionLine>()
        private val logLines = ArrayDeque<String>()
        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun transcriptSnapshot(): String =
            synchronized(finalLines) { finalLines.joinToString("\n") { it.text } }

        fun logSnapshot(): String = synchronized(logLines) { logLines.joinToString("\n") }

        private fun appendFinal(line: CaptionLine) {
            synchronized(finalLines) {
                finalLines.addLast(line)
                while (finalLines.size > 400) finalLines.removeFirst()
            }
            finalCount.incrementAndGet()
            contentSeq.incrementAndGet()
            revision.incrementAndGet()
        }

        private fun log(msg: String) {
            Log.i(TAG, msg)
            synchronized(logLines) {
                logLines.addLast("${timeFmt.format(Date())} $msg")
                while (logLines.size > 200) logLines.removeFirst()
            }
            revision.incrementAndGet()
        }

        /** Wipe per-run text so a fresh run starts clean. */
        private fun resetRunState() {
            synchronized(finalLines) { finalLines.clear() }
            synchronized(logLines) { logLines.clear() }
            finalCount.set(0)
            droppedChunks.set(0)
            lineIdSeq.set(0)
            partialText = ""
            levelDb = -100.0
            lagSeconds = 0.0
            engineReady = false
            lastPollMs = 0L
            contentSeq.incrementAndGet()
            revision.incrementAndGet()
        }

        /** Called by [CaptionHttpServer] on every /caption GET. */
        fun notePolled() {
            lastPollMs = System.currentTimeMillis()
        }

        /**
         * The /caption payload. Called from the HTTP server thread.
         *
         * "language"/"translation"/"ja" are frozen at en/false/null: this app only
         * transcribes English and the companion owns translation. They stay in the
         * payload so clients written against the Caption Bridge contract still parse.
         */
        fun captionJson(): JSONObject {
            val now = System.currentTimeMillis()
            val silentFor = when {
                !isRunning -> 0L
                lastLoudMs == 0L -> (now - startedAtMs) / 1000
                else -> (now - lastLoudMs) / 1000
            }
            val status = when {
                !isRunning -> "stopped"
                !engineReady -> "starting"
                silentFor >= 8 -> "silent"
                else -> "ok"
            }
            val arr = JSONArray()
            val tail = synchronized(finalLines) { finalLines.takeLast(10) }
            for (l in tail) {
                arr.put(
                    JSONObject()
                        .put("id", l.id)
                        .put("text", l.text)
                        .put("ja", JSONObject.NULL)
                        .put("t", l.tMs)
                )
            }
            return JSONObject()
                .put("app", "caption-bridge")
                .put("running", isRunning)
                .put("status", status)
                .put("engine", engineLabel)
                .put("language", "en")
                .put("translation", false)
                .put("silentForSec", silentFor)
                .put("seq", contentSeq.get())
                .put("partial", partialText)
                .put("lines", arr)
        }
    }

    private val stopping = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var engine: CaptionEngine? = null
    private var server: CaptionHttpServer? = null
    private var captureThread: Thread? = null
    private var engineThread: Thread? = null
    private val queue = LinkedBlockingQueue<ShortArray>(MAX_QUEUE_CHUNKS)

    /** Set when the 16 kHz mono AudioRecord path fails and we fall back to 48 kHz stereo. */
    private var needsDownmix = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            log("projection stopped by system (revoked or screen-cast ended)")
            stopEverything()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            log("stop requested")
            stopEverything()
            return START_NOT_STICKY
        }
        if (isRunning) {
            log("start ignored — already running")
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "captions require Android 12+; this device is API ${Build.VERSION.SDK_INT}")
            stopSelf()
            return START_NOT_STICKY
        }
        return startCapture(intent)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startCapture(intent: Intent?): Int {
        stopping.set(false)
        resetRunState()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            log("missing MediaProjection consent data — cannot start")
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        // Android 14+: the mediaProjection-typed foreground service must be up before
        // the projection token is used.
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            log("getMediaProjection failed: $e")
            null
        }
        if (proj == null) {
            stopEverything()
            return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val rec = buildAudioRecord(proj)
        if (rec == null) {
            log("AudioRecord init failed — cannot capture playback audio")
            stopEverything()
            return START_NOT_STICKY
        }
        record = rec

        isRunning = true
        running.set(true)
        startedAtMs = System.currentTimeMillis()
        lastLoudMs = 0L
        queue.clear()

        // English continuous speech never pauses long enough for the engine to finalize,
        // so raw finals arrive as paragraphs — the chunker recuts the stream into
        // caption-sized lines.
        val chunker = Chunker()

        fun commitLine(text: String) {
            appendFinal(CaptionLine(lineIdSeq.incrementAndGet(), text, System.currentTimeMillis()))
        }

        fun publishPartial(p: String) {
            if (p != partialText) {
                partialText = p
                contentSeq.incrementAndGet()
                revision.incrementAndGet()
            }
        }

        val sink = EngineSink(
            onPartial = { p ->
                val cut = chunker.onPartial(p)
                for (c in cut.lines) commitLine(c)
                publishPartial(cut.tail)
            },
            onFinal = { text ->
                for (c in chunker.onFinal(text)) commitLine(c)
                publishPartial("")
            },
            onLog = { log(it) },
            onReady = {
                engineReady = true
                contentSeq.incrementAndGet()
            },
        )
        val eng = MlKitEngine(sink, Locale.US)
        engine = eng
        engineLabel = eng.label
        log("engine: ${eng.label}")
        log("capture: $formatLabel")
        log("chunker: recutting captions every ~8 words")

        server = try {
            CaptionHttpServer().also { it.start() }
        } catch (e: Exception) {
            log("caption server failed to bind :8767 (${e.message ?: e}) — another bridge instance running?")
            null
        }
        if (server != null) log("serving http://127.0.0.1:${CaptionHttpServer.PORT}/caption")

        startEngineThread(eng)
        startCaptureThread(rec)
        return START_STICKY
    }

    // ---- capture ---------------------------------------------------------------

    /** 16 kHz mono first; some devices only expose the mix at 48 kHz stereo. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildAudioRecord(proj: MediaProjection): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        fun tryBuild(rate: Int, channelMask: Int): AudioRecord? = try {
            val minBuf = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) null else {
                val r = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                    r.release()
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord build failed at $rate Hz: $e")
            null
        }

        tryBuild(TARGET_RATE, AudioFormat.CHANNEL_IN_MONO)?.let {
            needsDownmix = false
            formatLabel = "16 kHz mono (native)"
            return it
        }
        tryBuild(48000, AudioFormat.CHANNEL_IN_STEREO)?.let {
            needsDownmix = true
            formatLabel = "48 kHz stereo → 16 kHz mono (downmix)"
            return it
        }
        return null
    }

    private fun startCaptureThread(rec: AudioRecord) {
        captureThread = thread(name = "mb-capture") {
            // 100 ms per read.
            val chunk = if (needsDownmix) ShortArray(48000 / 10 * 2) else ShortArray(TARGET_RATE / 10)
            try {
                rec.startRecording()
            } catch (e: Exception) {
                log("startRecording failed: $e")
                stopEverything()
                return@thread
            }
            var readErrors = 0
            while (running.get()) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    if (++readErrors <= 3) log("audio read returned $n")
                    SystemClock.sleep(50)
                    continue
                }
                readErrors = 0
                val mono = if (needsDownmix) downmixTo16k(chunk, n) else chunk.copyOf(n)
                trackLevel(mono)
                if (!queue.offer(mono)) {
                    queue.poll()
                    queue.offer(mono)
                    val d = droppedChunks.incrementAndGet()
                    if (d == 1 || d % 100 == 0) log("engine can't keep up — dropped $d chunks (0.1 s each)")
                }
            }
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
        }
    }

    /** Interleaved 48 kHz stereo → 16 kHz mono: average L/R, then boxcar-average groups of 3. */
    private fun downmixTo16k(buf: ShortArray, n: Int): ShortArray {
        val frames = n / 2
        val outLen = frames / 3
        val out = ShortArray(outLen)
        var i = 0
        for (o in 0 until outLen) {
            var acc = 0
            for (f in 0 until 3) {
                acc += (buf[i].toInt() + buf[i + 1].toInt()) / 2
                i += 2
            }
            out[o] = (acc / 3).toShort()
        }
        return out
    }

    private fun trackLevel(samples: ShortArray) {
        var peak = 0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        levelDb = if (peak == 0) -100.0 else 20.0 * log10(peak / 32768.0)
        if (levelDb > -50.0) lastLoudMs = System.currentTimeMillis()
    }

    // ---- engine ----------------------------------------------------------------

    private fun startEngineThread(eng: CaptionEngine) {
        engineThread = thread(name = "mb-engine") {
            try {
                eng.start()
            } catch (t: Throwable) {
                log("engine start failed: $t")
                stopEverything()
                return@thread
            }
            var feedErrors = 0
            while (running.get() || queue.isNotEmpty()) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                lagSeconds = queue.size * 0.1
                try {
                    eng.feed(chunk, chunk.size)
                } catch (t: Throwable) {
                    if (++feedErrors <= 3 || feedErrors % 100 == 0) log("engine feed error #$feedErrors: $t")
                }
            }
            try {
                eng.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "engine stop failed", t)
            }
        }
    }

    // ---- teardown --------------------------------------------------------------

    private fun stopEverything() {
        if (!stopping.compareAndSet(false, true)) return
        running.set(false)
        isRunning = false
        contentSeq.incrementAndGet()
        revision.incrementAndGet()

        // Free :8767 promptly (same synchronous pattern as the media server).
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null

        thread(name = "mb-caption-teardown") {
            captureThread?.join(2000)
            engineThread?.join(5000)
            try {
                projection?.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            try {
                projection?.stop()
            } catch (_: Exception) {
            }
            projection = null
            record = null
            engine = null
            log("stopped")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ---- notification ----------------------------------------------------------

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Captions", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Live captions from playback audio on 127.0.0.1:${CaptionHttpServer.PORT}"
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        // Request codes 2/3 — the media notification already owns 0/1.
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Captions running")
            .setContentText("127.0.0.1:${CaptionHttpServer.PORT} · transcribing playback on-device")
            .setSmallIcon(R.drawable.ic_stat_caption)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }
}
