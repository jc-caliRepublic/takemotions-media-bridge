# Media Bridge

A small Android helper app that makes phone-side data available to TakeMotions glasses apps
running inside Even Hub (which run in a WebView and can't reach this data directly).

It serves two things, each on its own local port, and does **nothing else**:

| Feed | Port | What it is |
|---|---|---|
| **Media** | `127.0.0.1:8766` | What's playing now + transport / volume / seek commands |
| **Captions** | `127.0.0.1:8767` | Live English transcription of the phone's playback audio (Android 12+) |

> A TakeMotions glasses app (e.g. NowPlaying) reads this bridge to display the current
> track on the lenses and to play / pause / skip / change volume from the R1 ring.

Each feed has its own switch. Turn on only what you need — the media bridge works with the
caption switch off, and vice versa.

## Media

- Reads the active media session on demand via Android's `MediaSessionManager` /
  `MediaController` — app name, title, artist, album, playback state, position, duration,
  a `live` flag for live streams / radio (duration ≤ 0), and the current volume level.
- Relays transport, volume and seek commands back to the player.
- A foreground service runs a tiny [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)
  server **bound to 127.0.0.1 only** (never reachable off-device), so the glasses app can
  reach it even with the screen off.

### Why it asks for "Notification access"

Android only lets an app list active media sessions if it owns an **enabled notification
listener**. So Media Bridge registers one — but that listener is **empty**: its
`onNotificationPosted` / `onNotificationRemoved` do nothing. The app **never reads, stores,
or transmits your notifications or messages.** The permission is purely the key Android
requires to see media playback. (Source: `MediaAccessService.kt`.)

## Captions

Captures the audio the phone is playing (**not** the microphone) and transcribes it to
English text on the device, so a glasses app can show it on the lenses. Translation is
**not** done here — the app serves English lines and the glasses companion translates them
if it wants to.

- Audio comes from Android's `AudioPlaybackCapture`, scoped to the screen-capture consent
  you grant. Speech recognition is Google's on-device **ML Kit GenAI Speech** (alpha).
- Nothing is uploaded and nothing is written to storage: the audio goes straight into the
  recognizer in memory, and only the resulting text is served on loopback.
- **Android 12+ only.** On older devices the caption card says so and the media bridge
  keeps working normally.
- Some apps refuse to be captured (Android lets an app opt out of playback capture); those
  produce silence.

### Two things to know about the caption switch

- **Android asks for screen-capture consent every time**, and only an app in the
  foreground may ask. So captions can never start by themselves — you start them from
  this screen.
- Because of that, the caption switch means **"running right now"**, not a remembered
  setting: after a reboot the media switch comes back on, the caption switch does not.
  (Same shape as Android's own VPN toggle.)

### Why it lists the microphone permission

Android routes captured playback audio through `AudioRecord`, and `AudioRecord` requires
`RECORD_AUDIO` — even when the source is the playback mix. **No microphone is ever
opened.** The permission is requested the first time you turn captions on.

## Endpoint contract

```
GET http://127.0.0.1:8766/media
  -> { "enabled": true,
       "playing": true, "state": "playing",          // playing|paused|stopped|buffering|none
       "package": "com.spotify.music", "app": "Spotify",
       "title": "Song", "artist": "Artist", "album": "Album",
       "position": 12345, "duration": 210000,         // ms; duration<=0 => live
       "live": false,
       "canSeek": true,                               // false for live streams / players that refuse
       "volume": 7, "volumeMax": 15 }                 // step index; the scale is device-dependent
  -> { "enabled": false }                             // when the bridge switch is off

GET http://127.0.0.1:8766/media/<action>              // play|pause|playpause|next|prev|volup|voldown|seek
  -> { "ok": true, "action": "next" }

GET http://127.0.0.1:8766/media/seek?by=30000         // signed ms, relative to the live position
GET http://127.0.0.1:8766/media/seek?pos=120000       // absolute ms
  -> { "ok": true, "action": "seek", "position": 42345 }   // where playback ended up

GET http://127.0.0.1:8766/health
  -> { "ok": true, "media": true,
       "caption": true, "captionRunning": false,      // can this build do captions / are they running
       "version": "2.0.0" }

GET http://127.0.0.1:8767/caption                     // only while captions are running
  -> { "app": "caption-bridge", "running": true,
       "status": "ok",                                // starting|ok|silent|stopped
       "engine": "…", "language": "en", "translation": false,
       "silentForSec": 0, "seq": 128, "partial": "…",
       "lines": [ { "id": 12, "text": "…", "ja": null, "t": 1723... } ] }   // last 10, oldest first

GET http://127.0.0.1:8767/health
  -> { "ok": true, "caption": true, "running": true }
```

CORS `*` + `Cache-Control: no-store` on every response, so an Even Hub WebView companion
can `fetch()` it with no extra setup.

**Compatibility.** Everything v1 served is unchanged; `canSeek`, `volume`, `volumeMax`,
`caption`, `captionRunning`, `version` and the `seek` action are additions. A client
written against v1 keeps working as-is, and a newer one can feature-detect by looking for
a key it needs (no `volumeMax` in `/media` ⇒ an older bridge that can't report the level
or seek). On `:8767`, connection refused means captions aren't running — treat it exactly
like `status: "stopped"`.

Stale media sessions are dropped: only playing / buffering sessions — plus a session you
paused **from the ring** — are reported. Many apps (Spotify, Amazon Music, …) leave a
paused-but-alive session behind when you close them; those are dropped immediately so a
closed player stops showing, while a track you paused from the ring is kept so you can
resume it.

## Setup (on the phone)

1. Install the APK (sideload).
2. Open Media Bridge, turn on **Enable media bridge**.
3. Grant **Notification access** when prompted (system setting; one time).
4. (Recommended) Set Battery to **Unrestricted** for Media Bridge so it keeps serving with
   the screen off.

For captions (optional, Android 12+):

5. Turn on **Live captions**. Allow the microphone permission when asked — it is what
   Android requires for playback capture; no mic is opened. If you miss the prompt, grant
   it from the system's app permissions screen.
6. Tap **Start now** on the screen-capture dialog. Captions stop when you turn the switch
   off, and after a reboot you start them again from here.

Each card shows a live preview so you can confirm it's working without the glasses.

## Build

Open in Android Studio and Run, or:

```
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (sign in Android Studio: Build > Generate Signed App Bundle / APK)
```

- Package: `com.takemotions.mediabridge`
- minSdk 26, targetSdk 36 (captions are gated at runtime to Android 12+, so the app still
  installs and serves media on older devices)

## Privacy

Everything stays on the device. Media info and caption text are served only over the
loopback interface (127.0.0.1) and nothing is uploaded anywhere. The notification listener
is empty and reads no notification content. Captured audio is transcribed in memory and
never written to storage.

Because it relies on notification access, this app is **not distributable via Google Play**
(Play restricts notification-listener apps for general use) — it is distributed as a
sideload.

---

Made by TakeMotions · [@r_tkbyc](https://x.com/r_tkbyc)
