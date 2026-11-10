# Progress

## Milestones

| Milestone | Status |
|---|---|
| M1 Skeleton | **In progress.** Android project created; theme, navigation, `AppContainer`, `SettingsRepository`, `LogBuffer` and `FakeLedController` still to do. |
| M2 Wall view | Not started |
| M3 Routes | Not started |
| M4 ESP32 firmware | Not started |
| M5 LEDs in the app | Not started |
| M6 Wall Setup mapping | Not started |
| M7 Polish & safety | Not started |

## Toolchain

| | Version |
|---|---|
| Package / applicationId | `com.kreyenhagen.homewall` |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin, so no `kotlin-android` plugin) |
| Gradle | 9.9.0 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 33 |
| JDK (Gradle) | Android Studio's bundled JBR |

## Deviations from NORTH_STAR.md

None.

## To do by hand

- [ ] **Update Android Studio to Rabbit 2 (2026.2.2) or newer.** The installed Narwhal 3 (2025.1.3) only supports AGP up to 8.13 and can't sync this project.
- [ ] After updating, open the `HomeWall` folder. If Android Studio offers to "Load Gradle Project", accept. If it still treats the folder as a plain project, close it, delete the `.idea` folder, and open it again.
