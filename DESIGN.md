# DESIGN.md

Visual design reference for **RT SOP 알림**. This documents where the current UI came from and what
changed when it was ported from mockup to real Android code — read this before changing colors,
typography, icons, or the calendar/settings layout so changes stay consistent with the approved
direction instead of drifting back toward the old stock-Material look.

## Source mockup

The approved visual direction was drafted as a Claude Design canvas (multi-artboard mockup) before
any of it was implemented:

**https://claude.ai/artifact/LvZMMWgmS88ptwFrjpKy7j?sk=z_M_puE6zjBzQIXdkXm45Q**

Six artboards, all 390×844 mobile frames: 홈 (Home, light), 경보상세 (Alert detail — active/중대,
light), 경보상세 · 빈 상태 (empty state), 경보상세 · 다크 (dark theme, 주의 severity), 오늘 이벤트
(Event log, light), 설정 (Settings, light). It's a static visual exploration (no working
interactivity) built with the `design` skill — not itself part of this repo, and not guaranteed to
stay editable forever, which is why the decisions below are captured here rather than only living on
that page.

## Design tokens actually implemented

- **Brand red** `#D22234` (`brand_red`) — used sparingly (primary buttons, active nav tab, brand
  accents), not as a dominant background color. This was a deliberate shift from the original
  template's `Theme.MaterialComponents.DayNight.DarkActionBar` (solid red action bar, white text) —
  the theme parent is now plain `Theme.MaterialComponents.DayNight` with a custom `actionBarStyle`
  (`Widget.Rtauto.ActionBar`, background `@color/surface`) so the top bar reads as neutral/enterprise
  rather than a colored banner.
- **Severity color system** — each level gets a light-background/dark-foreground pair, not a solid
  saturated fill:
  - 중대 (critical): `critical_bg` / `critical_fg`
  - 주의 (caution): `caution_bg` / `caution_fg`
  - 일반 (normal): `normal_bg` / `normal_fg`
  - `level_critical`/`level_caution`/`level_normal` are kept as aliases of the `_fg` colors for
    backward-compat call sites (`MainActivity.levelColorRes()`); `levelBgRes()` returns the matching
    `_bg` token. All defined in `values/colors.xml` with dark-mode overrides in
    `values-night/colors.xml`. The alert-detail severity banner and the event-list severity chips both
    use the `_bg`/`_fg` pair (light tinted badge), not solid-fill-white-text — that was the single
    biggest visual change from the pre-redesign version.
  - The "확인 (경보 종료)" button is intentionally **always brand red**, regardless of the event's
    severity — it's the one thing the admin needs to find instantly regardless of which color the
    banner above it is.
- **Neutral tokens**: `screen_bg` (window background), `surface` (card/row background), `outline`
  (1dp borders/dividers), `text_secondary`, `text_muted`. Cards stay flat — `MaterialCardView` with
  `cardElevation="0dp"` + 1dp `outline` stroke, no shadow — this predates the redesign and the mockup
  kept it deliberately (matches "카드는 그림자 없이 1px 테두리" from the mockup brief).
- **Typography**: IBM Plex Sans KR (`res/font/ibm_plex_sans_kr.xml`, weights 400/500/600/700) is the
  app-wide default font (`android:fontFamily`/`fontFamily` on the theme). IBM Plex Mono
  (`res/font/ibm_plex_mono.xml`) is used for the FCM token display and timestamps — anything
  numeric/code-like reads better monospaced. These are bundled `.ttf` files (downloaded from Google
  Fonts' GitHub source, not a downloadable-font API dependency), which is most of why the debug APK
  grew from ~7.6MB to ~11.8MB — if that size matters later, dropping to 2 weights per family instead
  of 4/3 is the easy lever.
- **Icons**: every icon in the app is a hand-authored stroke-style vector drawable (1.8dp stroke,
  round caps/joins, transparent fill) on a 24×24 viewport — nothing uses the stock filled Material
  icons anymore. This includes icons that predate the redesign (`ic_key`, `ic_copy`, `ic_delete`,
  `ic_notification`, `ic_chevron_right`, `ic_check_circle`) which were redrawn from filled to stroke
  specifically so nothing looks visually inconsistent next to the new ones (`ic_mic`, `ic_vibration`,
  `ic_play`, `ic_chevron_left`, `ic_nav_home`/`ic_nav_alert`/`ic_nav_list` outline versions). If you
  add a new icon, match this style — don't drop in a filled Material icon next to these.
  - One dead end worth knowing about: an early pass tried reusing the launcher icon's bell glyph
    (`ic_launcher_foreground.xml`'s bell group) as the app-bar brand mark. At 20dp it reads as a
    warning-triangle-with-exclamation, not a bell (that glyph has a white "!" baked into it, by
    original launcher-icon design) — the app bar now uses `ic_notification` (a plain rounded bell
    outline) tinted brand red instead. Don't reuse the launcher bell at small sizes for the same
    reason.

## Screens: mockup vs. what's actually implemented

| Screen | Match to mockup |
|---|---|
| 홈 | Close match — device-registration card with a "발급됨" status badge, test-alert card with a play-icon button and volume slider. |
| 경보상세 (active + empty + dark) | Close match — tinted severity banner, brand-red acknowledge button with a plain checkmark icon, empty state uses a circled checkmark in a light-blue backdrop (`bg_icon_circle`). |
| 오늘 이벤트 | Calendar fully rebuilt as a custom grid (see below) matching the mockup, not the native `CalendarView` the mockup screen implied replacing. |
| 설정 | Rebuilt from three bordered `MaterialCardView` sections into the mockup's flat list: section labels (경보/데이터/정보) directly on `screen_bg`, edge-to-edge `surface`-colored rows with `SettingsRow`/`SettingsRowDivider`/`SettingsRowLabel` styles (`values/styles.xml`), destructive delete row in `critical_fg`, no button chrome for "오늘 이벤트 전체 삭제" / "알림 설정 바로가기" (they're tappable rows now, not `MaterialButton`s). |
| App bar (all screens) | Two-line custom `actionBar` view (`view_actionbar_title.xml`): small bell icon + "RT AUTOMATION" eyebrow + current tab name, set via `MainActivity.setTabTitle()`. Replaces the old single-line `supportActionBar?.title`. |
| Bottom nav | **Deviates from the mockup on purpose**: the mockup showed text labels under each icon, but with the app bar now also showing the tab name (앞줄 참고), having it twice read as duplicated/overlapping — feedback was to drop the bottom-nav labels entirely (`app:labelVisibilityMode="unlabeled"`), icon-only, active tab still shown in brand red. |

## Custom calendar grid (오늘 이벤트 탭)

Replaces the stock `CalendarView` entirely — `MainActivity.renderCalendarGrid()` builds the month
grid programmatically (weekday header + week rows of day cells), same "build views in code" pattern
`buildEventRow()` already used for the event list.

- `displayedMonth` (which month is currently drawn) is tracked **separately** from `selectedDateMillis`
  (which day is selected) — paging months with the arrows doesn't change the selection until a day is
  actually tapped.
- Two ways to change year/month, both kept because they solve different problems: the `‹`/`›`
  `ImageButton`s (`calendarPrevMonth`/`calendarNextMonth`) step one month; tapping the centered
  `calendarMonthLabel` opens the existing `MaterialDatePicker` (its built-in year grid lets you jump
  years instantly) — same picker/UTC-midnight-conversion logic that predates the redesign, just
  retargeted at `displayedMonth` instead of a `CalendarView`.
- Cell states: **today** = filled `bg_day_today` circle (white text); **selected-but-not-today** =
  outlined `bg_day_selected` ring (brand-red text) — the mockup only showed "today", but a real user
  browsing other days needs to see which day they're actually looking at, so this was added on top of
  the mockup.
- Event dots (`bg_dot`, backed by `EventStore.datesWithEventsInMonth(context, year, month)`) are
  **always brand red**, even on the "today" cell — an earlier version tinted the dot white to sit on
  today's red circle, but the dot is positioned in the cell's margin *outside* the circle, not inside
  it, so a white dot there was invisible against the white card background. If you touch this again,
  remember the dot never actually overlaps the day circle.
- Sunday header label is tinted `critical_fg`, Saturday `normal_fg` (common Korean calendar
  convention) — cosmetic only, doesn't affect selection logic.

## If you extend this further

Two things the mockup queued up but weren't ported (see `CLAUDE.md`'s UI shape section for current
Settings/Home structure):

- The Home device-registration card's copy button is still a full outlined `MaterialButton` ("토큰
  복사"), not the mockup's icon-only 32dp square button — kept as-is for a clearer tap target/label,
  not an oversight.
- No dark-theme screenshots were produced for 홈/오늘 이벤트/설정 during the mockup phase (only
  경보상세 got a dedicated dark artboard, per the original ask) — dark mode for those three screens is
  covered by the same `values-night/colors.xml` tokens, verified on-device, but wasn't first designed
  as a mockup.
