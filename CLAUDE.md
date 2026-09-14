# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android app (Kotlin), branded **"RT SOP 알림"**, that receives SOP-violation alerts pushed from an
edge PC via Firebase Cloud Messaging and turns each one into an alarm on the admin's phone:
notification + vibration + alarm sound + Korean TTS readout. It is the "관리자 휴대폰 알림" piece of
a larger system described in `장비_구매_기획안_1안_최종본_안전구역설정방식추가.pdf` (equipment/
architecture proposal for a camera-based 2-person-rule / PPE / safety-zone compliance system; the
edge PC does detection and judgment, this app is only the notification receiver — the proposal was
originally a `.docx`, later replaced in-place with the `.pdf` of the same content). This is a 1차
MVP: single-shot alert playback, no 30-second resend loop, no full-screen lock alarm, no video clip
playback — those are documented in the proposal but not yet implemented here.

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

`buildFeatures.buildConfig = true` is set in `app/build.gradle.kts` specifically so the Settings tab
can read `BuildConfig.VERSION_NAME` — don't remove it without also removing that reference.

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

### Testing on a device over Wi-Fi (no cable)

The dev device (Samsung Galaxy) is normally reached via wireless `adb` rather than USB. Pairing
(Settings → 개발자 옵션 → 무선 디버깅 → 페어링 코드로 기기 페어링) issues a **new IP:port and
6-digit code roughly every 30 seconds**, and `adb pair` must be run within that window or it fails
with `protocol fault (couldn't read status message)` — that error is not a real bug, just a stale/
expired code. Once paired, `adb connect <ip>:<port>` (the port shown on the *main* wireless-debugging
screen, different from the pairing port) keeps a persistent session; after that the device also
reconnects on its own via mDNS as `adb-<serial>._adb-tls-connect._tcp`, which is a valid `-s` target
even if the IP:port form stops working (e.g. after Wi-Fi re-association).

When scripting UI taps against this device for verification, **don't hardcode pixel coordinates** —
this app's screens have dynamic-height content above buttons (e.g. the token-fetch status text on
Home varies a lot in length between "불러오는 중" / a real token / an error), which shifts everything
below it. Re-derive coordinates each time with `adb shell uiautomator dump` and grep the target
`resource-id`'s `bounds`, rather than reusing coordinates from a previous screenshot.

## Architecture

**Message contract (edge PC → app):** FCM message must use the `data` payload only, never `notification`
— a `notification` payload would let the OS handle display and skip `onMessageReceived` while
backgrounded, which breaks the custom sound/vibration/TTS sequence. Expected `data` fields: `title`,
`body`, optional `level` (`"중대"` / `"일반"` / `"주의"`, defaults to `"중대"`).

**Playback pipeline** (`AlertFcmService` → `EventStore` → `AlertPlayer`, gated by `AppSettings`):
1. `AlertFcmService.onMessageReceived` parses the data payload, records it via `EventStore.addEvent`,
   then calls `AlertPlayer.trigger`.
2. `AlertPlayer` (singleton `object`) does, in order: show a notification, vibrate (skipped if
   `AppSettings.isVibrationEnabled` is false), play the alarm sound, then speak the body via TTS
   (skipped if `AppSettings.isTtsEnabled` is false). It holds the currently-playing `MediaPlayer` and
   `TextToSpeech` instances at object scope so a later `AlertPlayer.stop()` call (wired to the "확인"
   button in the alert-detail screen) can interrupt playback immediately — this is the "알람 종료" action.
3. **Alarm sound source**: `AppSettings.resolveAlarmSoundUri()` — the user's chosen sound (picked via
   the Settings tab's system ringtone picker) if set, else the device's default alarm ringtone.
4. **Volume is intentionally not a custom software gain.** Both the alarm `MediaPlayer` and the TTS
   engine are routed to `AudioManager.STREAM_ALARM` (`AudioAttributes.USAGE_ALARM` / TTS
   `KEY_PARAM_STREAM`), and `MainActivity`'s volume `Slider` drives that same system stream directly via
   `AudioManager.setStreamVolume`. This is what makes the slider adjust an already-playing alarm in
   real time — dragging it changes the stream volume the mixer is actively applying, it doesn't need a
   reference to the live player. Don't reintroduce a separate `setVolume()` software multiplier without
   removing this live-adjust property.

**Local persistence** (SharedPreferences, all under one file `rtauto_sop_prefs`, no server-side
history in this MVP):
- `TokenStore` — this device's current FCM registration token (shown/copied on the Home tab so an
  operator can paste it into the edge PC's config; there's no automatic registration endpoint yet).
- `EventStore` — rolling JSON array (capped at `MAX_EVENTS = 500`, raised from an original 50 once
  date-based browsing was added — see below) of `AlertEvent` records. `latestUnacknowledged()` backs
  the alert-detail tab, `eventsForDate(dateMillis)` backs the event-list tab for whatever day is
  selected on its calendar (`todayEvents()` is just `eventsForDate(now)`), `acknowledge(id)` is called
  when the "확인" button stops an alarm, `clearAll()` backs the Settings tab's "오늘 이벤트 전체 삭제".
- `ThemePrefs` — manual dark-mode on/off (independent of the OS setting; see Dark mode below).
- `AppSettings` — chosen alarm sound `Uri` (nullable = "use device default"), TTS on/off, vibration
  on/off.

**UI shape** (`MainActivity` + single `activity_main.xml`, no Fragments/Navigation component — four
sibling `View`s inside one `FrameLayout`, toggled by visibility from a `BottomNavigationView`; the
first three roughly match the proposal's 그림4 mockup in section 5.5 of the PDF, 설정 was added later
and isn't in the proposal). Visual style is card-based (`MaterialCardView` panels with a 1dp outline,
no elevation shadow) with `Chip` components for level/status badges — built to read as a normal
enterprise notification app rather than a bare functional prototype:
- **홈** (`nav_home`) — device-registration card (FCM token display/copy) and an alert-test card
  (테스트 경보 재생 button + the alarm-volume `Slider`, described above).
- **경보상세** (`nav_alert_detail`) — shows `EventStore.latestUnacknowledged()` in a colored severity
  banner + card; empty state (체크 아이콘 + 안내문구) if none. The "확인 (경보 종료)" button calls
  `AlertPlayer.stop()` + `EventStore.acknowledge()`.
- **오늘 이벤트** (`nav_event_list`) — a `CalendarView` at the top (today highlighted) drives
  `selectedDateMillis`; tapping any day calls `EventStore.eventsForDate()` for that day and re-renders
  the list below, with the header switching between "오늘 이벤트" and "M월 d일 이벤트". Above the
  `CalendarView`, a separate clickable label ("연도·월 선택") opens a `MaterialDatePicker` — this
  exists only because the stock `CalendarView`'s own "2026년 9월" header can't be intercepted or have
  a listener attached, and only pages one month at a time; `MaterialDatePicker`'s built-in year-grid
  (tap its own month/year label) is what actually lets the user jump years quickly. Selecting a date
  there converts UTC-midnight (`MaterialDatePicker`'s convention) back to a local-timezone day-start
  before syncing it into `selectedDateMillis` and the inline `CalendarView` — don't skip that
  conversion or dates shift by one near timezone boundaries. Rows are built in code as
  `MaterialCardView` + `Chip` (no RecyclerView — kept intentionally simple for this MVP's data volume,
  now up to `MAX_EVENTS`).
- **설정** (`nav_settings`) — 경보음 선택 (opens `RingtoneManager.ACTION_RINGTONE_PICKER` via
  `soundPickerLauncher`, result saved through `AppSettings`), TTS/진동 switches, "오늘 이벤트 전체
  삭제" (confirm dialog → `EventStore.clearAll()`), app version (`BuildConfig.VERSION_NAME`), and a
  shortcut to the system per-app notification settings screen.

Both the alert-detail and event-list tabs refresh in `onResume()` and after the test-alert button
fires, so switching tabs or backgrounding/foregrounding the app is the only "live update" mechanism —
there's no observer/LiveData wiring from `AlertFcmService` into the UI.

**Dark mode** is a manual toggle (sun/moon icon in the action bar, top-right — not a system-follows
toggle), backed by `ThemePrefs` and applied via `AppCompatDelegate.setDefaultNightMode()`, which
recreates the Activity. Two things exist specifically to make that recreate not lose state:
- `android:forceDarkAllowed="false"` is set on both the light and dark theme in `themes.xml` /
  `values-night/themes.xml`. Without it, some OEM skins (confirmed on Samsung OneUI's "다른 앱에도
  다크 모드 적용") auto-darken the light theme's window background on top of our own theming, when the
  *system* is in dark mode but the *app's* toggle is off — producing a black screen with otherwise
  correctly-light-themed content. Don't remove this.
- `MainActivity.onSaveInstanceState()` explicitly persists the selected bottom-nav tab id and
  `selectedDateMillis` (the event-list calendar's selected day), and `onCreate()` restores both when
  `savedInstanceState != null`. Neither is part of Android's default view-state saving (plain
  `View.visibility`, which is how tab switching is implemented here, isn't saved by the base `View`
  class), so without this the recreate-on-theme-toggle would silently reset the visible tab to Home
  and the calendar to today every time. If you add more recreate-sensitive UI state, extend this same
  pair of overrides rather than inventing a second mechanism.
- The one-time intro splash (`introOverlay` fade-in/out, see below) is likewise gated on
  `savedInstanceState == null` so toggling the theme doesn't replay it.

**Launcher icon / splash / branding**: the brand mark is the real RT Automation logo, traced from the
low-res PNG embedded in the proposal PDF (`장비_구매_기획안...pdf` page 1) via OpenCV contour
extraction into a hand-written vector path (see `ic_launcher_foreground.xml`'s comment) — brand red is
`#D22234`, sampled directly from that image, not guessed. The launcher icon composites that wordmark
above a small bell glyph (also hand-built as vector paths, not a stock icon) on a white background;
both were nudged/centered per user feedback, so if asked to adjust icon layout again, edit the
`<group android:translateX=... translateY=...>` wrapping the bell paths (and matching values used to
regenerate the legacy `mipmap-*/ic_launcher*.webp` rasters — see git history for the Python/PIL script
used, it wasn't kept in the repo). The splash (`introOverlay` in `activity_main.xml`, driven by
`runIntroAnimation()` in `MainActivity`) is a custom fade-in of that same logo + "RT AUTOMATION / Co.,
Ltd." text over ~2.3s, layered *on top of* (not instead of) the androidx `core-splashscreen` system
splash — the latter only covers the instant before `MainActivity` inflates.

## Known gaps vs. the proposal (5.6 절)

Not implemented yet, in case a task asks to extend toward the full design: 30-second re-send until
acknowledged, full-screen forced alarm over the lock screen, auto-clear when the edge PC reports the
2-person rule restored (currently only the local "확인" button clears an alert), the safety-zone
marker/anchor-point logic (5.7 절, entirely edge-PC-side, not part of this app), and the edge PC's own
FastAPI send service (only the throwaway `tools/send_test_alert.py` stand-in exists so far).
