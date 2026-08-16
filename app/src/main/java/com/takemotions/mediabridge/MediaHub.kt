package com.takemotions.mediabridge

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import org.json.JSONObject

/**
 * Reads the currently-playing media and sends transport / volume / seek commands to the
 * active player.
 *
 * Uses [MediaSessionManager] / [MediaController], gated by Notification Access (the
 * [MediaAccessService] component is the listener handle MediaSessionManager requires).
 * That listener is empty — it reads no notifications. Everything here is queried on
 * demand per HTTP request; no persistent listeners are kept.
 */
object MediaHub {

    /**
     * Outcome of a command. [position] is the resulting playback position in ms after a
     * seek, so the glasses can redraw the progress bar immediately instead of waiting for
     * the next poll; it is -1 for every other command.
     */
    data class CommandResult(val ok: Boolean, val position: Long = -1L)

    private fun manager(ctx: Context): MediaSessionManager? =
        ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private fun listenerComponent(ctx: Context) =
        ComponentName(ctx, MediaAccessService::class.java)

    private fun activeSessions(ctx: Context): List<MediaController> = try {
        manager(ctx)?.getActiveSessions(listenerComponent(ctx)) ?: emptyList()
    } catch (_: SecurityException) {
        emptyList() // Notification Access not granted
    } catch (_: Exception) {
        emptyList()
    }

    // Distinguish a ring-initiated pause (keep — so the glasses can resume it) from a
    // session an app simply left PAUSED behind when it was closed / swiped away (drop —
    // so a finished app's track stops showing). Both look identical from the playback
    // state alone (PAUSED + active), so we remember which session WE paused from the ring.
    // Anything PAUSED that we did not pause is treated as a stale remnant.
    @Volatile private var ringPausedPkg: String? = null
    @Volatile private var ringPausedAt: Long = 0L
    private const val PAUSE_KEEP_MS = 5 * 60 * 1000L // safety expiry for a kept ring-pause

    private fun markRingPause(c: MediaController) {
        ringPausedPkg = c.packageName
        ringPausedAt = SystemClock.elapsedRealtime()
    }

    private fun clearRingPause() {
        ringPausedPkg = null
    }

    private fun isOurRingPause(c: MediaController): Boolean {
        val pkg = ringPausedPkg ?: return false
        return c.packageName == pkg &&
            SystemClock.elapsedRealtime() - ringPausedAt < PAUSE_KEEP_MS
    }

    /**
     * A session is "live" (worth reporting) while playing / buffering. A PAUSED session
     * is kept ONLY if the ring is what paused it, so play/pause from the glasses can
     * resume it; a session an app left PAUSED on its own (closed / swiped away) is
     * dropped immediately so the closed app's track stops showing. Stopped / none /
     * error are always dropped.
     */
    private fun isLive(c: MediaController): Boolean = when (c.playbackState?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING -> true
        PlaybackState.STATE_PAUSED -> isOurRingPause(c)
        else -> false // STOPPED / NONE / ERROR / CONNECTING / null
    }

    /** The most relevant live controller: a playing one if any, else the first live one. */
    private fun pick(ctx: Context): MediaController? {
        val sessions = activeSessions(ctx).filter { isLive(it) }
        if (sessions.isEmpty()) return null
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.first()
    }

    /** Whether this player accepts a seek at all (live streams and some apps do not). */
    private fun canSeek(c: MediaController): Boolean =
        ((c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L

    /**
     * Where playback actually is now, in ms, or -1 if unknown.
     *
     * [PlaybackState.getPosition] is a snapshot taken at `lastPositionUpdateTime` and
     * players refresh it lazily, so it can trail reality by a second or more. Projecting
     * it forward here is what lets a relative seek ("30 seconds on") start from where
     * playback really is rather than from where it was last reported.
     */
    private fun livePositionMs(c: MediaController): Long {
        val ps = c.playbackState ?: return -1L
        val base = ps.position
        if (base < 0L) return -1L
        if (ps.state != PlaybackState.STATE_PLAYING) return base
        val updatedAt = ps.lastPositionUpdateTime
        if (updatedAt <= 0L) return base
        val drift = SystemClock.elapsedRealtime() - updatedAt
        if (drift <= 0L) return base
        return base + (drift * ps.playbackSpeed.toDouble()).toLong()
    }

    // Where we last told a player to jump to, and when. A player does not publish its new
    // position instantly — measured on YouTube, a second seek sent right after the first
    // still read the *pre-seek* position — so two quick "30 seconds on" taps would land in
    // the same place instead of adding up. Inside a short window we therefore count from
    // what we asked for rather than from what the player is still reporting.
    @Volatile private var seekPkg: String? = null
    @Volatile private var seekTargetMs = 0L
    @Volatile private var seekAt = 0L
    private const val SEEK_TRUST_MS = 4000L

    private fun rememberSeek(c: MediaController, targetMs: Long) {
        seekPkg = c.packageName
        seekTargetMs = targetMs
        seekAt = SystemClock.elapsedRealtime()
    }

    /** Anything that moves playback its own way invalidates the remembered target. */
    private fun forgetSeek() {
        seekPkg = null
    }

    /**
     * The position a relative seek should count from: our own pending target while it is
     * fresh, otherwise what the player reports. Once the player has caught up the two
     * agree, so the window only ever covers the gap.
     */
    private fun seekBaseMs(c: MediaController): Long {
        val live = livePositionMs(c)
        if (seekPkg != c.packageName) return live
        val since = SystemClock.elapsedRealtime() - seekAt
        if (since < 0L || since > SEEK_TRUST_MS) return live
        val ps = c.playbackState
        val speed =
            if (ps?.state == PlaybackState.STATE_PLAYING) ps.playbackSpeed.toDouble() else 0.0
        return seekTargetMs + (since * speed).toLong()
    }

    /**
     * The volume the ring's volup / voldown actually move, as a step index.
     *
     * A session reports its own volume — for a cast / remote player that is the remote
     * device's, which is exactly what [MediaController.adjustVolume] changes — so it is
     * read from there when the session offers it, and from the music stream otherwise.
     * The scale is device-dependent (15, 25, 30 steps…), hence level + max rather than a
     * percentage.
     */
    private fun putVolume(ctx: Context, json: JSONObject, c: MediaController?) {
        val info = try {
            c?.playbackInfo
        } catch (_: Exception) {
            null
        }
        if (info != null && info.maxVolume > 0) {
            json.put("volume", info.currentVolume).put("volumeMax", info.maxVolume)
            return
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        json.put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("volumeMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    /** Now-playing as JSON. `state` = none when nothing is active. */
    fun currentJson(ctx: Context): JSONObject {
        val c = pick(ctx)
        if (c == null) {
            // Volume is still reported: it belongs to the phone, not to a track, and the
            // glasses can offer it while nothing is playing.
            return JSONObject()
                .put("playing", false).put("state", "none").put("live", false)
                .also { putVolume(ctx, it, null) }
        }
        val md = c.metadata
        val ps = c.playbackState
        val state = when (ps?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_BUFFERING -> "buffering"
            else -> "unknown"
        }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        // No fixed length (live stream / radio / endless) comes through as duration 0 or -1.
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        return JSONObject()
            .put("playing", ps?.state == PlaybackState.STATE_PLAYING)
            .put("state", state)
            .put("package", c.packageName ?: "")
            .put("app", appLabel(ctx, c.packageName))
            .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            .put("artist", artist)
            .put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            .put("position", ps?.position ?: -1L)
            .put("duration", duration)
            .put("live", duration <= 0L)
            .put("canSeek", canSeek(c))
            .also { putVolume(ctx, it, c) }
    }

    /**
     * Apply a transport / volume / seek command to the active player.
     *
     * [params] carries the query string; only `seek` reads it (`pos` / `by`).
     */
    fun command(
        ctx: Context,
        action: String,
        params: Map<String, String> = emptyMap(),
    ): CommandResult {
        val c = pick(ctx) ?: return CommandResult(false)
        val tc = c.transportControls
        when (action) {
            "play" -> { tc.play(); clearRingPause() }
            "pause" -> { tc.pause(); markRingPause(c) }
            "playpause" ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    tc.pause(); markRingPause(c)
                } else {
                    tc.play(); clearRingPause()
                }
            // Track changes move playback themselves, so a pending seek target is void.
            "next" -> { tc.skipToNext(); clearRingPause(); forgetSeek() }
            "prev", "previous" -> { tc.skipToPrevious(); clearRingPause(); forgetSeek() }
            "volup", "volume_up" -> c.adjustVolume(AudioManager.ADJUST_RAISE, 0)
            "voldown", "volume_down" -> c.adjustVolume(AudioManager.ADJUST_LOWER, 0)
            "seek" -> return seek(c, params)
            else -> return CommandResult(false)
        }
        return CommandResult(true)
    }

    /**
     * `?pos=<ms>` jumps to an absolute position; `?by=<ms>` (signed) moves relative to
     * where playback is right now. The glasses use the relative form — measuring from
     * here rather than from the companion's last poll is what keeps repeated taps
     * accurate — and the target is clamped to the track.
     *
     * Deliberately does not touch the ring-pause marker: seeking is not play or pause,
     * so a track paused from the ring stays reported and resumable.
     */
    private fun seek(c: MediaController, params: Map<String, String>): CommandResult {
        if (!canSeek(c)) return CommandResult(false)

        val absolute = params["pos"]?.toLongOrNull()
        val relative = params["by"]?.toLongOrNull()
        val target = when {
            absolute != null -> absolute
            relative != null -> {
                val now = seekBaseMs(c)
                if (now < 0L) return CommandResult(false)
                now + relative
            }
            else -> return CommandResult(false)
        }

        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        val clamped =
            if (duration > 0L) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)
        c.transportControls.seekTo(clamped)
        rememberSeek(c, clamped)
        return CommandResult(true, clamped)
    }

    private fun appLabel(ctx: Context, pkg: String?): String {
        pkg ?: return ""
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }
}
