# Progress

## Milestones

| Milestone | Status |
|---|---|
| M1 Skeleton | **Built.** `assembleDebug testDebugUnitTest` passes (33 tests). The AVD check is on hold (GPU memory is busy); see the checklist below. |
| M2 Wall view | **Built.** `assembleDebug testDebugUnitTest` passes (90 tests). Not yet run on the AVD or tablet; see the M2 checklist below. |
| M3 Routes | **Built.** `assembleDebug testDebugUnitTest` passes (131 tests). Not yet run on the AVD; see the M3 checklist below. |
| M4 ESP32 firmware | **Built.** `firmware/home_wall/home_wall.ino` compiles with `arduino-cli` for ESP32 Dev Module at default settings on core 3.3.12 (87% of program storage) and 2.0.17 (89%), with FastLED 3.10.9 and no warnings from the sketch. Not yet uploaded; see the M4 steps below. |
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
| Room | 2.8.5 (runtime, ktx, compiler through KSP) |
| KSP | 2.3.13 |
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

M3 notes (consistent with the doc, listed so they are not a surprise):
- Room exports its schema to `app/schemas/` (`exportSchema = true`, §6.3). Commit those files: each
  future migration is written against them. There is no destructive-migration fallback.
- Not in M3, per the milestone plan: lighting the wall (Route Display, the editor draft, Lights
  off, turning the LEDs off after a delete) is M5, and so is the Connect button. Scrolling the list
  back to the last viewed route and Keep screen on are M7.
- Landscape already works on the new screens (wall on the left, controls on the right); M7 polishes it.
- Route Display keeps showing a route I opened, or edited from there, even if the filter hides it,
  so e.g. regrading a route to Project while projects are filtered out does not make it vanish (§8.2).
- Editing a route whose holds are partly gone from the layout says so in the editor ("1 hold no
  longer exists. Saving removes it from the route."), as well as on Route Display (§6.3).
- Back from the editor with changes asks "Discard this route?" for a new route, and "Discard your
  changes?" when editing. Cancelling the Save dialog keeps what was typed for next time.

M4 notes:
- **Color order (deferred; you'll adjust it later).** The firmware uses `COLOR_ORDER RBG`, as §2 and §11.6 say.
  Your test sketch `Documents/Arduino/sketch_jun18a` uses `RGB`. The nRF Connect route test below
  shows which is right (LED 12 must be green). If it is RGB, change the `COLOR_ORDER` line in the sketch and §2/§11.6 together.
- The `#include`s sit just above the settings block instead of below it. FastLED uses `DATA_PIN`
  as a name inside its own headers, so defining it first breaks the build (FastLED's own examples
  put their defines after the include too). The settings block itself is exactly as in §11.6.
- The service UUID is in the advertising packet and the name in the scan response, set explicitly.
  The BLE library's default puts both in one packet, which doesn't fit with a 128-bit UUID and
  could drop the UUID the app scans for.
- At boot the palette holds the §9.3 colors (before brightness), so a frame sent by hand without
  a palette still shows something. The app always sends its own palette on connect.
- Serial logging uses a 1 KB transmit buffer so logging from the Bluetooth callback never stalls a write.

## To do by hand

- [x] ~~Update Android Studio to 2026.2.2 or newer.~~ Done; the project syncs.
- [ ] Run the M1 checklist below on the `Noah_s_Tablet` AVD once the GPU is free.
- [ ] Run the M2 checklist: the placeholder parts on the AVD, the real-photo parts on the tablet.
- [ ] Decide on the M2 perspective-matrix deviation above.
- [ ] Run the M3 checklist on the AVD.
- [ ] Upload the firmware and run the M4 nRF Connect steps.
- [ ] Check the strand's color order when convenient (see M4 notes).

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

## M3 manual test checklist (AVD)

Use the placeholder layout (no photo needed). Hold markers are on by default, so every hold shows its id.

- [ ] Route List with no routes: "No routes yet. Tap Create to make one."
- [ ] **Create** opens "New route": the wall with markers, and a bottom bar with Start (0/2), Middle (0), Finish (0/2), Undo and Redo (both disabled), and Save (disabled). Start is selected.
- [ ] Tap two holds: green rings, "Start (2/2)". Tap a third: nothing changes, the snackbar says "Max 2 start holds", and the device buzzes lightly. (The emulator has no vibration, so only the snackbar shows there.)
- [ ] Select Middle and tap a few holds (blue rings), then Finish and tap one (purple). Tap a ringed hold with any role selected: its ring goes away.
- [ ] Undo steps back one hold change at a time and Redo steps forward. A new tap after Undo disables Redo. Switching role isn't a step.
- [ ] Pinch-zoom (Ctrl + drag on the emulator) and pan; taps still pick the right hold. Double-tap resets.
- [ ] Rotate the screen mid-edit: the holds and the undo history are still there, and the controls move to the right.
- [ ] Back with holds picked asks "Discard this route?"; Cancel keeps editing.
- [ ] **Save** opens the dialog with the name field focused and the keyboard up; Grade shows Project. Save is disabled until there's a name. Typing stops at 40 characters. Remove the finish hold first to see "This route has no finish hold. You can still save it."
- [ ] Save goes to **Route Display**: the wall with the rings, the name, the grade and "1 / 1". Back goes to Route List, not to the editor.
- [ ] Create about six routes with different grades, including a couple of Projects. The list shows each name (long names are cut off with "…") and its grade chip.
- [ ] **Sort**: Grade high → low puts Projects last, and routes of the same grade newest first. Same for low → high. Newest and Oldest go by creation time.
- [ ] **Filter**: set V2–V6 and turn off Include projects. The chip "V2–V6 · no projects" appears under the app bar; tapping it reopens the sheet. Set a range that matches nothing: "No routes match this filter" with Reset filter.
- [ ] Tap a route: Route Display opens on it, and swiping left and right goes through the routes in the same order as the list, with "n / total" updating.
- [ ] On Route Display, **Edit** opens the route you're looking at (not necessarily the one you tapped), with its rings. Undo is disabled. Change a hold and save with a new name: you're back on Route Display on the same route, showing the changes, and the list shows the new name.
- [ ] With projects filtered out, edit a graded route and change it to Project: after saving it's still on screen in Route Display. Back on the list, it's hidden by the filter.
- [ ] Edit → ⋮ → **Delete route** asks first, then goes back to Route List, and the route is gone.
- [ ] Force-stop and reopen: routes, sort and filter are all still there.
- [ ] Settings → Show hold markers off: Route Display and the editor show only the plain wall and rings.

## M4 firmware: upload and test with nRF Connect

The sketch is `firmware/home_wall/home_wall.ino`. Upload it as its top comment says (esp32 board package, FastLED, ESP32 Dev Module, default settings).

**On the ESP32**

- [ ] Open Serial Monitor at 115200 baud and press the board's EN/RST button. You should see "Home Wall LED controller, protocol v1", then "Advertising as HomeWall (preferred MTU 517)". All LEDs are off.

**With nRF Connect for Mobile on a phone or the tablet**

To write a value: tap the up-arrow on the characteristic, choose **BYTE ARRAY**, and paste the hex without spaces. Request or Command both work.

- [ ] **Scanner:** "HomeWall" appears. Tap **Connect**. The serial log says "Connected".
- [ ] **MTU:** in nRF Connect's ⋮ menu, choose **Request MTU** and enter 517. The serial log says "MTU 517 (max packet 512 bytes)".
- [ ] **INFO:** open the service `5B1E0001-…` and read characteristic `…0003` (down-arrow). It shows `0x01-26-02-10`: protocol 1, 550 LEDs, 16 palette slots.
- [ ] **Palette (vector 1):** write `01000100FF00020000FF039600FF04FFFFFF` to `…0002`. The log says "SET_PALETTE seq=0 slots=4" and the wall doesn't change.
- [ ] **Route frame (vector 2):** write `0207030C00012800022900022C01029F0103`. The log says "FRAME seq=7 flags=03 entries=5". The wall shows LED 12 **green**, LEDs 40, 41 and 300 **blue**, and LED 415 **purple**. (LED 40 is on your test sketch's hidden list, so it may be behind the wall.)
  - **Color order check:** if LED 12 is blue and 41/300 are green, the strand isn't RBG. Tell me, and I'll change `COLOR_ORDER` in both the sketch and the doc.
- [ ] **Live palette:** write `010901FF0000` (slot 1 → red). LED 12 turns red straight away with no new frame, because a palette change re-renders the current frame.
- [ ] **All off (vector 3):** write `020803`. Every LED goes off.
- [ ] **Multi-packet frame:** write `020A01640004` (BEGIN, LED 100 white). Nothing changes yet, because it isn't committed. Then write `020B02650004` (COMMIT, LED 101 white). LEDs 100 and 101 light white together.
- [ ] **Bad input is ignored:** write `0300`. The log says "unknown opcode 03" and the wall doesn't change.
- [ ] **Disconnect:** with something lit, tap **Disconnect** in nRF Connect. The LEDs stay lit, and the log says "Disconnected", then "Advertising again". "HomeWall" shows up in the scanner again, and reconnecting works.
- [ ] **Power cycle:** unplug and replug the ESP32. It boots with every LED off and advertises again.
