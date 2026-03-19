# GammaLens Omni UI Redesign

## Overview

The app frontend was redesigned from an engineering overlay to a product-ready 5-tab UI while keeping the detection pipeline unchanged.

## Screenshots (placeholders)

- **Live**: Camera preview with score card, metrics row, dual-status chips, mode switch (遮光测量 / 环境巡检).
- **Events**: List of events with time, scoreEma, suppressed badge; tap for details; Export CSV button.
- **Spectrum**: Area histogram (last 60s sliding window), bucket counts.
- **Calibration**: Device profile summary, 遮光基线校准 (TODO), 导出设备档案.
- **Settings & Debug**: Toggles (debug overlay, save logs, developer), collapsible MVP_STATUS / GL_SYNC / GL_EVT panels, Copy debug summary.

## Architecture

- **Data layer**: `StatsRepository`, `EventRepository`, `DebugLogRepository`, `HistogramRepository`, `DeviceProfile`. Single source of truth; UI subscribes via `StateFlow`.
- **UI state**: `LiveStatsUiState`, `DualStatusUiState`, `EventItem`, `HistogramUiState`.
- **Pipeline**: No algorithm changes. `FrameProcessor` exposes `onStatsSnapshot`, `onSuppressed`, `onEvtLog`; `FrameSynchronizer` exposes `onSyncLog`. MainActivity pushes to repositories at 1 Hz and on events.

## 5 Tabs

| Tab        | Purpose |
|-----------|---------|
| Live      | Real-time view, scoreEma, risk (low/medium/high), events/rate/lastAgo/cooldown, dual chips, mode switch, info (?) tooltip. |
| Events    | Event list (timestamp, scoreEma, suppressed badge). Tap for details. Export CSV to app storage + share. |
| Spectrum  | MVP histogram: area buckets (0–50, 50–100, …) in last 60s. |
| Calibration | Device profile (model, cameraId, pairWindowNs, flashThreshold, area range). Buttons: 遮光基线校准 (stub), 导出设备档案 (JSON). |
| Settings & Debug | Toggles; collapsible panels for MVP_STATUS, GL_SYNC, GL_EVT; Copy debug summary to clipboard. |

## Mode switch (presentation only)

- **遮光测量(更准)** / **环境巡检(更方便)**: UI labels and hints only; no pipeline or threshold changes.

## Data export

- **CSV**: Events list + summary (event count, columns: id, timestampMs, streamId, scoreEma, blobCount, maxArea, nonZeroCount, suppressed, suppressReason). Saved to app external files dir; share intent with FileProvider.
- **Device profile**: JSON export of `DeviceProfile` (model, cameraId, pairWindowNs, flashThreshold, area range, captureSize, fpsTarget).

## Live screen: overlap fix and layout

**Overlap bug cause:** The Live overlay (score and stats) was inside a full‑screen `ScrollView` with no top padding. The activity uses `Theme.MaterialComponents.DayNight.DarkActionBar`, and the fragment container is constrained to the full content area; on some devices the status bar (or action bar when overlaid) overlaps the top of the content, so the first card was drawn underneath the app bar.

**Fix:** Apply system window insets on the overlay container so it is never under system UI:
- In `LiveFragment.onViewCreated`, call `ViewCompat.setOnApplyWindowInsetsListener` on the overlay `ConstraintLayout`.
- Set padding from `WindowInsetsCompat.getInsets(WindowInsetsCompat.Type.systemBars())` (top/left/right/bottom).
- Call `requestApplyInsets()` so insets are dispatched. Result: the top chips row starts below the status bar, and the bottom panel stays above the navigation bar; the big score and all stats remain fully visible.

**Layout redesign:** One full‑screen `TextureView` as background; one overlay with (1) top row of status chips (single/dual, pairing, suppression, fallback, fps) and (2) one bottom card containing score, risk, four metrics, and the mode switch. No ScrollView; no multiple stacked dark cards; bottom nav is in the activity and does not cover the Live content.

## QA checklist

1. **Score not overlapped**  
   On Live tab, the large score and risk label are fully visible below the status bar / app bar on notched and non‑notched devices.

2. **Camera preview full‑screen**  
   The camera preview fills the entire Live content area behind the overlay (no large blank area).

3. **Live updates once per second**  
   Score, metrics, dual chips, and FPS chip update at ~1 Hz (no per‑frame updates).

4. **Bottom nav does not cover content**  
   The bottom panel sits above the bottom navigation bar with margin; no critical UI is hidden.

5. **Event list increments when triggers happen**  
   Cause a radiation candidate (or wait for one); open Events tab; confirm new row with time and scoreEma; suppressed events show “已抑制”.

3. **CSV export works**  
   Events tab → Export CSV → choose app or share; open file and verify header + rows.

4. **Debug summary copy works**  
   Settings & Debug → Copy debug summary → paste in notes; verify MVP_STATUS and recent GL_SYNC/GL_EVT lines.

6. **No FPS regression**  
   Compare logcat `GL_BLOB` fps before/after; should remain ~10 fps.

## Build and run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Manual QA: launch app → grant camera → use each tab; trigger an event and check Events + CSV export; open Debug and copy summary.
