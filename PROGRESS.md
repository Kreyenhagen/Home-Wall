# Progress

## Milestones

| Milestone | Status |
|---|---|
| M1 Skeleton | **Built.** `assembleDebug testDebugUnitTest` passes (33 tests). The AVD check is on hold (GPU memory is busy); see the checklist below. |
| M2 Wall view | In progress |
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

## To do by hand

- [x] ~~Update Android Studio to 2026.2.2 or newer.~~ Done; the project syncs.
- [ ] Run the M1 checklist below on the `Noah_s_Tablet` AVD once the GPU is free.

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
