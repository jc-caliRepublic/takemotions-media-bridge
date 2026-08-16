package com.takemotions.mediabridge

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.takemotions.mediabridge.caption.CaptureService

/**
 * The single Media Bridge screen: the media switch with its Notification Access grant
 * and now-playing readout, the caption switch with its live readout, and the
 * plain-language privacy statement. Deliberately one screen.
 *
 * The two features are independent — either can run alone — and both are switches, but
 * they promise different things. The media switch is a remembered setting (it survives
 * reboots, see BootReceiver). The caption switch reflects the live session only: Android
 * grants capture one session at a time, from a visible activity, so turning it on always
 * prompts and nothing can turn it back on by itself. Same shape as the system VPN toggle,
 * and for the same reason.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mediaSwitch: SwitchCompat
    private lateinit var accessText: TextView
    private lateinit var nowPlayingText: TextView

    private lateinit var captionSection: View
    private lateinit var captionSwitch: SwitchCompat
    private lateinit var captionStatusText: TextView
    private lateinit var captionPreviewText: TextView

    private var syncing = false
    private var syncingCaption = false

    /**
     * When the caption switch was flipped on, while the permission dialogs are up and the
     * service is starting. During that gap the switch is on but nothing is running yet,
     * and the UI must not read that as "stopped" and flip it back. 0 = not pending;
     * it also times out (CAPTION_PENDING_MS) so a failed start can't strand the switch on.
     */
    private var captionPendingSince = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            // Captions change by the second; the media readout does not.
            uiHandler.postDelayed(this, if (CaptureService.isRunning) 400 else 1500)
        }
    }

    private val postNotifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Captions need RECORD_AUDIO before the projection consent — Android routes the
    // captured playback mix through AudioRecord, so it insists on the mic permission
    // even though no microphone is ever opened.
    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchProjectionConsent()
            } else {
                // Nothing started, so the switch must go back down rather than sit on.
                setCaptionSwitch(false)
                captionStatusText.text =
                    "Permission denied. Android needs it to hand over the playback sound — " +
                        "the microphone itself is never used."
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                startCaptureService(res.resultCode, data)
            } else {
                setCaptionSwitch(false)
                captionStatusText.text = "Capture permission declined — captions not started."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pinned light to match the theme. The default (auto) follows the system into
        // dark mode and would draw white status-bar icons over this light screen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContentView(R.layout.activity_main)
        applyInsets(R.id.main)

        findViewById<TextView>(R.id.title).text = versionedTitle("Media Bridge")
        mediaSwitch = findViewById(R.id.mediaSwitch)
        accessText = findViewById(R.id.accessText)
        nowPlayingText = findViewById(R.id.nowPlayingText)

        mediaSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            Prefs.setMediaEnabled(this, checked)
            if (checked) {
                ensurePostNotifPermission()
                if (!hasNotificationAccess()) openNotificationAccessSettings()
            }
            syncService()
            updateUi()
        }
        findViewById<Button>(R.id.grantAccessBtn).setOnClickListener { openNotificationAccessSettings() }
        findViewById<Button>(R.id.appSettingsBtn).setOnClickListener { openAppSettings() }

        captionSection = findViewById(R.id.captionSection)
        captionSwitch = findViewById(R.id.captionSwitch)
        captionStatusText = findViewById(R.id.captionStatusText)
        captionPreviewText = findViewById(R.id.captionPreviewText)

        val captionsPossible = CaptureService.isSupported()
        captionSection.visibility = if (captionsPossible) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.captionPrivacyText).visibility =
            if (captionsPossible) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.captionUnsupported).visibility =
            if (captionsPossible) View.GONE else View.VISIBLE

        captionSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingCaption) return@setOnCheckedChangeListener
            if (checked) {
                if (!CaptureService.isRunning) {
                    captionPendingSince = SystemClock.elapsedRealtime()
                    captionStatusText.text = "Waiting for permission…"
                    ensurePostNotifPermission()
                    ensureAudioPermissionThenStart()
                }
            } else {
                captionPendingSince = 0L
                if (CaptureService.isRunning) {
                    startService(
                        Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Second line of defence behind BootReceiver: if the switch says on but nothing
        // is serving (killed by the system, a restart the receiver missed), opening the
        // app puts it back rather than leaving a switch that lies.
        if (Prefs.mediaEnabled(this) && !BridgeService.isRunning) syncService()
        uiHandler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refresh)
    }

    private fun updateUi() {
        val access = hasNotificationAccess()
        syncing = true
        mediaSwitch.isChecked = Prefs.mediaEnabled(this)
        syncing = false

        accessText.text =
            if (access) "Notification access: granted"
            else "Notification access: not granted — tap below"
        findViewById<Button>(R.id.grantAccessBtn).visibility =
            if (access) View.GONE else View.VISIBLE

        nowPlayingText.text = when {
            !Prefs.mediaEnabled(this) -> "Bridge off"
            !access -> "Grant notification access to read media"
            else -> {
                val j = MediaHub.currentJson(this)
                val state = j.optString("state", "none")
                if (state == "none") {
                    "Nothing playing"
                } else {
                    val app = j.optString("app")
                    val title = j.optString("title")
                    val artist = j.optString("artist")
                    val live = if (j.optBoolean("live")) "  (live)" else ""
                    "[$state] $app$live\n${title.ifBlank { "(no title)" }}" +
                        if (artist.isNotBlank()) " — $artist" else ""
                }
            }
        }

        if (captionSection.visibility == View.VISIBLE) updateCaptionUi()
    }

    // ---- captions ---------------------------------------------------------------

    private fun ensureAudioPermissionThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchProjectionConsent()
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchProjectionConsent() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        // Android 14+ consent otherwise defaults to single-app sharing, which adds a
        // "next → pick the app" step. Only the audio mix is taken (no video), so ask
        // for whole-display capture up front: the dialog becomes a single Start tap.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mgr.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val i = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, i)
    }

    /** Move the switch without the listener treating it as a tap. */
    private fun setCaptionSwitch(checked: Boolean) {
        syncingCaption = true
        captionSwitch.isChecked = checked
        syncingCaption = false
        if (!checked) captionPendingSince = 0L
    }

    private fun awaitingCaptionConsent(): Boolean =
        captionPendingSince != 0L &&
            SystemClock.elapsedRealtime() - captionPendingSince < CAPTION_PENDING_MS

    private fun updateCaptionUi() {
        val running = CaptureService.isRunning
        if (running) captionPendingSince = 0L

        // The service is the truth: it also stops from its own notification, and it never
        // comes back on its own, so the switch follows it — except during the start-up
        // gap, where the launcher callbacks own the switch instead.
        if (captionSwitch.isChecked != running && (running || !awaitingCaptionConsent())) {
            setCaptionSwitch(running)
        }

        if (!running) {
            if (!awaitingCaptionConsent()) {
                captionStatusText.text = "Off — start something playing, then switch this on."
            }
            captionPreviewText.visibility = View.GONE
            return
        }

        val now = System.currentTimeMillis()
        val elapsed = (now - CaptureService.startedAtMs) / 1000
        val silentFor =
            if (CaptureService.lastLoudMs == 0L) elapsed
            else (now - CaptureService.lastLoudMs) / 1000

        captionStatusText.text = buildString {
            append("Running %d:%02d".format(elapsed / 60, elapsed % 60))
            append("   ${CaptureService.finalCount.get()} lines")
            append("   level ${CaptureService.levelDb.toInt()} dB")
            append("\nServing 127.0.0.1:8767 ")
            val poll = CaptureService.lastPollMs
            append(
                if (poll == 0L) "(the glasses app hasn't connected yet)"
                else "(read ${(now - poll) / 1000}s ago)"
            )
            if (silentFor >= 8) {
                append("\n⚠ No sound captured for ${silentFor}s — is anything playing? ")
                append("(a few apps block capture; Spotify and YouTube allow it)")
            }
        }

        val lines = CaptureService.transcriptSnapshot().lines().takeLast(4)
        val partial = CaptureService.partialText
        captionPreviewText.text = when {
            lines.any { it.isNotBlank() } ->
                lines.joinToString("\n") + if (partial.isNotBlank()) "\n$partial…" else ""
            partial.isNotBlank() -> "$partial…"
            else -> "Listening…"
        }
        captionPreviewText.visibility = View.VISIBLE
    }

    // ---- helpers ----------------------------------------------------------------

    private fun applyInsets(rootViewId: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(rootViewId)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val me = packageName
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == me }
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun ensurePostNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Start the bridge service when the feature is on, stop it when off. */
    private fun syncService() {
        val i = Intent(this, BridgeService::class.java)
        if (Prefs.mediaEnabled(this)) {
            i.action = BridgeService.ACTION_START
            ContextCompat.startForegroundService(this, i)
        } else {
            i.action = BridgeService.ACTION_STOP
            startService(i)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** "Title  vX.Y.Z" with the version dimmed and smaller. */
    private fun versionedTitle(base: String): CharSequence {
        val v = appVersion()
        val suffix = if (v.isEmpty()) "" else "  v$v"
        val full = "$base$suffix"
        return SpannableString(full).apply {
            if (suffix.isNotEmpty()) {
                val s = full.length - suffix.length
                setSpan(RelativeSizeSpan(0.6f), s, full.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(0xFF7B7B7B.toInt()), s, full.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private companion object {
        /** How long the caption switch may stay on waiting for consent + service start. */
        const val CAPTION_PENDING_MS = 8_000L
    }
}
