package com.takemotions.mediabridge

import android.content.Context
import com.takemotions.mediabridge.caption.CaptureService
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Serves the currently-playing media on http://127.0.0.1:<port>
 *
 *   GET /media
 *       -> {"enabled":true,"playing":bool,"state":"playing|paused|stopped|buffering|none",
 *           "package":..,"app":..,"title":..,"artist":..,"album":..,
 *           "position":ms,"duration":ms,"live":bool,
 *           "canSeek":bool,"volume":n,"volumeMax":n}
 *       (or {"enabled":false} when the bridge switch is off)
 *   GET /media/<action>   action = play|pause|playpause|next|prev|volup|voldown|seek
 *       -> {"ok":bool,"action":..}
 *       seek takes ?pos=<ms> (absolute) or ?by=<ms> (signed, relative to the live
 *       position) and answers with the resulting "position"
 *   GET /health
 *       -> {"ok":true,"media":bool,"caption":bool,"captionRunning":bool,"version":".."}
 *
 * Everything v1.0.0 served is frozen: the added keys above ("canSeek", "volume",
 * "volumeMax", "caption", "captionRunning", "version") and the added "seek" action are
 * the whole difference, so a companion written against v1.0.0 keeps working untouched —
 * and a newer one can feature-detect simply by looking for a key it needs (no
 * "volumeMax" in /media => an older bridge that cannot report the level or seek).
 *
 * Captions are a separate feed on :8767 (see caption/CaptionHttpServer) — nothing in
 * this server's behaviour changes whether they run or not.
 *
 * Bound to the loopback interface only (127.0.0.1) — never reachable off-device.
 * CORS "*" + Cache-Control: no-store on every response so the Even WebView companion
 * (a glasses app's React UI) can fetch it.
 */
class BridgeHttpServer(private val appContext: Context, port: Int) :
    NanoHTTPD("127.0.0.1", port) {

    @Suppress("DEPRECATION")
    private val versionName: String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
        }

        val uri = session.uri
        return when {
            // Now-playing media (read). GET /media
            uri == "/media" -> {
                val body = if (!Prefs.mediaEnabled(appContext)) {
                    JSONObject().put("enabled", false).toString()
                } else {
                    MediaHub.currentJson(appContext).put("enabled", true).toString()
                }
                cors(newFixedLengthResponse(Response.Status.OK, "application/json", body))
            }

            // Media control. /media/<action> = play|pause|playpause|next|prev|volup|voldown|seek
            uri.startsWith("/media/") -> {
                val body = if (!Prefs.mediaEnabled(appContext)) {
                    "{\"ok\":false,\"error\":\"media disabled\"}"
                } else {
                    val action = uri.substringAfterLast('/')
                    // Query string, first value per name — only seek reads it (pos / by).
                    val params = session.parameters.mapValues { it.value.firstOrNull() ?: "" }
                    val res = MediaHub.command(appContext, action, params)
                    JSONObject().put("ok", res.ok).put("action", action)
                        .apply { if (res.position >= 0L) put("position", res.position) }
                        .toString()
                }
                cors(newFixedLengthResponse(Response.Status.OK, "application/json", body))
            }

            uri == "/health" -> {
                val body = JSONObject()
                    .put("ok", true)
                    .put("media", Prefs.mediaEnabled(appContext))
                    // Capability probe (added, never changed): tells a companion whether
                    // this build can do captions at all, and whether they are running
                    // right now. Without it an idle v2 looks exactly like v1, because
                    // :8767 only exists while capturing. Older clients ignore both keys.
                    .put("caption", CaptureService.isSupported())
                    .put("captionRunning", CaptureService.isRunning)
                    // Which build a user is on, for support ("what does the app say?")
                    // without asking them to dig through Android's settings.
                    .put("version", versionName)
                    .toString()
                cors(newFixedLengthResponse(Response.Status.OK, "application/json", body))
            }

            else -> cors(
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "application/json", "{\"error\":\"not found\"}"
                )
            )
        }
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "*")
        r.addHeader("Cache-Control", "no-store")
        return r
    }
}
