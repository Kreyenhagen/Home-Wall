# Progress

## Milestones

| Milestone | Status |
|---|---|
| M1 Skeleton | **Built.** `assembleDebug testDebugUnitTest` passes (33 tests). The AVD check is on hold (GPU memory is busy); see the checklist below. |
| M2 Wall view | **Built.** `assembleDebug testDebugUnitTest` passes (90 tests). Not yet run on the AVD or tablet; see the M2 checklist below. |
| M3 Routes | **Built.** `assembleDebug testDebugUnitTest` passes (131 tests). Not yet run on the AVD; see the M3 checklist below. |
| M4 ESP32 firmware | **Built.** `firmware/home_wall/home_wall.ino` compiles with `arduino-cli` for ESP32 Dev Module at default settings on core 3.3.12 (87% of program storage) and 2.0.17 (89%), with FastLED 3.10.9 and no warnings from the sketch. Not yet uploaded; see the M4 steps below. |
| M5 LEDs in the app | **Built.** `assembleDebug testDebugUnitTest` passes (165 tests). Not yet run on the AVD or with the ESP32; see the M5 checklist below. |
| M6 Wall Setup mapping | **Built.** `assembleDebug testDebugUnitTest` passes (212 tests). Not yet run on the AVD or the real wall; see the M6 checklist below. |
| M7 Polish & safety | **Built.** `assembleDebug testDebugUnitTest` passes (232 tests). Not yet run on the AVD or tablet; see the M7 checklist below. |

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

M5 notes (consistent with the doc unless marked, listed so they are not a surprise):
- **Connecting at launch (my reading of §10.4; tell me if you'd rather not).** With a saved board
  and Simulated off, the app connects to it on launch, and again when Simulated is turned off. §10.4
  asks for a reconnect when the app comes back to the foreground; Android often ends a backgrounded
  app's process, and coming back then is a fresh launch, so connecting at launch is how that case
  reconnects. Drops while the app is running are retried by the controller itself.
- Auto-reconnect (1 s, 2 s, 4 s, then every 10 s) starts after a connection I didn't end drops.
  A first connect that fails (board off, out of range) stops with an error snackbar and Retry
  instead of retrying forever. Inside one attempt, an immediate GATT error such as status 133 is
  retried up to three times, half a second apart.
- A write that fails is treated as a dropped connection: the app closes it, reconnects, and
  LedSync resends the current frame on the new session.
- In the background the retry loop waits; it tries again as soon as the app is back in front.
  Foreground tracking uses `ActivityLifecycleCallbacks`, so no new library.
- `connectGatt(context, false, callback, TRANSPORT_LE)` (§10.3) is deprecated in SDK 37 in favor of
  an overload that only exists on Android 17, so it's kept with a suppressed warning until minSdk
  reaches 37. Everything else uses the API-33 calls §10 names.
- If the MTU request is refused, it's tried once more after 200 ms, then the default MTU is used
  (the encoder chunks for it; it's only slower).
- The GATT UUIDs and INFO parsing live next to the encoder in `led/LedProtocol.kt`, so the whole
  §11 contract is in one file. The protocol itself is unchanged (still v1).
- The Simulated setting switches between the simulated and Bluetooth controllers live
  (`led/ActiveLedController.kt`). Switching always counts as a new session, so the current frame is
  resent to whichever one is now active.
- Brightness is sent when the slider is released (the setting is saved then). The firmware
  re-renders straight away.
- §10.3 step 5's LED-count warning is a banner on Route List, e.g. "The controller has 550 LEDs but
  the wall layout has 154." Expect it with the placeholder layout until the wall is mapped (M6).
- The connected menu also opens while connecting, so a stuck connection can be cancelled with
  Disconnect. Forget device (menu or Settings) disconnects too.
- If the Nearby devices permission is denied, the snackbar has a Settings button that opens the
  app's system settings page.
- Wall Setup keeps the last route lit for now; its own LED content (the current LED, an identified
  hold, all holds) is M6 (now done). The LED preview is drawn on Route Display and in the editor; Wall Setup
  gets it with mapping in M6.
- `UserMessage` moved from the editor to `ui/components`, since the Connect button uses it too.

M6 notes (consistent with the doc unless marked, listed so they are not a surprise):
- **Starting.** While the placeholder is active, Map LEDs first asks for the LED count (the
  controller's count if connected, otherwise 550). Starting replaces the placeholder with an empty
  layout of the same size. Routes made on the placeholder are throwaway (§6.4): their hold ids may
  now point at real holds somewhere else.
- **Tapping.** A tap within 3 in of a hold's marker adds the LED to that hold; anything farther
  makes a new hold. The normal hit radius (half the hold spacing) is capped here, because early in
  mapping the few holds are far apart and would otherwise swallow taps meant for new holds. Zoom
  in for precise taps.
- After every action it moves to the next LED by index (not the next unmapped one), so it follows
  the strand. At the last LED it stays and says so.
- Undo is on the pairing snackbar, as §12.1 says. Any other mistake is fixed with Prev and a new
  tap, which replaces the old assignment (§12.1). Remapping an LED that was a hold on its own reuses
  that hold's id, so routes that use it follow the corrected spot.
- **LED count** in Map LEDs changes the count later. Lowering it unmaps the LEDs past the new
  count, and the dialog says how many.
- Positions are saved to 0.1 in, which keeps the JSON tidy.
- **Review layout (my reading of "a sheet").** The hold's details and actions sit beside the wall
  (below it in portrait) rather than in a modal bottom sheet, so the wall stays visible and you can
  tap the next hold straight away.
- Review → Move: drag anywhere on the wall to move the selected hold, then Save position or
  Cancel. Remove LED is offered for holds with two or more LEDs; Delete hold asks first.
- Review with nothing selected and Light all holds off turns the wall off. Light all holds lights
  every hold in slot 5, and the selected hold stays bright (slot 4). Photo and Align keep the last
  route lit.
- **CSV import (my reading where §12.3 is silent).** The header row is required; columns are
  found by name in any order, and `hold` is optional. Quoted cells, Windows line endings, a
  byte-order mark and decimal commas inside quoted cells all work. Rows after the first of a
  `hold` key may leave x/y blank (the hold uses the first row's position anyway); the first row of a
  key must have one. Blank x/y with no key means hidden. The wall size comes from the current
  layout, since the CSV has none. Every problem is listed with its line number (up to 50).
- **CSV export** writes one row per mapped or hidden LED, with each hold's id as its key. Groups,
  ring sizes and fallback ids (§6.1) aren't in the CSV, so JSON is the exact copy. A fallback-id
  hold comes back from a CSV with its lowest LED as its id.
- Import works even when the saved layout is broken, since importing is how to fix it. If the
  imported wall size differs, the straightened photo is regenerated.
- Export and import go through the system file picker (Create document / Open document).
- No protocol or firmware changes.

M7 notes (consistent with the doc unless marked, listed so they are not a surprise):
- **Backup zip (§13).** `manifest.json` has `schemaVersion` (1), `appVersion` and `createdAt`
  (ISO-8601 with the time zone). `routes.json` is a list of routes with the same fields as the
  database, ids and dates included. `wall_layout.json` and `wall_photo.json` are the same files the
  app keeps. `wall_photo.jpg` is the original photo, byte for byte. The photo files are left out
  when there's no photo.
- **Import backup** checks everything first (every problem is listed, as with layouts), then asks
  "Replace all routes and wall setup?" with what the backup holds. It replaces the layout, every
  route (in one database transaction, so the old routes are never half-replaced), and the photo and
  alignment (removed if the backup has none), then regenerates the straightened image. Settings,
  including the saved board, stay as they are. After a restore the wall turns off, since the
  remembered route may not exist any more.
- A backup from a newer app (schema version above 1) is refused with "Update the app". Unknown
  extra fields and files are ignored, so later versions can add to the format.
- Export is refused while the layout file is broken, because that backup couldn't be restored.
  Import still works then, and fixes it.
- Restoring isn't atomic across the three stores: the layout is replaced first, then the routes,
  then the photo. Because everything was checked first, only a storage error can stop it part way,
  and the message then says what was restored.
- Android Auto Backup stays on (the default), as a second safety net.
- **Keep screen on** applies on Route Display only, as §8.2 says. The Settings row now says so.
- **List scroll restore.** Back from Route Display, the list scrolls to the route Route Display
  showed last (after any swiping), unless it's already in view. It only does this once per visit,
  so rotating the list later doesn't jump. It also works after creating a route.
- **Bluetooth turned off mid-session.** The app now listens for Bluetooth on/off. Turning it off
  ends the connection at once (and any scan), and the button shows "Bluetooth is off". Turning it
  back on reconnects to the board straight away instead of after up to 10 s. If Bluetooth was
  already off when the app started, tapping Connect asks to turn it on, as before.
- **ESP32 out of range or powered off.** Unchanged from M5: the button turns amber and the app keeps
  retrying (1, 2, 4, then every 10 s) while it's in the foreground. The wall keeps the last frame
  (firmware), and the current frame is resent as soon as the board is back.
- **Broken layout file.** The Route List banner and the problems screen now say how to fix it
  (import a layout or a backup in Settings → Data), since the file itself can't be reached
  without a computer.
- Landscape: every screen with the wall already puts it on the left with the controls on the right
  (Route Display, the editor, and all four Wall Setup steps). Route List and Settings are centered
  lists that work either way.
- Removed `StubBody`, which nothing has used since M6.
- No protocol or firmware changes. No database schema change (only new queries), so no migration.

## To do by hand

- [x] ~~Update Android Studio to 2026.2.2 or newer.~~ Done; the project syncs.
- [ ] Run the M1 checklist below on the `Noah_s_Tablet` AVD once the GPU is free.
- [ ] Run the M2 checklist: the placeholder parts on the AVD, the real-photo parts on the tablet.
- [ ] Decide on the M2 perspective-matrix deviation above.
- [ ] Run the M3 checklist on the AVD.
- [ ] Upload the firmware and run the M4 nRF Connect steps.
- [ ] Check the strand's color order when convenient (see M4 notes).
- [ ] Run the M5 checklist: Simulated on the AVD, Bluetooth on the tablet with the ESP32.
- [ ] Say whether connecting to the saved board at launch is what you want (see M5 notes).
- [ ] Run the M6 checklist, then map the real wall (about 30–45 minutes) and send me or commit the exported `wall_layout.json` as `app/src/main/assets/wall_layout.json`.
- [ ] Say whether the M6 CSV and Review choices in the M6 notes above are OK (marked "my reading").
- [ ] Run the M7 checklist, including a backup round trip on the tablet. Keep a backup somewhere off the tablet once the wall is mapped.

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

## M5 manual test checklist

**On the AVD (Simulated):** Settings → turn on **Simulated LED controller**. **Show LED preview** is on by default in debug builds. Have a few routes from M3.

- [ ] Open a route: a small glowing dot sits in the middle of every ring, in the ring's color. Settings → Diagnostics shows a `TX 02 .. 03 …` frame with one 3-byte entry per LED.
- [ ] Swipe quickly through several routes: the dots follow, and once you stop they match the rings of the route on screen. Diagnostics shows fewer frames than routes swiped past (stale ones are dropped), and the last one is the route you stopped on.
- [ ] Turn Show LED preview off: the dots go; the rings stay.
- [ ] **Edit** a route: dots match the draft. Tap a hold on and off, Undo, Redo: the dots follow every step. **Create** a new route: no dots (the wall goes dark), and Diagnostics shows `TX 02 .. 03` with no entries.
- [ ] Back on Route List, Settings and Wall Setup: no new frame is sent (the last route stays lit). Open the route again: still no new frame, since it's already showing.
- [ ] **Lights off** (power icon on Route List) sends `TX 02 .. 03` (all off). Opening any route lights it again.
- [ ] Edit → ⋮ → Delete route: back on Route List, and an all-off frame was sent.
- [ ] Settings → Brightness: move the slider and let go. A palette packet `TX 01 …` is sent with the new values (at 100%: `… 01 00 FF 00 02 00 00 FF 03 96 00 FF 04 FF FF FF 05 40 40 40`).
- [ ] Turn Simulated off and on again: "Simulated controller disconnected", then "connected", the palette, and the current frame are logged again.
- [ ] Rotate on Route Display and in the editor: no frame is resent unless the content changed.

**On the tablet with the ESP32** (firmware from M4 uploaded; Simulated **off**):

Note: with the placeholder layout, hold *n* is LED *n*, so the LEDs that light are the right indices but not at the positions on screen. The Route List banner about 550 vs 154 LEDs is expected until mapping (M6).

- [ ] Tap the grey Bluetooth icon: a short explanation, then Android asks to allow **Nearby devices**. If Bluetooth is off, Android asks to turn it on.
- [ ] The picker scans (progress bar) and lists **HomeWall** with its signal strength. Rescan works after the 10 s scan ends.
- [ ] Tap HomeWall: the icon goes amber, then green. Diagnostics shows `INFO 01 26 02 10`, "Connected to HomeWall (MTU 517)" and the palette `TX`. Settings → Saved device shows HomeWall.
- [ ] Open a route: the wall lights it. Swipe through routes fast: the wall keeps up with no visible lag and ends on the route on screen.
- [ ] In the editor the wall follows every tap, Undo and Redo. Lights off clears the wall.
- [ ] Brightness slider: the wall gets brighter or dimmer when you let go.
- [ ] **Power-cycle the ESP32** while a route is shown: the icon goes amber, and within about 10 s it's green again and the route is back on the wall.
- [ ] Press Home, power-cycle the ESP32, then reopen the app: it reconnects and relights the route.
- [ ] Force-stop the app and reopen it: it connects to HomeWall by itself (see M5 notes).
- [ ] Tap the green icon: a menu with "HomeWall · 550 LEDs", **Disconnect** and **Forget device**. Disconnect: the icon goes grey and the wall stays lit (firmware keeps the last frame); it doesn't reconnect on its own. Tap the icon: it connects again. Forget device: the next tap opens the picker.
- [ ] With the board unplugged, tap Connect: after about 10 s a snackbar says it couldn't connect, with **Retry**.

## M6 manual test checklist

**On the AVD** (Settings → Simulated LED controller on, so the frames show under Diagnostics):

- [ ] Settings → Wall Setup → **Map LEDs** shows "Map your wall" with Number of LEDs 550 ("550 is the default."). **Start mapping**: the placeholder markers disappear and the header reads "LED 0 of 550 · Not mapped yet". Diagnostics shows `TX 02 .. 03 00 00 04` (LED 0 in the white setup slot).
- [ ] Tap the wall: a marker "0" appears there and the header moves to LED 1.
- [ ] Tap right on marker 0: the snackbar says "LED 1 added to hold 0" with **Undo**. Undo puts you back on LED 1, and hold 0 has only LED 0 again.
- [ ] **Hidden** moves on; **Prev** shows that LED as "Hidden". Prev to LED 0 and tap somewhere else: marker 0 moves there.
- [ ] Type 549 in the jump field and tap Go: "LED 549 of 550", and Next is disabled. Hidden there says "That was the last LED (LED 549)."
- [ ] Map a few more, force-stop the app, reopen Map LEDs: it resumes at the first unmapped LED.
- [ ] **LED count** → 10: the dialog warns which LEDs lose their mapping. Cancel.
- [ ] **Review** shows e.g. "550 LEDs · 4 holds (5 LEDs) · 2 hidden · 544 unmapped". Tapping "544 unmapped" opens Map LEDs at the first unmapped LED.
- [ ] Tap a hold: white ring, "Hold n · LEDs … · x …, y … in (Main)", and a frame with its LEDs in slot 4. Tap empty wall: deselected, and an all-off frame is sent.
- [ ] **Light all holds**: a frame with every hold in slot 5 (and the selected one in slot 4).
- [ ] **Move**: drag anywhere and the marker follows; **Save position** keeps it, **Cancel** puts it back. **Remove LED** (on a paired hold) and **Delete hold** (asks first) update the summary.
- [ ] **Export layout (JSON)** → save it → "Layout exported". Settings → Data → **Import layout** → pick that file → "Replace the wall layout?" with the same summary → Replace → "Layout imported", and Review looks the same.
- [ ] Settings → Export layout **CSV**, then import that CSV: the same holds come back (ids of fallback holds may change; see M6 notes).
- [ ] Import a CSV with a mistake (e.g. the same `led` twice): a dialog lists the problem with its line number, and nothing changes.
- [ ] Rotate on Map LEDs and Review: wall on the left, controls on the right.

**On the tablet with the ESP32** (photo taken and aligned as in M2):

- [ ] Map LEDs → Start mapping with **550 is from the controller**. Only one LED is lit on the wall, white.
- [ ] Tap where it shows up on the picture; it moves to the next LED and the wall follows. LEDs behind the wall → Hidden. A second LED on a big hold → tap that hold's marker.
- [ ] Map all 550 (it can be done over several sessions). Review shows "0 unmapped".
- [ ] **Light all holds**: every hold glows dim white on the wall. Tap any marker that looks wrong: only that hold lights brightly; fix it with Move, Remove LED or Delete hold, or remap the LED in Map LEDs.
- [ ] Export the JSON and send it to me (or commit it as `app/src/main/assets/wall_layout.json`), so a fresh install starts with the real wall.
- [ ] Import that same JSON: the summary and markers are identical.

## M7 manual test checklist

**On the AVD:**

- [ ] Make a few routes. Settings → Data → Backup → **Export**: the file picker suggests `homewall-backup-YYYYMMDD-HHmm.zip`. Save it → "Backup saved".
- [ ] Change things: delete a route, edit another, map or move a hold in Wall Setup.
- [ ] Backup → **Import** → pick the zip → "Replace all routes and wall setup?" lists the date, route count, layout summary and photo. **Replace** → "Backup restored". Every route is back as it was (names, grades, holds, list order by date), and Wall Setup shows the old layout and photo.
- [ ] Import something that isn't a backup (e.g. the layout JSON): "This backup can't be restored" with the reason, and nothing changes.
- [ ] Keep screen on: set the AVD's screen timeout to 15 s. On Route Display the screen stays on; on Route List it times out. Turn Keep screen on off in Settings: Route Display times out too.
- [ ] Scroll restore: with 20+ routes, open one near the top, swipe ten routes on, press Back: the list scrolls to that route. Rotate the list: it stays where it is.
- [ ] Create a route from a list scrolled far down → Save → Back: the list shows the new route.
- [ ] Rotate every screen (Route List, Route Display, editor, Save dialog, Settings, all four Wall Setup steps, the filter sheet, the device picker): everything stays usable, with the wall on the left in landscape.
- [ ] No photo: on a fresh install (or after restoring a backup with no photo) every screen shows the plain wall with the fold line.
- [ ] Broken layout: in Android Studio's Device Explorer, open `/data/data/com.kreyenhagen.homewall/files/wall_layout.json`, put one LED in two holds, save it back and force-stop the app. Reopen: Route List shows the banner, Route Display, the editor and Wall Setup list the problem with how to fix it, and nothing crashes. Settings → Import layout (a good JSON) or Import backup fixes it.

**On the tablet:**

- [ ] Backup round trip: export, uninstall and reinstall the app (or clear its data), import: routes, layout, photo and alignment all come back, and the straightened image is regenerated.
- [ ] Connected to the board, turn Bluetooth off from quick settings: the button shows "Bluetooth is off" right away. Turn it back on: it reconnects within a few seconds and the wall shows the current route again.
- [ ] Unplug the ESP32 for a minute: the button goes amber and keeps retrying; plug it back in: it reconnects and the route comes back. Walk out of range with the tablet and back: same.
