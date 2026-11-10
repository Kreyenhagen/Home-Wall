# Progress

## Milestones

| Milestone | Status |
|---|---|
| M1 Skeleton | **Built.** `assembleDebug testDebugUnitTest` passes (33 tests). The AVD check is on hold (GPU memory is busy); see the checklist below. |
| M2 Wall view | **Built.** `assembleDebug testDebugUnitTest` passes (90 tests). Not yet run on the AVD or tablet; see the M2 checklist below. |
| M3 Routes | Not started |
| M4 ESP32 firmware | Not started |
| M5 LEDs in the app | Not started |
| M6 Wall Setup mapping | Not started |
| M7 Polish & safety | Not started |

## Toolchain

| | Version |
|---|---|
| Package / applicationId | `com.kreyenhagen.homewall` |
| Android Studio | 2026.2.2 (installed in `C:\Program Files\Android\Android Studio1`) |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin, so no `kotlin-android` plugin) |
| Gradle | 9.9.0 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 (Material 3 1.4.0) |
| Navigation Compose | 2.10.2 |
| Lifecycle | 2.11.0 |
| DataStore Preferences | 1.3.0 |
| kotlinx.serialization JSON | 1.12.0 |
| ExifInterface | 1.4.2 |
| kotlinx-coroutines-test | 1.11.0 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 33 |
| JDK (Gradle) | Android Studio's bundled JBR 25 |

Icons are Material icon paths stored as vector drawables in `res/drawable/ic_*.xml`, so there's no icon library.

## Deviations from NORTH_STAR.md

None. One ordering note: `FakeLedController` (M1) must "encode with the real encoder", so the
protocol encoder (`led/LedProtocol.kt`), its §11.5 golden-vector and chunking tests, and the
palette slots and brightness scaling (`led/Palette.kt`) were written in M1 instead of M5.
`LedSync`, `DriveLeds` and the BLE controller are still M5.

Protocol detail (within v1, no change to the contract): a SET_PALETTE that doesn't fit in one
packet is sent as several complete SET_PALETTE messages. That only happens at the default MTU of
23 (5 slots need 22 bytes; the limit is 20). Each message stands alone, so the firmware needs
nothing special.

**M2: perspective matrix (deviation from §7.1, awaiting your OK).** §7.1 says to build each
panel's matrix with `Matrix.setPolyToPoly`, and §14 asks for a unit test that each panel's corners
map exactly onto its band. `android.graphics.Matrix` is only a stub in plain JVM unit tests, so
that test can't run against `setPolyToPoly` without Robolectric (a new library). Instead,
`data/photo/Homography.kt` solves the same 4-point perspective transform in plain Kotlin, and
`WallRectifier` loads it into an `android.graphics.Matrix` with `setValues`. The drawing is the same
as §7.1 describes; only the matrix's source differs, and that's what the test covers.

M2 notes (consistent with the doc, listed so they're not a surprise):
- The placeholder layout is generated in memory and only written to `wall_layout.json` once
  something changes it (e.g. resizing a panel). That way, if a later app update bundles a real
  `assets/wall_layout.json`, it still gets picked up.
- `wall_layout.json` is written with one hold per line, so it's easy to edit by hand and to diff.
  Unknown keys are reported as a problem rather than silently dropped on the next save.
- If the saved layout has problems, the app lists them (Wall Setup → Align, plus a banner on Route
  List) and never writes over that file.
- The placeholder banner on Route List (§6.4) went in now, since the layout exists from M2.
- The Align step's result view shows hold details when you tap a hold, and the wall coordinates
  when you tap an empty spot. That's how to check hit testing at every zoom level before the
  editor exists (M3).

## To do by hand

- [x] ~~Update Android Studio to 2026.2.2 or newer.~~ Done; the project syncs.
- [ ] Run the M1 checklist below on the `Noah_s_Tablet` AVD once the GPU is free.
- [ ] Run the M2 checklist: the placeholder parts on the AVD, the real-photo parts on the tablet.
- [ ] Decide on the M2 perspective-matrix deviation above.

## M1 manual test checklist (AVD)

Run the `app` configuration on `Noah_s_Tablet`.

- [ ] The app opens on **Route List**: dark theme, title "Home Wall", text "No routes yet. Tap Create to make one.", and a **Create** button.
- [ ] Top bar: grey Bluetooth icon, Lights off (disabled), Sort, Filter (disabled), Settings gear.
- [ ] **Sort** opens a menu with Grade high → low, Grade low → high, Newest (checked), Oldest. Pick Oldest, force-stop the app, reopen it: Oldest is still checked.
- [ ] **Create** opens "New route" (Route Editor stub). **Save** opens Route Display. **Back** from there goes straight to Route List, not to the editor.
- [ ] Create → Save again, then **Edit** (pencil) on Route Display opens "Edit route". **Save** returns to Route Display. Edit → ⋮ → **Delete route** returns to Route List.
- [ ] **Settings** gear → Settings. **Wall Setup** opens a screen with Photo / Align / Map LEDs / Review tabs that switch.
- [ ] In Settings, turn on **Simulated LED controller**. Under Diagnostics, Connection shows "Simulated", and log lines appear: "Simulated controller connected (MTU 517)", then
  `TX 01 00 01 00 80 00 02 00 00 80 03 4B 00 80 04 80 80 80 05 20 20 20` (the palette at 50 % brightness).
- [ ] Back on Route List, the Bluetooth icon is cyan on a tinted tile (Simulated). Tapping it shows a snackbar.
- [ ] Turn Simulated off: the icon goes grey and the log says "Simulated controller disconnected".
- [ ] Toggles and sliders (markers, LED preview, ring size, keep screen on, brightness) keep their values after force-stop and reopen. Brightness only changes the palette sent on the next connect for now; sending it live comes with M5.
- [ ] Rotate to landscape on every screen: nothing is cut off, and Settings stays a centered column.

## M2 manual test checklist

**On the AVD (no photo needed):**

- [ ] Route List shows the banner "Placeholder layout. Map your wall in Settings → Wall Setup."
- [ ] Settings → Wall Setup opens on **Photo** (no photo yet), with Take photo, Choose photo and the five tips.
- [ ] **Align** shows a plain brown-grey wall with a dashed fold line and 154 markers on an even 11 × 14 grid: two rows on the kicker below the fold, twelve above. Ids run serpentine: 0–10 left to right along the bottom row, 11–21 right to left above it.
- [ ] Tap a marker: a white ring appears and the panel shows e.g. "Hold 37 · LED 37 · x 56.0, y 27.0 in (Main)". Tap between markers: "No hold here · x …, y … in".
- [ ] Pinch to zoom (on the emulator, hold Ctrl and drag), up to 5×. Two-finger drag pans, and the wall can't be dragged out of view. Taps still hit the right marker when zoomed. Double-tap returns to 1×.
- [ ] Change **Main** from 99 to 90 and tap **Save sizes**: the markers above the fold squeeze together and the kicker rows don't move. Force-stop and reopen: the new size is kept. Put it back to 99.
- [ ] **Choose photo** with any image (the emulator's camera roll works). It opens Align with six labeled handles: Floor L/R, Fold L/R, Top L/R. Dragging one shows a magnifier and moves the lines.
- [ ] Drag the Fold L handle to the right of Fold R and tap **Save**: a message says the Kicker panel's points don't outline a panel, and nothing is saved.
- [ ] Rotate to landscape on Photo and Align: the wall is on the left and the controls on the right.

**On the tablet (real photo):**

- [ ] **Take photo** opens the system camera. After the shot, the app goes to Align with the photo upright.
- [ ] Drag the handles onto the floor line, the fold and the top corners, then **Save**. After "Straightening the photo…", the result is a flat, front-on wall: both panels look rectangular and the fold is a straight horizontal line where the dashed line sits.
- [ ] **Adjust points** goes back to the handles at their saved positions; **Cancel** returns to the result unchanged.
- [ ] Force-stop and reopen: Wall Setup → Align shows the straightened wall straight away.
