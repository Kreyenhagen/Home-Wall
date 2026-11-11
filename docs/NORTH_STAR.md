# NORTH_STAR.md — Home Wall

> A lightweight, single-wall, Kilter-style app.
> Kotlin · Jetpack Compose · Android tablet → BLE → ESP32 → WS2811 LEDs

---

## 0. How to work from this document (Claude Code: read first)

- This file is the source of truth for scope, architecture, data formats and the LED protocol. If the code and this doc disagree, ask me before changing either one.
- Build in the milestone order in §15. At the end of each milestone:
    1. make sure `./gradlew assembleDebug testDebugUnitTest` passes,
    2. summarize what was built and how I can test it (emulator and/or tablet),
    3. **stop and wait for my go-ahead** before starting the next milestone.
- Keep a `PROGRESS.md` at the repo root with milestone status, any deviations from this doc, and anything I need to do by hand.
- If something here is ambiguous or looks wrong, ask me. Don't guess. Known open questions are in §17.
- Don't add libraries beyond §3 without asking. Use current stable versions through the Gradle version catalog (`gradle/libs.versions.toml`).
- The byte-level protocol in §11 is a contract between the app and the ESP32 firmware in `firmware/` (§11.6). Never change one side without the other. Any change needs a protocol version bump and a note in `PROGRESS.md`.
- If an Android Studio project already exists, use it and its package name. Do not create a second project.

---

## 1. What we're building

**Home Wall** is an Android tablet app for one home climbing wall. I create routes by tapping holds on a photo of my wall, save them with a name and grade, then browse, filter and sort them. When I open a route, the app lights that route's holds on the real wall in real time over Bluetooth LE.

**Core loop:** pick a route → the wall lights up → climb → swipe to the next route → the wall changes instantly.

### Principles
1. **The wall is the display.** An LED update must reach the wall within about 100 ms of the UI changing, even when I swipe fast.
2. **The screen mirrors the wall.** Rings on the photo and lit LEDs on the wall always come from the same data.
3. **Offline, single user, no accounts.** Everything lives on the tablet, with export for backup.
4. **Lightweight.** Few dependencies, simple screens, no speculative features.

### Non-goals (v1)
Accounts or cloud sync, climb logging or ratings, any Kilter database or Kilter-board compatibility, wall-angle settings, multiple walls, a foot-hold role (keep the design open to adding it, see §6.3), phone layouts.

---

## 2. Hardware & environment

| Item | Detail |
|---|---|
| Wall | 8 ft (96 in) wide. Two flat, full-width panels: a **kicker** about 15 in tall at the bottom, and the **main panel** above it, overhanging at **30°**. |
| Panel heights | Measured along each panel's surface (§5). Kicker: about 15 in. Main panel: about 99 in (9.5 ft along the surface, minus the kicker). Both are rough measurements, accurate to about ±3 in, and can be refined at any time (§6.1). |
| LEDs | **550** WS2811 pixels, color order **RBG**, on one data line, driven by an **ESP32 Dev Module** running FastLED (§11.6). Some pixels sit behind the wall (wire-length constraints) and must always stay off; the list is in §12.1. Some holds are covered by more than one pixel. |
| Power & wiring | 24 V LEDs on an 8.33 A (200 W) supply. LED data line on ESP32 **GPIO 16**. All 550 LEDs have been tested and work with FastLED. |
| LED index | 0-based position along the data line, starting at the pixel nearest the ESP32. The app always shows indices 0-based. |
| Link | BLE. The app is the GATT client (central). The ESP32 is the GATT server (peripheral). |
| Tablet | **Samsung Galaxy Tab S9 FE+**: 12.4", 2560×1600 LCD. This is the only target device. |
| Dev setup | Android Studio with a custom AVD that matches the tablet. **The emulator can't talk to the real ESP32 over BLE.** Everything except BLE must be fully usable on the emulator through the simulated controller (§10.5). BLE gets tested on the real tablet over USB or wireless debugging. |

The 30° angle matters to the app in exactly one way: the kicker and the main panel are two different flat planes, so the photo is straightened one panel at a time (§7.1). Nothing else in the app knows or cares about the angle.

---

## 3. Tech stack & conventions

- **Kotlin**, **Jetpack Compose**, **Material 3**. Dark theme by default, because the photo and LED colors read better on dark.
- Single activity. **Navigation Compose** with type-safe (kotlinx.serialization) destinations.
- AndroidX **ViewModel + StateFlow**, unidirectional data flow, immutable UI-state data classes.
- **Room** (via KSP) for routes. **DataStore (Preferences)** for settings. **kotlinx.serialization JSON** for the wall layout, photo alignment and backups.
- `androidx.exifinterface` for photo rotation.
- BLE: the platform `android.bluetooth.*` APIs wrapped in one small coroutine-based class. No third-party BLE library unless the wrapper becomes painful, and ask me first.
- **Manual DI**: one `AppContainer` created in the `Application` class. No Hilt.
- Tests: JUnit and kotlinx-coroutines-test (Turbine is fine).
- **minSdk 33.** The tablet shipped with Android 13, and this lets us use only the non-deprecated BLE APIs. compileSdk and targetSdk are the latest stable.
- **Orientation:** design portrait-first, since the wall is taller than it is wide. **Every screen must still work in landscape.** Recent Android versions ignore orientation locks on large screens for apps targeting the latest SDK. In landscape, put the wall on the left and controls and info on the right.
- No `!!`. No business logic in composables. Provide `@Preview`s for the main composables using the placeholder layout (§6.4).

---

## 4. Architecture

```
┌──────────────── UI (Compose screens + ViewModels) ─────────────────┐
│  RouteList   RouteDisplay   RouteEditor   Settings   WallSetup     │
└──────┬──────────────────────────────┬──────────────────────────────┘
       │ read / write                 │ "show this on the wall"
       ▼                              ▼
 Repositories                      LedSync  (desired frame, latest wins)
 • RouteRepository     (Room)         │
 • LayoutRepository    (JSON file)    ▼
 • WallPhotoRepository (files)     LedController ─┬─ BleLedController ──BLE──► ESP32 ──► WS2811
 • SettingsRepository  (DataStore)                └─ FakeLedController (emulator)
```

### Suggested packages
```
<package>/
  App.kt                  Application + AppContainer
  MainActivity.kt
  data/route/             RouteEntity, RouteDao, AppDatabase, RouteRepository, converters
  data/layout/            WallLayout models, LayoutRepository, LayoutValidator,
                          CsvLayoutImporter, PlaceholderLayout
  data/photo/             WallPhotoRepository, PhotoAlignment, WallRectifier
  data/settings/          SettingsRepository
  data/backup/            BackupManager
  domain/                 HoldRole, grade helpers, EditorRules, EditHistory, RouteSorting
  led/                    LedFrame, LedProtocol (encoder), Palette, LedSync,
                          LedController, FakeLedController
  ble/                    BleLedController, BleScanner, permission helpers
  ui/theme/
  ui/wall/                WallView, WallTransform, gestures, drawing
  ui/routelist/  ui/routedisplay/  ui/editor/  ui/settings/  ui/wallsetup/
  ui/components/          ConnectButton, GradeChip, SaveRouteDialog, DriveLeds, …
  util/                   LogBuffer (in-memory ring buffer for diagnostics)
```

The repo also holds the ESP32 sketch at `firmware/home_wall/home_wall.ino` (§11.6). It isn't part of the Gradle build.

### Single sources of truth
| What | Owner |
|---|---|
| Which holds exist and which LEDs each owns | `LayoutRepository` (`wall_layout.json`) |
| Routes | Room via `RouteRepository` |
| Wall photo, alignment and straightened image | `WallPhotoRepository` |
| What the wall should show right now | `LedSync` |
| Settings, including the list's sort and filter | `SettingsRepository`. Route List and Route Display both read it, so swipe order always matches list order. |

---

## 5. Core concepts & coordinate system

- **Panel**: one flat section of the wall, full width. Panels are stacked bottom to top: **Kicker**, then **Main**.
- **LED**: one WS2811 pixel, identified by its index.
- **Hold**: a selectable spot on the wall. It owns one or more LED indices and has one position. This is what I tap, what gets a ring, and what the debug "tiles" represent.
- **Hidden LED**: an LED behind the wall or otherwise not under a hold. It is never lit.
- **Route**: a name, a grade, and a set of `holdId → role`.
- **Role**: `START`, `MIDDLE`, `FINISH`.

### Coordinates: the unfolded wall
Wall coordinates are **inches on the unfolded wall surface**. Picture the wall flattened out, so the kicker and the main panel lie in one plane:

```
 y = 114 ┌──────────────────────────┐  top edge of main panel
         │                          │
         │       Main panel         │  (30° overhang, but drawn flat)
         │                          │
 y = 15  ├──────────────────────────┤  fold line (kicker meets main)
         │         Kicker           │
 y = 0   └──────────────────────────┘  floor
        x = 0                    x = 96
```

- The origin is the **bottom-left corner of the kicker**. x runs right (0–96). y runs **up along the surface**: the kicker covers y 0–15, the fold line is at y = 15, and the main panel covers y 15 to 15 + main height (about 114).
- A hold's panel is derived from its y; holds don't store a panel.
- Hold positions are always stored in wall coordinates, so they survive retaking the photo.
- The only perspective math in the app is the one-time photo straightening (§7.1). Everything at runtime is plain scaling between wall coordinates and the straightened image.

---

## 6. Data model

### 6.1 Wall layout: `wall_layout.json`

```json
{
  "schemaVersion": 1,
  "placeholder": false,
  "wall": {
    "widthIn": 96.0,
    "panels": [
      { "name": "Kicker", "heightIn": 15.0 },
      { "name": "Main",   "heightIn": 99.0 }
    ]
  },
  "ledCount": 550,
  "holds": [
    { "id": 0,  "x": 4.0,  "y": 6.0,  "leds": [0] },
    { "id": 7,  "x": 12.0, "y": 22.0, "leds": [7, 8], "group": "main" },
    { "id": 40, "x": 8.0,  "y": 26.0, "leds": [40], "group": "aux-a", "ringIn": 2.5 }
  ],
  "hiddenLeds": [1, 2, 3, 4, 5, 6]
}
```

Rules:
- `panels` are listed bottom to top, each spanning the full width. The total wall height is the sum of the panel heights.
- **Panel sizes are approximate** (about ±3 in) and can be edited at any time in Wall Setup → Align, even after mapping. Holds were placed by tapping the straightened image, so a resize must keep every hold on the same spot in that image:
    - When a panel's height changes, scale the y of each hold on that panel proportionally (its fraction of the way up the panel stays the same). Shift every hold on the panels above it by the change in height.
    - When the width changes, scale every hold's x proportionally.
    - Then regenerate the straightened image (§7.1).
- `id` is a stable Int. Routes reference holds by `id`. A new hold gets `id` = its lowest LED index if no existing hold already uses that id; otherwise it gets the lowest unused id ≥ `ledCount`. The fallback is needed because ids never change while LEDs can move between holds: e.g. hold 37 owns LEDs [37, 38], LED 37 is remapped to a new spot, and hold 37 keeps its id with only LED 38, so the new hold can't also be 37. **Never renumber existing ids.**
- `leds`: one or more indices, each `< ledCount`. Each LED belongs to at most one hold.
- `hiddenLeds`: LEDs that have been reviewed and intentionally stay off. An LED in neither a hold nor `hiddenLeds` is **unmapped**. It is also always off, and Wall Setup lists it as still to do.
- `group` (optional): a free-text label, for example the names of my grid patterns. It is only used to color debug markers.
- `ringIn` (optional): a ring-radius override for this hold, in inches.
- **Validate on load:** duplicate ids, an LED in two holds, an LED in both a hold and `hiddenLeds`, out-of-range indices, positions off the wall, non-positive panel sizes. Show a clear error listing every problem. Never crash.
- **Storage:** `filesDir/wall_layout.json`. On first launch, copy `assets/wall_layout.json` if it exists. Otherwise generate the placeholder (§6.4). Write atomically: temp file, then rename.
- The file is meant to be hand-editable. It's where I can keep my own lists of LED indices.

### 6.2 Photo, alignment & straightened image
- `filesDir/wall_photo.jpg`: the original photo with EXIF rotation applied, long side ≤ 4096 px.
- `filesDir/wall_photo.json`: alignment points in normalized (0–1) photo coordinates. There is one left/right pair per panel edge, listed bottom to top, so the wall needs **6 points**:
```json
{
  "edges": [
    { "left": [0.12, 0.95], "right": [0.88, 0.95] },
    { "left": [0.11, 0.84], "right": [0.89, 0.84] },
    { "left": [0.03, 0.04], "right": [0.97, 0.04] }
  ]
}
```
- Edge 0 is the bottom of the kicker (the floor line).
- Edge 1 is the fold between the kicker and the main panel.
- Edge 2 is the top edge of the main panel.
- `filesDir/wall_rectified.jpg`: the straightened, unfolded wall image generated from the photo and alignment (§7.1). Every screen displays this image, not the raw photo.

### 6.3 Routes (Room)

```kotlin
@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val grade: Int?,             // 0..17 = V0..V17, null = Project (ungraded)
    val holds: List<RouteHold>,  // TypeConverter → JSON string
    val createdAt: Long,         // epoch ms
    val updatedAt: Long,
)

@Serializable data class RouteHold(val holdId: Int, val role: HoldRole)
@Serializable enum class HoldRole { START, MIDDLE, FINISH }  // FOOT may be added later
```
- Grade label: `null → "Project"`, `n → "V$n"`.
- Set `exportSchema = true` and write real migrations. **Never use a destructive-migration fallback**, because it would wipe my routes.
- If a route references a hold id that's missing from the layout, render the rest of the route and show a small note on Route Display, for example "2 holds no longer exist". The editor drops those holds on save.

### 6.4 Placeholder layout (development only)
When no layout exists, generate one in code. It uses the same two panels as the real wall, so the panel code gets exercised from day one.
- 11 columns at x = 8, 16, … 88.
- Kicker rows at y = 4 and 11. Main-panel rows at y = 19, 27, … 107 (12 rows).
- That's 14 rows × 11 columns = 154 holds, one LED each, numbered serpentine from the bottom-left. `ledCount` = 154. `"placeholder": true`.

While the placeholder is active, Route List shows the banner *"Placeholder layout. Map your wall in Settings → Wall Setup."* Routes made on the placeholder are throwaway.

### 6.5 Settings (DataStore)
| Setting | Default |
|---|---|
| List sort | Newest |
| Grade filter min/max | V0 / V17 |
| Include projects | on |
| Saved BLE device (address + name) | none |
| LED brightness | 50% |
| Ring size | 3.5 in |
| Show hold markers (debug tiles) | **on** for now |
| Show LED preview (debug) | on in debug builds |
| Keep screen on | on |
| Simulated LED controller | off |

---

## 7. The wall view

### 7.1 Straightening the photo (the only perspective math)
A photo of this wall shows two differently skewed shapes: the vertical kicker and the main panel leaning out at 30°. Instead of warping the overlays at runtime, the app **straightens the photo once** into a flat, front-on picture of the unfolded wall. `WallRectifier` does this:
- **When:** every time the alignment is saved or the photo changes. Run it on a background dispatcher and show a progress indicator.
- **Output:** `wall_rectified.jpg` at **20 px per inch**, which is 1920 × 2280 px for a 96 × 114 in wall.
- **Per panel:**
    1. Take the panel's quad in photo pixels: the left and right points of its bottom edge and its top edge.
    2. Build a perspective matrix with `android.graphics.Matrix.setPolyToPoly(src, 0, dst, 0, 4)`, where `src` is that quad and `dst` is the panel's band in the output image.
    3. Clip to the band, overlapping neighboring bands by 1 px so there's no seam.
    4. Draw the photo through the matrix with bitmap filtering on.
- **Result:** wall coordinates map to image pixels by plain scaling (with y flipped). Rings are true circles and hit testing is trivial.
- **Without a photo:** draw a plain wall-colored rectangle with the same aspect ratio and a subtle line at the fold. The app must be fully usable without a photo.
- **Unit tests:** for each panel, the quad's four corners map exactly to its band's four corners.

### 7.2 WallView (shared component)
`WallView` is used by Route Display, Route Editor and Wall Setup.

- **Inputs:** the straightened image (nullable), layout, `rings: Map<Int /*holdId*/, Color>`, display flags (markers, labels, LED preview), `zoomEnabled`.
- **Callbacks:** `onTapHold(holdId)`, `onTapEmpty(wallPoint)`.
- **Transform:** `WallTransform` = zoom/pan ∘ fit (scale + offset, y flipped, like `ContentScale.Fit`). It is the only wall ↔ screen conversion at runtime. Unit-test that wall → screen → wall round-trips within 0.01 in.

### 7.3 Drawing
- **Rings:** a stroked circle centered on the hold.
    - Radius = `(hold.ringIn ?: settings.ringSize) × scale`, minimum 12 dp.
    - 3 dp stroke in the role color, with a thin dark outer stroke so rings stand out on any photo. No fill.
- **Hold markers (debug tiles, toggle):** a small translucent square at each hold labeled with its LED numbers (e.g. "37, 38" for a paired hold), colored by `group`. Hidden LEDs are not drawn. The fold line is drawn too. I'll turn these off once I trust the mapping.
- **LED preview (debug, toggle):** small glowing dots at the holds whose LEDs are in the last frame actually sent to the controller. This lets me verify LED logic on the emulator against the rings.

### 7.4 Hit testing
On tap: convert the point to wall coordinates, then pick the nearest hold within the hit radius. The hit radius is about half the median nearest-neighbor hold spacing on screen, minimum 24 dp. If no hold is in range, call `onTapEmpty(wallPoint)`.

### 7.5 Zoom
- **Route Editor and Wall Setup:** pinch to zoom 1×–5×, two-finger pan, double-tap to reset. A single tap still selects. Clamp panning so the wall can't leave the view.
- **Route Display:** no zoom, because horizontal swipes belong to the pager.

### 7.6 Performance
Decode the straightened image once into an `ImageBitmap` held by `WallPhotoRepository`. All screens and pager pages share that bitmap. Draw the overlay in a single `Canvas`.

---

## 8. Screens

```
Route List ──tap──► Route Display ◄─swipe─►
   │                     │ Edit
   │                     ▼
   │               Route Editor (edit) ──Save──► Route Display
   ├─ Create ──► Route Editor (new) ──Save──► Route Display   (editor popped)
   ├─ ⚙ ──► Settings ──► Wall Setup: Photo → Align → Map LEDs → Review
   └─ Connect ──► device picker sheet
```

Type-safe destinations: `RouteList`, `RouteDisplay(routeId: Long)`, `RouteEditor(routeId: Long = -1L /* -1 = new */)`, `Settings`, `WallSetup`.

### 8.1 Route List (start destination)

**Top app bar:**
- Title: **Home Wall**.
- **Connect** button: a Bluetooth icon showing the state. Grey = disconnected, amber = scanning or connecting, green = connected, plus a distinct look for Simulated.
- **Lights off** button.
- **Sort** menu.
- **Filter** button.
- **Settings** gear.

**Body:**
- Scrollable `LazyColumn` of routes. Each row shows the name (one line, ellipsized) and a grade chip ("V5" or "Project"). Tapping a row opens Route Display.
- **Create** FAB opens Route Editor for a new route.

**Sort** (persisted): Grade high→low, Grade low→high, Newest, Oldest.
- "Newest" and "Oldest" use `createdAt`.
- Grade sorts break ties newest-first.
- Projects always come after graded routes in both grade orders.

**Filter** (bottom sheet, persisted): a RangeSlider V0–V17, an "Include projects" switch, and Reset. When a filter is active, show it as a chip under the app bar (e.g. "V3–V7 · no projects"). Tapping the chip opens the sheet.

Filter and sort in Kotlin (there will be at most a few hundred routes), in one shared function used by both Route List and Route Display.

**Empty states:**
- No routes: "No routes yet. Tap Create to make one."
- Filter hides everything: "No routes match this filter" with a Reset button.

**Other:**
- Show the placeholder-layout banner (§6.4) when relevant.
- When I come back from Route Display, scroll the list to the last route I viewed.
- LEDs: see §9.2. This screen keeps the last displayed route lit; Lights off clears it.

**Connect button behavior:**
- Disconnected with a saved device: connect to it.
- No saved device: request permissions, ask to turn Bluetooth on if needed, then open the device picker. The picker is a sheet that scans for 10 s, lists boards by name and signal strength, and has a Rescan button. Picking a board connects and saves it.
- Connected: a menu with the device name, Disconnect, and Forget device.
- Show errors as a snackbar with Retry.

### 8.2 Route Display
- `HorizontalPager` over the **same filtered and sorted list** as Route List, opened at the tapped route.
- If the opened route isn't in the filtered list (e.g. I just saved a Project while projects are filtered out), insert it at its sorted position so it still shows.
- Each page: `WallView` with rings in role colors around that route's holds. Below it: the route **name** (large) and **grade**, plus the position, e.g. "12 / 48".
- **Edit** button in the top app bar opens Route Editor for the current page's route. Back returns to Route List.
- **LEDs in real time:** when `pagerState.currentPage` changes (it changes mid-swipe at the halfway point), call `LedSync.showRoute(...)` for that route. Fast swipes are handled by LedSync's conflation (§9.1), not by debouncing in the UI.
- The pager list collects the repository flow, so edits and deletes show up immediately. When the list changes, keep the current route selected by id.
- Keep the screen on if that setting is enabled.

### 8.3 Route Editor: Create

**Layout (portrait):** a zoomable `WallView` fills the top. The bottom bar holds:
- **Role selector:** three toggle buttons, each with a color swatch: **Start (n/2)**, **Middle (n)**, **Finish (n/2)**. Exactly one is selected. Default: Start.
- **Undo**, **Redo** (disabled when there's nothing to undo or redo), and **Save**.

**Rules:**
- All holds start inactive, with no rings.
- Tapping an inactive hold activates it with the selected role, and it gets a ring in that role's color.
- Tapping an active hold **deactivates** it, whatever role is selected. To change a hold's role: tap it off, pick the role, tap it on again.
- **Max 2 Start and 2 Finish holds.** A tap that would exceed the limit changes nothing and shows a snackbar ("Max 2 start holds" or "Max 2 finish holds") with a light haptic. Middle holds are unlimited.
- Tapping empty space does nothing.
- **Undo/redo:** every hold change is one step. Changing the selected role is not a step. A new change clears the redo stack. History lives in the ViewModel (so it survives rotation), capped at 200 steps.
- **LEDs in real time:** every change, including undo and redo, goes to `LedSync.show(draftFrame)`. A new route starts empty, so the wall goes dark.
- **Save** is enabled when at least one hold is active, and opens the Save dialog.
- Back with unsaved changes asks "Discard this route?"

**Save dialog:**
- **Name:** required (non-blank after trimming), max 40 characters, autofocused.
- **Grade:** dropdown with Project, V0 … V17. Default Project. Project means ungraded.
- A soft, non-blocking note if there's no Start or no Finish hold.
- Cancel / Save. Save inserts the route (`createdAt = updatedAt = now`), then navigates to **Route Display** for it and pops the editor, so Back goes to the list.

### 8.4 Route Editor: Edit
Same screen as Create, except:
- It loads the route's holds, and history starts there (undo can't go further back).
- The Save dialog is prefilled. Saving updates the route (`updatedAt = now`, `createdAt` unchanged) and returns to Route Display on that route.
- Save is enabled whenever there's at least one hold, so I can change only the name or grade.
- Overflow menu → **Delete route** → confirm → delete, go back to Route List, and turn the LEDs off.

### 8.5 Settings
- **Wall Setup** (opens §8.6)
- **Display:** Show hold markers, Show LED preview, Ring size slider, Keep screen on
- **LEDs:** Brightness slider (resends the palette live), Simulated LED controller
- **Bluetooth:** saved device, Forget device
- **Data:** Export / Import backup (§13), Export / Import layout (JSON or CSV, §12.3)
- **Diagnostics:** the last 200 BLE and LED log lines, including packet hex

### 8.6 Wall Setup
Four steps, shown as tabs or a stepper: **Photo · Align · Map LEDs · Review.** Each step works on its own later. For example, I can retake the photo and re-align without remapping.

1. **Photo**
    - "Take photo" uses the system camera via `ActivityResultContracts.TakePicture` and a `FileProvider`. **Don't declare the CAMERA permission**: if it's declared but not granted, the system camera intent fails.
    - "Choose photo" uses the Photo Picker, which needs no permissions.
    - Show these tips:
        - Turn the LEDs off and light the wall evenly.
        - Stand centered left to right, at about mid-height.
        - The main panel leans toward you, so step back rather than tilting the tablet a lot.
        - Use the 1× lens, not ultra-wide. Shoot in portrait.
        - Get all six alignment points in frame: both ends of the floor line, both ends of the fold, and both top corners.
2. **Align**
    - Show the raw photo with **six draggable handles**, bottom to top: Floor left/right, Fold left/right, Top left/right. Lines join them into the two panel quads. Show a magnifier loupe while dragging.
    - The initial guess is: floor at 95% of the photo height, fold at 85%, top at 5%, each inset 10% from the sides.
    - Show the wall width and each panel's height as editable numbers in inches, prefilled from the layout. Editing them follows the resize rule in §6.1.
    - Saving stores the points and runs the rectifier (§7.1). Then show the straightened result with the hold markers on it, so I can check the fit, with a button to go back and adjust.
3. **Map LEDs** (§12.1)
4. **Review** (§12.2)

---

## 9. LED sync: who controls the wall

### 9.1 LedSync
One app-wide object, and the only thing that talks to `LedController`.

```kotlin
data class LedEntry(val index: Int, val slot: Int)
data class LedFrame(val entries: List<LedEntry>) {   // sorted by index, no duplicates
    companion object { val OFF = LedFrame(emptyList()) }
}
```

Behavior:
- It holds the **desired frame** in a `StateFlow`. A single collector sends frames to the controller.
- **Latest wins:** while one frame is in flight, newer frames replace the pending one and intermediate frames are dropped. Identical consecutive frames aren't resent. This is what makes fast swiping work.
- On every (re)connect, the controller reads INFO and sends the palette. LedSync then resends the current desired frame.

```kotlin
class LedSync(private val controller: LedController, scope: CoroutineScope) {
    private val desired = MutableStateFlow<LedFrame?>(null)  // null = never set; leave the wall alone
    private var lastRoute: LedFrame? = null

    fun show(frame: LedFrame) { desired.value = frame }
    fun showRoute(frame: LedFrame) { lastRoute = frame; show(frame) }
    fun showLastRoute() { lastRoute?.let(::show) }
    fun lightsOff() { lastRoute = LedFrame.OFF; show(LedFrame.OFF) }

    init {
        scope.launch {
            combine(controller.session, desired, ::Pair)
                .distinctUntilChanged()
                .conflate()
                .collect { (session, frame) ->
                    if (session != null && frame != null) controller.sendFrame(frame) // suspends until acked
                }
        }
    }
}
```

### 9.2 Which screen drives the wall
**Every screen drives the LEDs.** Screens without their own LED content show the last displayed route.

| Screen | The wall shows |
|---|---|
| Route Display | The current page's route, via `showRoute`, which is also remembered as the last route. |
| Route List | The last route. **Lights off** calls `lightsOff()`. |
| Route Editor | The draft, live. |
| Settings | The last route. |
| Wall Setup | The current LED, the identified hold, or all holds (§12). |

Implement this with a `DriveLeds(...)` composable effect (e.g. `LifecycleResumeEffect`) that pushes only while the screen's nav back-stack entry is **RESUMED**, re-pushes whenever the frame changes, and pushes again when the screen resumes. **Don't clear the LEDs in `onDispose`.** During navigation transitions it can fire after the next screen has already set its frame.

### 9.3 Roles, colors and palette slots

| Use | Slot | On-screen ring | LED RGB (before brightness) |
|---|---|---|---|
| Off | 0 | n/a | 0, 0, 0 (fixed) |
| Start (green) | 1 | `#00E676` | 0, 255, 0 |
| Middle (blue) | 2 | `#2979FF` | 0, 0, 255 |
| Finish (purple) | 3 | `#B040FF` | 150, 0, 255 |
| Setup cursor / identify | 4 | n/a | 255, 255, 255 |
| Setup "all holds" | 5 | n/a | 64, 64, 64 |

- Keep the `HoldRole → (slot, ring color, LED RGB)` mapping in one file. Screen colors and LED colors are deliberately separate, because LEDs render colors differently from the screen.
- LED colors are always plain R, G, B, in the app and in the protocol. The strand is wired RBG, and FastLED's `COLOR_ORDER` in the firmware handles that. The app never reorders channels.
- **Brightness** is applied app-side: scale the palette RGB values before sending `SET_PALETTE`. Changing brightness resends the palette, and the firmware re-renders immediately.
- Building a frame from a route is a pure function: for each route hold that exists in the layout, for each LED of that hold, add `LedEntry(led, role.slot)`. Unit-test it.

---

## 10. Bluetooth (Android side)

### 10.1 Manifest & permissions
```xml
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```
Request permissions at the first connect, with a one-line rationale. If Bluetooth is off, ask the system to turn it on.

### 10.2 Scanning
Use a `ScanFilter` on the service UUID (§11.1) and `SCAN_MODE_LOW_LATENCY`. Stop after 10 s or when I pick a device. Show name and RSSI.

### 10.3 Connect sequence
1. `connectGatt(context, autoConnect = false, callback, TRANSPORT_LE)`
2. `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)`
3. `requestMtu(517)`, then use the MTU reported in `onMtuChanged`.
4. `discoverServices()` and find the service, `LED_CTRL` and `INFO`. If any is missing, show the error "Not a Home Wall controller".
5. Read `INFO`:
    - The protocol version must be 1; otherwise show an error.
    - Compare `ledCount` to the layout. On a mismatch, show a warning banner but keep going.
6. Send the palette, publish a new `session`, and LedSync resends the current frame. The state becomes Connected.

### 10.4 Rules
- **One GATT operation at a time.** Use a `Mutex` and await each callback with a `CompletableDeferred`, with a 2 s timeout.
- Use the API-33 `writeCharacteristic(characteristic, value, WRITE_TYPE_DEFAULT)` (write with response). A write is complete when `onCharacteristicWrite` arrives.
- Never send a packet larger than `min(MTU − 3, 512)` bytes. Chunk instead (§11.4).
- On disconnect: call `gatt.close()` and set the state to Disconnected. If I didn't ask for the disconnect and the app is in the foreground, auto-reconnect to the saved device with backoff: 1 s, 2 s, 4 s, then every 10 s. GATT status 133 is common: close and retry.
- The connection lives in `AppContainer` (app-scoped), so it survives screen changes and rotation. If it dropped while the app was in the background, reconnect when the app comes back to the foreground.
- `ConnectionState`: `Disconnected`, `Scanning`, `Connecting`, `Connected(name, ledCount)`, `Simulated`, `Error(message)`.
- Log every connection event and every packet (as hex) to `LogBuffer`, shown in Settings → Diagnostics.

### 10.5 LedController
```kotlin
interface LedController {
    val state: StateFlow<ConnectionState>
    val session: StateFlow<Int?>  // new value on every successful (re)connect; null when not connected
    suspend fun sendPalette(slots: Map<Int, Rgb>)
    suspend fun sendFrame(frame: LedFrame)  // encodes and chunks per §11, suspends until all packets are acked
}
```
- `BleLedController`: the real implementation.
- `FakeLedController`: reports `Simulated`, encodes with the real encoder, logs packets as hex, and adds about 20 ms of artificial latency per packet. It's selected with Settings → "Simulated LED controller" and used on the emulator.

---

## 11. LED protocol v1 (app ⇄ ESP32 contract)

### 11.1 GATT
| | UUID | Properties |
|---|---|---|
| Service | `5b1e0001-7c2f-4a8e-9d3b-2f6a1c4e8b70` | n/a |
| `LED_CTRL` | `5b1e0002-7c2f-4a8e-9d3b-2f6a1c4e8b70` | Write, Write Without Response |
| `INFO` | `5b1e0003-7c2f-4a8e-9d3b-2f6a1c4e8b70` | Read |

The ESP32 advertises the service UUID in the advertising packet and the device name `HomeWall` in the scan response.

### 11.2 INFO (read)
| Byte | Meaning |
|---|---|
| 0 | Protocol version = `1` |
| 1–2 | `ledCount`, uint16 little-endian |
| 3 | Number of palette slots supported (≥ 8) |
| 4… | Optional extra data; the app ignores it |

Example: `01 26 02 10` = protocol 1, 550 LEDs, 16 palette slots.

### 11.3 LED_CTRL messages
Every message starts with `[opcode u8][seq u8]`. `seq` is an app-side counter that wraps from 255 to 0. The firmware may log it but never rejects a message because of it.

**`0x01` SET_PALETTE**
- Body: repeated `[slot u8][r u8][g u8][b u8]`.
- Slot 0 is always black and is ignored if sent.
- The firmware stores the colors, then re-renders the current frame with them.

**`0x02` FRAME**
- Body: `[flags u8]`, then repeated `[led u16 LE][slot u8]`.
- `flags` bit 0 = **BEGIN**: clear the back buffer (all slots to 0) before applying entries.
- `flags` bit 1 = **COMMIT**: copy the back buffer to the front buffer and render it to the LEDs.
- A single-packet frame uses flags `0x03`. An empty frame with flags `0x03` turns everything off.
- An out-of-range LED index is ignored. An unknown slot renders as off.
- A new BEGIN discards any uncommitted partial frame, so an interrupted multi-packet frame can never appear on the wall.

### 11.4 Sizes & chunking
- Max packet = `min(MTU − 3, 512)` bytes.
- Max entries per FRAME packet = `(maxPacket − 3) / 3`, which is **169** at MTU 517 and **5** at the default MTU of 23.
- A typical route (≤ 40 LEDs) fits in one packet. Lighting all 550 LEDs takes 4 packets at MTU 517.
- For bigger frames, send the first packet with BEGIN (`0x01`), middle packets with `0x00`, and the last with COMMIT (`0x02`).

### 11.5 Golden test vectors (the encoder must match byte-for-byte)
1. **Palette** (seq 0) with slots 1–4 at the §9.3 defaults (0,255,0), (0,0,255), (150,0,255), (255,255,255):
   `01 00 01 00 FF 00 02 00 00 FF 03 96 00 FF 04 FF FF FF`
2. **Route frame** (seq 7): Start on LED 12; Middle on LEDs 40 and 41 (one paired hold) and LED 300; Finish on LED 415:
   `02 07 03 0C 00 01 28 00 02 29 00 02 2C 01 02 9F 01 03`
3. **All off** (seq 8): `02 08 03`
4. **Chunking:** all 550 LEDs at MTU 517 make four packets: flags `01` with 169 entries, `00` with 169, `00` with 169, `02` with 43.

### 11.6 ESP32 firmware (`firmware/home_wall/home_wall.ino`)
No firmware exists yet. Claude Code writes it in M4, before the app's Bluetooth code, so each side can be tested against the other.

**Goal: copy, paste, upload.** The firmware is **one self-contained `.ino` file** that I paste into a new Arduino IDE sketch and upload, with no other steps:
- Everything lives in that one file: no extra tabs, headers or config files.
- The only library to install is **FastLED** (from the Library Manager). BLE uses the library that comes with the ESP32 board package (`BLEDevice.h`), so there's nothing else to install.
- Default board settings: board **ESP32 Dev Module**, default partition scheme. Don't require any menu changes.
- All settings are already filled in for my wall (below), so I don't edit anything before uploading.
- The top of the file is a short comment with the setup steps:
    1. In Boards Manager, install "esp32 by Espressif Systems" (once).
    2. In Library Manager, install "FastLED" (once).
    3. Paste this file into a new sketch, select ESP32 Dev Module and the port, and click Upload.
- It must compile on both the current ESP32 Arduino core (3.x) and 2.0.x. Avoid BLE APIs that changed between them. For example, `BLECharacteristic::getValue()` returns `std::string` in 2.x but `String` in 3.x, so read written data with `getData()` and `getLength()` instead.
- If `arduino-cli` is available, compile-check with `arduino-cli compile --fqbn esp32:esp32:esp32`. If it isn't, say in `PROGRESS.md` that the sketch hasn't been compiled.

**Settings block** at the top of the file:
```cpp
// ---- Home Wall settings ----
#define DATA_PIN            16      // GPIO connected to the LED data line
#define LED_COUNT           550
#define CHIPSET             WS2811
#define COLOR_ORDER         RBG
#define DEVICE_NAME         "HomeWall"
#define MAX_MILLIAMPS       7000    // power cap: ~85% of the 24 V, 8.33 A supply
#define MA_PER_PIXEL_WHITE  15      // estimated draw of one 24 V pixel at full white
```

**LED setup**
- `FastLED.addLeds<CHIPSET, DATA_PIN, COLOR_ORDER>(leds, LED_COUNT)`.
- Global brightness stays at 255, because brightness is applied app-side in the palette.
- Don't use FastLED's built-in power limiting. Its current model assumes 5 V pixels, so it's inaccurate for 24 V pixels. Use the simple cap in `loop()` instead.

**State**
- `uint8_t backSlots[LED_COUNT]` and `uint8_t frontSlots[LED_COUNT]`.
- `CRGB palette[16]`, with slot 0 always black.
- `CRGB leds[LED_COUNT]`.
- A `needsRender` flag.
- One FreeRTOS mutex guarding the buffers and palette, shared by the BLE callback and `loop()`.

**BLE**
- A GATT server matching §11.1. Set the preferred MTU to 517.
- Advertise the service UUID, with the name in the scan response.
- **Restart advertising after every disconnect.**
- Fill in `INFO` at startup per §11.2.

**On a write to `LED_CTRL`:** parse per §11.3. Ignore malformed messages (wrong length, unknown opcode) and log them to serial.
- SET_PALETTE: update the slots and set `needsRender`.
- FRAME: on BEGIN clear `backSlots`; apply the entries; on COMMIT copy `backSlots` to `frontSlots` and set `needsRender`. The back buffer persists between writes, so chunked frames build up across packets.
- **Never call `FastLED.show()` in the callback.**

**loop():** if `needsRender` is set:
1. Copy `frontSlots` and `palette` while holding the lock, clear the flag, and release the lock.
2. Resolve each LED's slot to its palette color in `leds`.
3. **Power cap:** estimate the current as `(sum of r + g + b over all LEDs) × MA_PER_PIXEL_WHITE / 765`. If that's over `MAX_MILLIAMPS`, scale every LED down by `MAX_MILLIAMPS / estimate` (e.g. with `nscale8`) and log it to serial. Normal routes never get near the cap; it's a safety net for things like lighting every LED at full brightness.
4. Call `FastLED.show()`.

**Disconnects:** keep showing the last committed frame, so the route stays lit if the tablet sleeps.

**Boot:** all LEDs off.

**Serial log** at 115200 baud: connections, MTU, and one line per message (opcode, seq, entry count).

**Testing without the app:** with a generic BLE app such as nRF Connect, write the hex from §11.5 to `LED_CTRL`.
- Vector 1 then vector 2 must light LED 12 green; LEDs 40, 41 and 300 blue; and LED 415 purple.
- Vector 3 must turn them all off.

**Hardware is already verified:** every LED works with FastLED on GPIO 16 with RBG order. The firmware's job is only to add the Bluetooth and protocol layer on top of that.

---

## 12. Mapping the wall

**Recommendation: map the wall inside the app, using the wall itself.** The app lights one LED at a time and I tap where it shows up on the straightened wall image.

- No drawings, measurements or transcription.
- Positions land exactly where the holds appear in the image.
- Skipped LEDs behind the wall get handled one by one as I reach them.
- Paired LEDs are created by tapping the hold a second time.
- 550 LEDs at a few seconds each is roughly 30–45 minutes, and it can be resumed.

A spreadsheet isn't the primary method because off-by-one errors in the wiring order and skipped LEDs are easy to make and hard to spot. Sheets is still supported as an import (§12.3).

### 12.1 Map LEDs step
- First, confirm the total LED count. Default 550, or the value from INFO if connected.
- **Known hidden LEDs.** I've already found the LEDs behind the wall: **0–40, 45, 54, 59, 64, 69, 74, 79, 84–86, 93, 106, 113, 120, 127, 134, 141, 148, 155, 162, 169, 200, 231, 262, 293, 325, 356, 371, 388, 420, 452, 484, 485, 517** (75 LEDs).
    - Starting to map puts them in `hiddenLeds` (those below the LED count).
    - After that they're ordinary Hidden LEDs, so any of them can be changed like any other LED: go to it and tap where it lights up, or tap a tile to pair it.
    - If some of them are unmapped (e.g. a layout started before this list), Map LEDs shows a "Mark Hidden" button that marks them all in one tap, with Undo. LEDs on the list that are already in tiles are left alone, and the snackbar names them.
- Header: "LED 37 of 0–549" (LEDs are numbered from 0, so it shows the last index, not the count), with Prev/Next and a jump-to-index field. The step resumes at the first unmapped LED.
- **Auto-advance** goes to the next LED that isn't Hidden, so LEDs behind the wall are skipped. Prev, Next and the jump field still stop on every LED.
- The wall lights **only the current LED** (slot 4), except while a tile is held (below).
- Actions:
    - **Tap an empty spot** on the image: create a new hold there containing this LED, then auto-advance.
    - **Tap an existing hold:** add this LED to that hold (pairing), then auto-advance. Show a snackbar: "LED 37 paired with LED 36 · Undo".
    - **Hidden** button: add the LED to `hiddenLeds`, then auto-advance.
- **Press and hold a tile** (a hold's marker). The wall lights that tile's LEDs (slot 4) instead of the current LED, with a light haptic.
    - **Drag** it to move the tile. It's saved when I lift my finger.
    - **Let go** without dragging to open a menu on the tile:
        - **Change LED number(s):** edit the tile's LEDs as a comma-separated list. The tile keeps its id and position. An LED that's already in another tile, or Hidden, is refused with a message; nothing is moved or swapped. Snackbar with Undo.
        - **Delete tile:** its LEDs become unmapped. Snackbar with Undo. The current LED doesn't change.
- Redoing an already-mapped LED replaces its old assignment. Remove it from its previous hold, and delete that hold if it's left with no LEDs.
- Zoom is available for precise taps. Existing holds are drawn as tiles labeled with their LED numbers (§7.3).
- Every action saves immediately (atomic write).

### 12.2 Review step
- Summary, e.g. "550 LEDs · 380 holds (402 LEDs) · 148 hidden · 0 unmapped". Tapping "unmapped" jumps to the first unmapped LED in Map LEDs.
- **Tap a hold:** its LEDs light up (slot 4). A sheet shows its id, LEDs and position, with actions: **Move** (drag the marker), **Remove LED from hold**, **Delete hold**.
- **"Light all holds"** toggle: every hold's LEDs light in dim white (slot 5). This is the fastest way to spot mistakes on the real wall, and it exercises chunking.
- **Export layout:** saves the JSON through the share sheet or file picker. Once I'm happy, I'll copy it into `app/src/main/assets/wall_layout.json` so a fresh install starts with it.

### 12.3 Import alternatives
- **JSON:** import a `wall_layout.json`. It's validated first and replaces the layout after I confirm.
- **CSV** (e.g. Google Sheets → File → Download → CSV):
  ```
  led,x_in,y_in,hold
  0,4,6,
  1,,,
  7,12,22,A
  8,12,22,A
  ```
    - `x_in` and `y_in` are inches on the unfolded wall (§5): x from the left edge, y up along the surface from the bottom of the kicker. A main-panel hold's y is 15 plus its distance up the main panel from the fold.
    - Blank x/y means the LED is hidden.
    - Rows that share a `hold` key form one hold, positioned at the first such row's x/y. A blank key means the LED is its own hold.
    - LED indices missing from the file are unmapped. `ledCount` = highest `led` + 1, unless I change it.
    - For regular grids, x/y can come from sheet formulas. Measured positions are less exact than tap-mapping, so check them in Review and drag markers to fix them.

---

## 13. Backup & data safety
- **Settings → Export backup** saves a zip named `homewall-backup-YYYYMMDD-HHmm.zip` through the system "Create document" picker. It contains `manifest.json` (schema version, app version, date), `routes.json`, `wall_layout.json`, `wall_photo.jpg` and `wall_photo.json`. The straightened image isn't included; it's regenerated on import.
- **Import backup:** validate the zip, confirm "Replace all routes and wall setup?", replace everything, then regenerate the straightened image.
- Leave Android Auto Backup on (the default) as a second safety net.

---

## 14. Testing

Required unit tests:
- **Protocol encoder:** the §11.5 golden vectors. Chunking at 0, 1, max and max+1 entries, at MTU 23 and 517.
- **Route → frame:** paired LEDs; holds missing from the layout.
- **Editor rules:** the start and finish limits, toggling, and undo/redo, including redo being cleared by a new change.
- **Sort and filter:** all four orders, projects last, range edges, the include-projects switch.
- **Layout:** validation (including panels), CSV import, the placeholder generator, panel resizing (holds keep their relative positions; holds on higher panels shift), and new-hold id assignment when the lowest LED index is already taken (§6.1).
- **Rectifier:** each panel's quad corners map exactly to its band's corners.
- **WallTransform:** wall → screen → wall round trip, with and without zoom.
- **LedSync:** a fast burst of frames sends only the latest after the in-flight one finishes; a new session resends; an identical frame isn't resent.

Keep a short manual test checklist for each milestone in `PROGRESS.md`.

---

## 15. Milestones

**M1 — Skeleton**
- Project setup, theme, navigation with every destination stubbed.
- `AppContainer`, `SettingsRepository`, `LogBuffer`, `FakeLedController`.
- ✅ The app runs on the AVD and every screen is reachable.

**M2 — Wall view**
- Layout model with panels, validation and the two-panel placeholder.
- Photo take/pick with EXIF handling, the six-handle Align step, and `WallRectifier`.
- `WallView`: transform, markers, rings, hit testing, zoom.
- ✅ Aligning a real photo of my wall produces a straight, front-on image of both the kicker and the main panel, with the fold where it belongs.
- ✅ Placeholder markers sit on an even grid, and taps hit the right hold at every zoom level.

**M3 — Routes**
- Room, and Route List with sort, filter, empty states and the placeholder banner.
- Route Editor (create, edit, delete) with the rules and undo/redo, plus the Save dialog.
- Route Display pager.
- ✅ The full create → display → swipe → edit → delete loop works on the emulator and persists across restarts.

**M4 — ESP32 firmware**
- The single-file sketch `firmware/home_wall/home_wall.ino` per §11.6, ready to copy, paste and upload. Put the nRF Connect test steps in `PROGRESS.md`.
- ✅ Writing the §11.5 vectors from nRF Connect lights the right LEDs in the right colors and turns them off again.
- ✅ After a disconnect the LEDs stay lit and the board advertises again.

**M5 — LEDs in the app**
- Protocol encoder and tests, `LedSync`, `DriveLeds` wired per §9.2, the LED preview overlay.
- `BleLedController`, the Connect button and device picker, auto-reconnect.
- ✅ On the emulator (Simulated), the LED preview matches the rings while I swipe fast.
- ✅ On the tablet with the ESP32, the wall follows swipes and edits with no visible lag and recovers after the ESP32 is power-cycled.

**M6 — Wall Setup mapping**
- The Map LEDs and Review steps, plus JSON/CSV import and export.
- ✅ I can map all 550 LEDs on the real wall end to end, "Light all holds" shows every hold, and an exported JSON reloads identically.

**M7 — Polish & safety**
- Backup export/import, keep screen on, landscape layouts, list scroll restore.
- Edge cases: no photo, layout errors, Bluetooth turned off mid-session, ESP32 out of range.
- ✅ Every screen is usable in both orientations, and a backup round-trips on the tablet.

---

## 16. Decisions already made (I can override any of these)
- Portrait-first, but landscape must work.
- Wall coordinates are inches on the unfolded wall surface, origin at the bottom-left of the kicker. Positions survive retaking the photo.
- The photo is straightened once per alignment, panel by panel. Every screen shows that flat, unfolded image.
- Panel sizes are rough (about ±3 in) and stay editable. Changing them rescales the holds instead of invalidating the mapping.
- A hold's id is its lowest LED index at creation (or the lowest unused id ≥ `ledCount` if that's taken), and ids never change.
- Tapping an active hold always removes it. The editor's default role is Start, with no auto-switching.
- Saving needs at least one hold. Missing a start or finish hold only shows a warning.
- A new route's grade defaults to Project. Projects sort after graded routes.
- Route List keeps the last route lit. There's an explicit Lights off button.
- The LEDs switch at the halfway point of a swipe (`currentPage`) for a real-time feel.
- Delete lives in Edit.
- BLE uses write-with-response plus latest-wins conflation. Colors are palette slots, and brightness is applied app-side.
- The firmware keeps the last frame lit when the app disconnects.
- In-app light-and-tap mapping is the primary way to build the layout. CSV and JSON are the alternatives.
- In Map LEDs, pressing and holding a tile drags it; letting go opens a menu to change its LED numbers or delete it. Changing to an LED that is already placed or Hidden is refused.
- The LEDs known to be behind the wall (§12.1) start Hidden when mapping starts, and auto-advance skips Hidden LEDs. They stay editable like any other LED.
- Manual DI and the platform BLE API. No Hilt and no BLE library.
- The firmware is one self-contained Arduino sketch (`firmware/home_wall/home_wall.ino`) that I copy, paste and upload. Its only extra library is FastLED; BLE uses the library bundled with the ESP32 board package. It's written in its own milestone (M4).
- The power cap is done in the firmware with a simple estimate, not FastLED's 5 V-based power model.

## 17. Open questions (ask me; don't guess)
None right now. Add new ones here as they come up.
