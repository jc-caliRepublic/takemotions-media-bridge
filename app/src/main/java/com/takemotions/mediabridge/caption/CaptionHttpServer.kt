package com.takemotions.mediabridge.caption

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Serves the live caption feed on http://127.0.0.1:8767 — the contract the
 * glasses-side companion (apps/caption-lab, later the NowPlaying G2 app) polls.
 * Separate port and server from the media feed on :8766, which is untouched.
 *
 *   GET /caption
 *       -> {"app":"caption-bridge","running":bool,
 *           "status":"starting|ok|silent|stopped",
 *           "engine":..,"language":"en","translation":false,
 *           "silentForSec":n,"seq":n,"partial":"…",
 *           "lines":[{"id":n,"text":..,"ja":null,"t":ms}, …]}   // last 10, oldest first
 *   GET /health
 *       -> {"ok":true,"caption":true,"running":bool}
 *
 * Semantics for clients:
 *  - connection refused = the bridge isn't capturing; treat exactly like status "stopped"
 *  - "seq" bumps on every content change (new line, partial edit); an unchanged seq
 *    means there is nothing new to render
 *  - "ja" is always null and "translation" always false: this app transcribes English
 *    only, and translation belongs to the companion. Both fields (and the "app" and
 *    "language" values) are kept verbatim from the Caption Bridge spike so a client
 *    written against that contract — caption-lab v0.1.4 — runs unmodified here.
 *
 * Loopback only (never reachable off-device). CORS "*" + Cache-Control: no-store so
 * the Even WebView companion can fetch it — same conventions as the media feed.
 */
class CaptionHttpServer : NanoHTTPD("127.0.0.1", PORT) {

    companion object {
        const val PORT = 8767
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
        }
        return when (session.uri) {
            "/caption" -> {
                CaptureService.notePolled()
                cors(
                    newFixedLengthResponse(
                        Response.Status.OK, "application/json",
                        CaptureService.captionJson().toString()
                    )
                )
            }

            "/health" -> cors(
                newFixedLengthResponse(
                    Response.Status.OK, "application/json",
                    JSONObject()
                        .put("ok", true)
                        .put("caption", true)
                        .put("running", CaptureService.isRunning)
                        .toString()
                )
            )

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
