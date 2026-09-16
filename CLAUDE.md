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

The UI went through a full visual redesign (see [`DESIGN.md`](./DESIGN.md) for the source mockup,
color/typography/icon decisions, and exactly what was and wasn't ported 1:1 from it) — read that
before changing colors, fonts, icons, the calendar, or the Settings layout.

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
backgrounded, which breaks the custom sound/vibration/TTS sequence. Expected `data` fields:
- `kind` — `"alert"` (new violation, the default when omitted — keeps old edge-PC senders working)
  or `"resolved"` (violation cleared). `title`, `body`, `level` only apply to `"alert"`.
- `key` — the edge PC's (rule, target) identifier, e.g. `"helmet:7"` or `"crew:None"`
  (`webcam_sop.py`'s `fcm_key()`). Correlates an `"alert"` with the later `"resolved"` for the
  same violation; empty/omitted means "can't be auto-resolved" (`AlertFcmService` just no-ops a
  `resolved` message with no key rather than guessing).
- `title` — e.g. `"2인 1조 위반"` (alert only)
- `body` — e.g. `"세정기 구역, 2인 1조 위반, 09시 10분 발생"` (alert only)
- `level` — `"중대"` / `"일반"` / `"주의"`, defaults to `"중대"` (alert only)

A `"resolved"` message never shows a new notification or plays sound — see `AlertFcmService`'s doc
comment and "해제 조건" below.

**Playback pipeline** (`AlertFcmService` → `EventStore` → `AlertPlayer`, gated by `AppSettings`):
1. `AlertFcmService.onMessageReceived` parses the data payload, records it via `EventStore.addEvent`,
   then calls `AlertPlayer.trigger`.
2. `AlertPlayer` (singleton `object`) does: show a notification, vibrate once (skipped if
   `AppSettings.isVibrationEnabled` is false), start the alarm sound, **and** — concurrently, not
   waiting for the alarm sound to finish — speak the body via TTS (skipped if
   `AppSettings.isTtsEnabled` is false), then keep re-speaking it every 10 seconds
   (`TTS_REPEAT_INTERVAL_MS`, via a `Handler(Looper.getMainLooper())`) until `stop()` is called. TTS is
   deliberately **not** sequenced after the alarm sound's completion callback — real alarm-type sounds
   are often designed to not finish on their own for a long time (confirmed on-device: `dumpsys audio`
   showed the default alarm `MediaPlayer` still `state:started` several minutes after triggering), so
   gating TTS on that completion event meant it could take minutes to ever speak, or never repeat in
   any useful timeframe. Voice repetition is checked/re-scheduled against `AppSettings.isTtsEnabled`
   on every firing (not just once at trigger time), so toggling the Settings-tab switch mid-alarm takes
   effect on the next tick. `AlertPlayer` holds the currently-playing `MediaPlayer`, `TextToSpeech`
   instance, and the repeat `Handler`/`Runnable` at object scope so a later `AlertPlayer.stop()` call
   (wired to the "확인" button in the alert-detail screen) can cancel all three immediately — this is
   the "알람 종료" action; `playAlarmSound()` also cancels any previous alert's still-pending repeat
   schedule before starting its own, so two overlapping alerts don't end up with two independent
   10-second loops both firing.
   While TTS is actually speaking, `AlertPlayer` ducks the alarm sound to 25%
   (`DUCKED_ALARM_VOLUME`, via `currentPlayer?.setVolume()` on the TTS `UtteranceProgressListener`'s
   `onStart`) and restores it to 100% (`FULL_ALARM_VOLUME`) on `onDone`/`onError` — both alarm sound
   and TTS share the same `STREAM_ALARM`, so without ducking the sound and the spoken words compete and
   the words are hard to make out. The `MediaPlayer.setVolume()` calls are wrapped in `runCatching`
   (logged as a `Log.w` on failure, never crashes) since the alarm sound may have already finished/been
   released by the time a duck/restore fires.
   `playAlarmSound()` uses `MediaPlayer.prepareAsync()` + `setOnPreparedListener`, never the blocking
   `prepare()` — the trigger path can run on the UI thread (the 테스트 경보 재생 button calls
   `AlertPlayer.trigger()` directly from its click listener), so a synchronous `prepare()` there risks
   an ANR if the audio backend is slow (reproduced on a cold emulator: a multi-second main-thread
   freeze, confirmed via `/data/anr` traces). The whole `MediaPlayer` setup is also wrapped in
   `try/catch` — an invalid/revoked alarm-sound `Uri` throwing from `setDataSource`/`prepareAsync`
   used to crash the app outright; now it just skips the sound and still runs TTS. Don't reintroduce a
   bare `prepare()` call, drop the try/catch, or re-couple TTS to the alarm sound's completion
   callback here.
3. **Alarm sound source**: `AppSettings.resolveAlarmSoundUri()` — the user's chosen sound (picked via
   the Settings tab's system ringtone picker) if set, else the device's default alarm ringtone.
4. **Volume is intentionally not a custom software gain.** Both the alarm `MediaPlayer` and the TTS
   engine are routed to `AudioManager.STREAM_ALARM` (`AudioAttributes.USAGE_ALARM` / TTS
   `KEY_PARAM_STREAM`), and `MainActivity`'s volume `Slider` drives that same system stream directly via
   `AudioManager.setStreamVolume`. This is what makes the slider adjust an already-playing alarm in
   real time — dragging it changes the stream volume the mixer is actively applying, it doesn't need a
   reference to the live player. Don't reintroduce a separate `setVolume()` software multiplier without
   removing this live-adjust property.
5. **Resolve path (해제 조건, proposal 5.6절)**: stopping an alarm has two independent entry points
   into the same `AlertPlayer.stop()` — the "확인" button (human-initiated) and
   `AlertPlayer.resolveIfMatches(context, key)` (edge-PC-initiated, called from
   `AlertFcmService` when a `kind="resolved"` message arrives). `resolveIfMatches` only calls
   `stop()` if `key` equals the currently-alarming `currentKey` (set in `trigger()`, cleared in
   `stop()`) — this stops the *right* alarm instead of whatever happens to be ringing, so a
   `resolved` for an old violation can't cut off a newer, unrelated one that's mid-playback.
   `EventStore.resolveByKey(context, key)` runs in parallel (not gated on `AlertPlayer`'s state) to
   mark the matching history entry `acknowledged = true, autoResolved = true` — the proposal is
   explicit that this clearing must come from the edge PC re-confirming normal state, never from a
   human "확인"/voice ACK, so `autoResolved` exists to keep that distinction visible in the event
   list ("자동 해제" vs "확인 완료") even though both set `acknowledged = true` under the hood (the
   alert-detail banner's `latestUnacknowledged()` query doesn't need to know which).

   `resolveIfMatches` (and the ackButton's manual-confirm handler) also call
   `AlertPlayer.promoteNextIfAny(context)` right after `stop()` — if `EventStore.latestUnacknowledged()`
   still returns something (a second violation that was already active, just buried behind the one
   that just cleared — see "multiple simultaneously-active violations" below), it re-triggers the
   full alarm (`trigger()`: sound, vibration, TTS, notification, tab switch) for it. Without this
   (2026-09-16, found in real testing: a crew violation was still open when a helmet violation
   confirmed and then auto-resolved), the card would silently swap to show the next violation with
   no sound/TTS at all, since `refreshAlertDetail()`/`refreshEventList()` only repaint the UI — they
   never call `trigger()`. Don't add a similar "if the event changed, ring" check inside
   `refreshAlertDetail()` itself — that path also runs from a brand-new alert's own `onNewAlertArrived()`
   chain, and would double-trigger the sound/TTS for it; `promoteNextIfAny` is deliberately only
   called from the two well-defined "an alarm was just stopped" call sites instead.

   `resolveIfMatches` also invokes `AlertPlayer`'s second UI callback, `uiResolvedListener` (set by
   `MainActivity.onStart()`/cleared in `onStop()`, same lifecycle as the existing `uiListener` used
   for new alerts) — **unconditionally**, regardless of whether `key` matched `currentKey`, because
   the caller (`AlertFcmService`) always calls `EventStore.resolveByKey()` first, so *something* in
   the event history may have changed even when this specific alarm didn't. `MainActivity`'s handler
   (`onAlertResolvedFromEdge()`) just calls `refreshAlertDetail()` + `refreshEventList()` — unlike
   `onNewAlertArrived()` it does **not** switch tabs, since a resolve shouldn't yank the admin off
   whatever screen they're looking at. This existed as a real gap before 2026-09-16: the alarm would
   go silent on a `resolved` message, but the alert-detail banner stayed showing the now-cleared
   violation until the user manually left and came back to the tab (which happens to call
   `refreshAlertDetail()` again) — `refreshAlertDetail()` already starts/stops the shake+vibration
   impact loop based on whether `latestUnacknowledged()` returns anything, so no separate handling
   was needed for that once the refresh itself was wired up.

**Local persistence** (SharedPreferences, all under one file `rtauto_sop_prefs`, no server-side
history in this MVP):
- `TokenStore` — this device's current FCM registration token (shown/copied on the Home tab so an
  operator can paste it into the edge PC's config; there's no automatic registration endpoint yet).
- `EventStore` — rolling JSON array (capped at `MAX_EVENTS = 500`, raised from an original 50 once
  date-based browsing was added — see below) of `AlertEvent` records (`id`, `level`, `title`, `body`,
  `acknowledged`, `autoResolved`, `key` — the last two default `false`/`null` so old stored JSON
  without them still parses via `optBoolean`/`has()` checks in `readAll()`, not `getBoolean`/
  `getString`). `latestUnacknowledged()` backs the alert-detail tab, `eventsForDate(dateMillis)` backs
  the event-list tab for whatever day is selected on its calendar (`todayEvents()` is just
  `eventsForDate(now)`), `acknowledge(id)` is called when the "확인" button stops an alarm,
  `resolveByKey(key)` is called from `AlertFcmService` on a `kind="resolved"` message (see Resolve
  path above) and sets both `acknowledged` and `autoResolved` on every matching not-yet-acknowledged
  event, `clearAll()` backs the Settings tab's "오늘 이벤트 전체 삭제".
  `datesWithEventsInMonth(context, year, month)` backs the event-dot indicators on the custom calendar
  grid (below) — it's a separate query rather than calling `eventsForDate()` per day so the whole
  month's dot layout is one pass over the stored events instead of ~30 separate reads.
- `ThemePrefs` — manual dark-mode on/off (independent of the OS setting; see Dark mode below).
- `AppSettings` — chosen alarm sound `Uri` (nullable = "use device default"), TTS on/off, vibration
  on/off.

**UI shape** (`MainActivity` + single `activity_main.xml`, no Fragments/Navigation component — four
sibling `View`s inside one `FrameLayout`, toggled by visibility from a `BottomNavigationView`). Visual
design follows the redesign documented in [`DESIGN.md`](./DESIGN.md) (severity-tinted badges instead
of solid-fill, IBM Plex Sans KR/Mono, stroke-style icons throughout, flat `MaterialCardView` panels
with a 1dp outline and no elevation shadow) rather than literally matching the proposal PDF's 그림4
mockup anymore — treat DESIGN.md as the source of truth for colors/typography/icons, and this section
as the source of truth for how each screen's logic is wired:
- **App bar** — not the default single-line title. `view_actionbar_title.xml` is set as the action
  bar's custom view (`setDisplayShowCustomEnabled(true)`); `MainActivity.setTabTitle(String)` updates
  just its `actionBarTabTitle` `TextView` (bell icon + "RT AUTOMATION" eyebrow stay static). Called
  once for the default tab in `onCreate()` and again from every `BottomNavigationView` item-selected
  branch — if you add a fifth tab, remember to call it there too or the app bar will keep showing the
  previous tab's name.
- **Bottom nav** — `app:labelVisibilityMode="unlabeled"` (icons only, no text) — deliberately dropped
  after the app bar started showing the tab name too (see DESIGN.md); don't re-enable labels without
  also revisiting that.
- **홈** (`nav_home`) — device-registration card (FCM token display/copy, "발급됨" status badge) and
  an alert-test card (테스트 경보 재생 button + the alarm-volume `Slider`, described above).
- **경보상세** (`nav_alert_detail`) — shows `EventStore.latestUnacknowledged()` in a severity-tinted
  banner (`levelBgRes()`/`levelColorRes()` pair) + card; the banner `View` is fully `GONE` (not just
  recolored) when there's no active alert, showing a circled-checkmark empty state instead. The
  "확인 (경보 종료)" button calls `AlertPlayer.stop()` + `EventStore.acknowledge()` and is always
  brand red regardless of the alert's severity (see DESIGN.md for why). The alarm can also stop on
  its own without this button — see "Resolve path" above — in which case this banner disappears the
  next time `refreshAlertDetail()` runs (`latestUnacknowledged()` no longer returns it) with no extra
  code needed here — **and does so live**, not just on the next tab switch: `AlertFcmService`'s
  `resolved` branch drives `AlertPlayer.resolveIfMatches()`, which posts to `uiResolvedListener`
  (a second callback alongside the existing `uiListener`, same `onStart`/`onStop` lifecycle)
  unconditionally — even when the key doesn't match anything currently alarming — so
  `MainActivity.onAlertResolvedFromEdge()` calls `refreshAlertDetail()` + `refreshEventList()`
  whenever a resolved message arrives while the app is foregrounded, regardless of which tab is
  showing (unlike `onNewAlertArrived()`, it never switches tabs — a resolve shouldn't yank the
  admin off whatever they're looking at). The empty state also calls
  `refreshAutoResolvedSummary()` (2026-09-16) — the answer to "auto-resolve is a safety risk if
  it's silent, but re-alerting on every resolve is the alarm-fatigue problem all over again":
  the alarm still stops silently on the edge PC's re-detection, but the empty-state screen shows
  a tappable `autoResolvedSummary` pill, `EventStore.autoResolvedCountToday()` (today's
  `autoResolved` count), reading data that's already on-device — no new push, no fatigue, but
  nothing is invisible to an admin who opens the app. Tapping it jumps to 오늘 이벤트.

  **Multiple simultaneously-active violations (2026-09-16, found in real testing — a crew
  violation was still open when a helmet violation confirmed on top of it)**: the card only ever
  shows `EventStore.latestUnacknowledged()` (one event), so a second violation confirming just
  silently replaces what's on screen — the first one isn't resolved, just no longer visible.
  `EventStore.allUnacknowledged()` returns all of them (newest first, same ordering/filter as
  `latestUnacknowledged()` so `.firstOrNull()` on it is always the same event); `refreshAlertDetail()`
  passes everything after the first into `refreshOtherActiveAlerts()`, which shows a "다른 활성
  위반 N건 ▼" toggle (`otherAlertsToggle`/`otherAlertsContainer` in the layout) below the card.
  Expanding it renders each with the same `buildEventRow()` used in 오늘 이벤트 — deliberately
  read-only here (no per-row "확인"); the only ways anything gets acknowledged are the main card's
  button (the top event) or the edge PC's own `resolved` message for that specific `key` (matches
  regardless of which event is "on top" — see Resolve path above), so an older buried violation
  still clears itself correctly once its own condition resolves. `otherAlertsExpanded` (an Activity
  field, not persisted) keeps the expand state across the repeated `refreshAlertDetail()` calls
  from `onResume`/`onAlertResolvedFromEdge`/tab reselection so it doesn't re-collapse on every
  refresh.

  **Severity-first priority (2026-09-16, explicit design intent from the proposal's
  critical/major/minor grading — implemented same day)**: `allUnacknowledged()` sorts by
  `compareByDescending { levelRank(it.level) }.thenByDescending { it.id }` (a private
  `EventStore.levelRank()`: `"중대"`=3, `"주의"`=2, else=1) instead of plain recency, and
  `latestUnacknowledged()` is now just `allUnacknowledged().firstOrNull()` — so a `"중대"` (crew/
  zone) violation always outranks a later-confirmed `"주의"` (helmet, per `FCM_LEVEL_BY_RULE` on
  the edge PC) for the main card, the "다른 활성 위반" list, and `AlertPlayer.promoteNextIfAny()`
  (which also calls `latestUnacknowledged()`, so the alarm resumes for whichever is now most
  urgent, not just whatever's newest). `readAll()`'s own stored order is untouched (still
  newest-first, insertion order) — only this filtered/derived view re-sorts, since 오늘 이벤트/
  the calendar want chronological order, not severity order. Adding a new level string needs a
  matching case in `levelRank()` here alongside `levelBgRes()`/`levelColorRes()` in `MainActivity`.

  **Notification tray stacking (2026-09-16, same finding)**: `AlertPlayer.showNotification()`
  already gave each alert its own `notificationId` (`System.currentTimeMillis().toInt()`) and never
  cancelled previous ones, so multiple notifications were already technically distinct — the
  visible problem was no grouping, so the tray/OEM skin had no reason to present them as a
  meaningful stack. Fixed by giving every alert notification `setGroup(ALERT_GROUP_KEY)` plus a
  separate group-summary notification (`SUMMARY_NOTIFICATION_ID`, `setGroupSummary(true)`) that
  `updateGroupSummary()` keeps in sync with `activeNotificationIds.size` ("확인 안 된 위반 N건") —
  posted/updated on every `showNotification()` and after `stop()` removes the current one. Also
  fixed in passing: the `PendingIntent.getActivity()` request code was hardcoded `0` for every
  alert, which under `FLAG_UPDATE_CURRENT` makes Android treat them as *the same* PendingIntent and
  overwrite its extras — harmless today (every alert's extra is the identical `open_alert_detail =
  true`) but wrong in principle, so the request code is now `notificationId`. Known gap:
  `activeNotificationIds` only shrinks via `AlertPlayer.stop()` — if the admin swipes/taps an
  individual notification away from the tray directly, this list doesn't find out and the summary
  count can drift high; fixing that needs a dismiss/delete `PendingIntent` (`BroadcastReceiver`),
  not done yet.

  While an alert is active, `MainActivity.startAlertImpactLoop()` re-triggers
  `playAlertImpact()` on the `alertDetailCard` every 4s (`ALERT_IMPACT_INTERVAL_MS`) via a
  `Handler(Looper.getMainLooper())` — a damped shake (`DangerShakeInterpolator`) + scale pulse on the
  card, plus a punchier waveform vibration (gated on `AppSettings.isVibrationEnabled`, same rule as
  `AlertPlayer.trigger()`'s initial vibration) — replacing an earlier calm gray "무한궤도" border
  animation (`AlertBorderPulseView`, removed) that was deliberately toned-down; this one is
  intentionally the opposite, by request, so it can't be casually ignored before acknowledging. Like
  the TTS repeat in `AlertPlayer`, the loop is idempotent (`startAlertImpactLoop()` no-ops if already
  running) so switching tabs doesn't restart it, and it's only stopped in `onStop()`/when there's no
  active event — it deliberately keeps running (including the vibration) even while a different
  bottom-nav tab is showing, so it can't be dismissed just by looking away.
- **오늘 이벤트** (`nav_event_list`) — the calendar is a **custom-built month grid**, not the stock
  `CalendarView` (removed entirely). `MainActivity.renderCalendarGrid()` builds weekday header + week
  rows programmatically (same "build views in code, no RecyclerView" style `buildEventRow()` already
  used), keyed off `displayedMonth` (which month is on screen) kept separate from `selectedDateMillis`
  (which day is selected) so paging months doesn't change the selection. `calendarPrevMonth`/
  `calendarNextMonth` step one month; tapping the centered `calendarMonthLabel` opens a
  `MaterialDatePicker` (its built-in year-grid lets you jump years instantly) — selecting a date there
  converts UTC-midnight (`MaterialDatePicker`'s convention) back to a local-timezone day-start before
  syncing `selectedDateMillis`/`displayedMonth` — don't skip that conversion or dates shift by one near
  timezone boundaries. Tapping a day cell calls `EventStore.eventsForDate()` and re-renders the list
  below, with the header switching between "오늘 이벤트" and "M월 d일 이벤트". Event-day dots come from
  `EventStore.datesWithEventsInMonth()` and are always brand red (see DESIGN.md — a white-on-today's-
  red-circle version was invisible). Event rows below are still `MaterialCardView` + `Chip` built in
  code (no RecyclerView — kept intentionally simple for this MVP's data volume, now up to
  `MAX_EVENTS`), with severity/status chips using the same tinted-badge style as the alert-detail
  banner. The status chip reads "미확인" / "확인 완료" / "자동 해제" (the last for `autoResolved`
  events — see "Resolve path" above); "확인 완료" and "자동 해제" share the same color/icon
  (`status_ok`/`ic_check_circle`), only the label text tells them apart.
- **설정** (`nav_settings`) — rebuilt as a flat list (section labels + edge-to-edge rows + dividers,
  see DESIGN.md), not three bordered cards. 경보음 선택 (opens
  `RingtoneManager.ACTION_RINGTONE_PICKER` via `soundPickerLauncher`, result saved through
  `AppSettings`), TTS/진동 `SwitchMaterial` rows, "오늘 이벤트 전체 삭제" and "알림 설정 바로가기" are
  now plain clickable `LinearLayout` rows (not `MaterialButton` — if you `findViewById` them, cast to
  `View`, not `Button`), app version (`BuildConfig.VERSION_NAME`).

Both the alert-detail and event-list tabs refresh in `onResume()` and after the test-alert button
fires, so switching tabs or backgrounding/foregrounding the app is the only "live update" mechanism —
there's no observer/LiveData wiring from `AlertFcmService` into the UI.

**Dark mode** is a manual toggle (sun/moon icon in the action bar, top-right — not a system-follows
toggle), backed by `ThemePrefs` and applied via `AppCompatDelegate.setDefaultNightMode()`, which
recreates the Activity. **It does not persist across a full app restart, by design (2026-09-16)** —
every admin who opens the app starts from light, and dark is an opt-in for that session only. The
reset lives in **`App.onCreate()`** (`App.kt`, registered as `android:name=".App"` in the manifest),
not in `MainActivity.onCreate()` — `MainActivity` just unconditionally applies whatever
`ThemePrefs.isDarkMode()` currently says. This split matters: an earlier version gated the reset on
`MainActivity`'s `savedInstanceState == null`, which looked right but wasn't — Android restores a
killed process's Activity from its saved instance-state Bundle (so the app "reopening after being
swiped away or reclaimed in the background" is extremely common) and passes that Bundle into
`onCreate()` as **non-null**, indistinguishable from an in-session recreate (theme toggle, rotation)
by that check alone. `Application.onCreate()` doesn't have this problem — it only runs once per
actual process start, which is the one signal that means "truly fresh," so the reset (`ThemePrefs`
back to light + `MODE_NIGHT_NO`) belongs there. Don't move it back into `MainActivity` behind a
`savedInstanceState` check. Two things exist specifically to make the in-session recreate not lose
state:
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
splash — the latter only covers the instant before `MainActivity` inflates. `introOverlay` only covers
the content area, not the action bar (a separate window-decor element drawn above the content view),
so `runIntroAnimation()` explicitly calls `supportActionBar?.hide()` at the start — without this the
redesigned action bar (bell · "RT AUTOMATION" · tab name · dark-mode icon, see DESIGN.md) visibly shows
through above the splash for its whole ~2.3s. `?.show()` is called right as the overlay's 400ms
fade-out *starts* (not in its `withEndAction`) so `ActionBar.show()`'s own built-in appear animation
runs concurrently with the overlay fading to transparent — calling it after the fade finished instead
looked like two disconnected steps (splash fully gone, then the action bar separately popped in), which
was a review comment. Verified by temporarily stretching the 1900ms hold to ~15s on an emulator build
to actually see the hidden-action-bar state — worth remembering if this needs re-checking, since the
real duration is too short to reliably catch with manual screenshots.

## Known gaps vs. the proposal (5.6 절)

Not implemented yet, in case a task asks to extend toward the full design: the proposal's 30-second
re-send is only partially covered — `AlertPlayer` now repeats the **TTS voice** every 10 seconds until
acknowledged (see Architecture above), but the notification, alarm sound, and vibration all still fire
just once, not on the same repeating schedule. Also missing: full-screen forced alarm over the lock
screen, the safety-zone marker/anchor-point logic (5.7 절, entirely edge-PC-side, not part of this
app), and the edge PC's own FastAPI send service (only the throwaway `tools/send_test_alert.py`
stand-in exists so far, plus `webcam_sop.py --fcm-token` in the edge-PC repo for the real detection
loop).

**Resolved (2026-09-15)**: auto-clear when the edge PC reports normal state restored — see "Resolve
path" under Architecture. `kind="resolved"` FCM messages (not yet exercised against a real running
`webcam_sop.py`, only compiled and reasoned through — verify end-to-end before relying on it).
