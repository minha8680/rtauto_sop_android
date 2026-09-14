# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android app (Kotlin) that receives SOP-violation alerts pushed from an edge PC via Firebase Cloud
Messaging and turns each one into an alarm on the admin's phone: notification + vibration + alarm
sound + Korean TTS readout. It is the "관리자 휴대폰 알림" piece of a larger system described in
`장비_구매_기획안_1안_최종본_안전구역설정방식추가.pdf` (equipment/architecture proposal for a
camera-based 2-person-rule / PPE / safety-zone compliance system; the edge PC does detection and
judgment, this app is only the notification receiver). This is a 1차 MVP: single-shot alert playback,
no 30-second resend loop, no full-screen lock alarm, no video clip playback — those are documented in
the proposal but not yet implemented here.

Current package: `com.example.rtauto_sop` (still the template package name, not yet renamed).

## Build / install / run

There is no CLI test suite exercised in this project yet (only the stock JUnit/Espresso template
tests under `app/src/test` and `app/src/androidTest`). Common commands:

```powershell
# JAVA_HOME must point at a JDK 17+ (Android Studio's bundled JBR works)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat test                        # unit tests (app/src/test)
.\gradlew.bat connectedAndroidTest         # instrumented tests (needs a device/emulator)
```

Install/run on a device already connected via `adb` (USB or Wi-Fi — see "Testing without an edge PC"
below for pairing over Wi-Fi):

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.rtauto_sop/.MainActivity
```

Building requires `app/google-services.json` for the `rtauto-sop` Firebase project (committed to the
repo — it's a client identifier restricted by package name + signing cert, not a secret). Without it,
`:app:processDebugGoogleServices` fails.

## Testing without an edge PC

`tools/send_test_alert.py` sends a real FCM data-only message directly from a PC, standing in for the
edge PC's send logic (same data-only shape, so `AlertFcmService.onMessageReceived` fires exactly as it
would in production, regardless of whether the app is foregrounded).

Requires `tools/service-account.json` (Firebase Console → Project settings → Service accounts → Generate
new private key — **never commit this file**; it's already in `.gitignore`). The device's FCM token
must be passed via the `DEVICE_TOKEN` env var (never hardcode a real device token in this script —
it was accidentally committed once and the git history had to be rebuilt to remove it):

```powershell
$env:DEVICE_TOKEN = "<token copied from the app's home screen>"
python tools/send_test_alert.py
```

## Architecture

**Message contract (edge PC → app):** FCM message must use the `data` payload only, never `notification`
— a `notification` payload would let the OS handle display and skip `onMessageReceived` while
backgrounded, which breaks the custom sound/vibration/TTS sequence. Expected `data` fields: `title`,
`body`, optional `level` (`"중대"` / `"일반"` / `"주의"`, defaults to `"중대"`).

**Playback pipeline** (`AlertFcmService` → `EventStore` → `AlertPlayer`):
1. `AlertFcmService.onMessageReceived` parses the data payload, records it via `EventStore.addEvent`,
   then calls `AlertPlayer.trigger`.
2. `AlertPlayer` (singleton `object`) does, in order: show a notification, vibrate, play the alarm
   sound, then speak the body via TTS. It holds the currently-playing `MediaPlayer` and `TextToSpeech`
   instances at object scope so a later `AlertPlayer.stop()` call (wired to the "확인" button in the
   alert-detail screen) can interrupt playback immediately — this is the "알람 종료" action.
3. **Volume is intentionally not a custom software gain.** Both the alarm `MediaPlayer` and the TTS
   engine are routed to `AudioManager.STREAM_ALARM` (`AudioAttributes.USAGE_ALARM` / TTS
   `KEY_PARAM_STREAM`), and `MainActivity`'s volume slider drives that same system stream directly via
   `AudioManager.setStreamVolume`. This is what makes the slider adjust an already-playing alarm in
   real time — dragging it changes the stream volume the mixer is actively applying, it doesn't need a
   reference to the live player. Don't reintroduce a separate `setVolume()` software multiplier without
   removing this live-adjust property.

**Local persistence** (SharedPreferences, no server-side history in this MVP):
- `TokenStore` — this device's current FCM registration token (shown/copied on the Home tab so an
  operator can paste it into the edge PC's config; there's no automatic registration endpoint yet).
- `EventStore` — rolling JSON array (capped at 50) of `AlertEvent` records. `latestUnacknowledged()`
  backs the alert-detail tab, `todayEvents()` (filtered by local midnight) backs the event-list tab,
  `acknowledge(id)` is called when the "확인" button stops an alarm.

**UI shape** (`MainActivity` + single `activity_main.xml`, no Fragments/Navigation component — three
sibling `View`s inside one `FrameLayout`, toggled by visibility from a `BottomNavigationView`, matching
the proposal's 그림4 mockup in section 5.5 of the PDF):
- **홈** (`nav_home`) — FCM token display/copy, "테스트 경보 재생" (fires `AlertPlayer.trigger` + logs
  an `EventStore` entry, exactly like a real push), the live volume slider.
- **경보상세** (`nav_alert_detail`) — shows `EventStore.latestUnacknowledged()`; empty state if none.
  The "확인 (경보 종료)" button calls `AlertPlayer.stop()` + `EventStore.acknowledge()`.
- **오늘 이벤트** (`nav_event_list`) — `EventStore.todayEvents()` rendered as plain `LinearLayout` rows
  built in code (no RecyclerView dependency — kept intentionally simple for this MVP's data volume).

Both the alert-detail and event-list tabs refresh in `onResume()` and after the test-alert button fires,
so switching tabs or backgrounding/foregrounding the app is the only "live update" mechanism — there's
no observer/LiveData wiring from `AlertFcmService` into the UI.

## Known gaps vs. the proposal (5.6 절)

Not implemented yet, in case a task asks to extend toward the full design: 30-second re-send until
acknowledged, full-screen forced alarm over the lock screen, auto-clear when the edge PC reports the
2-person rule restored (currently only the local "확인" button clears an alert), the safety-zone
marker/anchor-point logic (5.7 절, entirely edge-PC-side, not part of this app), and the edge PC's own
FastAPI send service (only the throwaway `tools/send_test_alert.py` stand-in exists so far).
