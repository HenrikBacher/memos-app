# Memos Android

Android client for [memos](https://usememos.com) — a self-hosted note-taking server. Sign in with a server URL + access token, then create, edit, and pin memos, set time-based reminders, share text from other apps, and post quick notes from a home-screen widget.

## Architecture

Two-module Kotlin Multiplatform project, Android-only target. Layout follows the [Android KMP guide](https://developer.android.com/kotlin/multiplatform/plugin):

- **`:shared`** — KMP library using `com.android.kotlin.multiplatform.library`. Holds platform-agnostic code (DTOs, Ktor `MemosApi`, Room database / DAO / entity, repositories, ViewModels, Koin `commonModule`) plus Android-specific platform glue (`OkHttp` engine binding, Room `databaseBuilder`, `SharedPreferencesSettings` binding).
- **`:app`** — Standard `com.android.application`. Holds Activities, broadcast receivers, Compose UI, Glance widget, resources, manifest, and `AlarmScheduler` (which references `AlarmReceiver`, hence app-side). Depends on `:shared` via `project(":shared")`.

### Stack

| Concern | Library |
|---|---|
| Networking | Ktor client (`okhttp` engine on Android) |
| Persistence | Room (KMP) via KSP |
| Settings | `multiplatform-settings` (SharedPreferences backend) |
| DI | Koin (`commonModule` + `androidPlatformModule` + `appModule`) |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Widget | Glance AppWidget |
| Background work | `AlarmManager` (no WorkManager) |
| Serialization | `kotlinx.serialization` |

### Versions

- Kotlin **2.3.21**, KSP **2.3.7**
- AGP **9.2.1** (Java **21**, `compileSdk` **36**, `minSdk` **34**)
- Compose BOM **2026.05.01**
- Room **2.7.2**, Ktor **3.5.0**, Koin **4.2.1**

All versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Requirements

- **JDK 21** (Temurin or Android Studio's bundled JBR)
- **Android SDK** with `platforms;android-36` and `build-tools;36.0.0`
- Android Studio Ladybug+ recommended for IDE work

The Gradle wrapper at `./gradlew` pins everything else.

## Building

```sh
./gradlew :app:assembleDebug     # debug APK at app/build/outputs/apk/debug/
./gradlew :app:assembleRelease   # release APK — no signing config yet, will be unsigned
./gradlew :app:installDebug      # build + install on the attached device/emulator
./gradlew clean                  # if Gradle gets confused
```

On Windows use `gradlew.bat`. CI uses the Linux wrapper.

### Running on a device

After `installDebug`, the launcher carries the **Memos** icon. First launch goes to a login screen — enter your memos server URL (e.g. `https://memos.example.com`) and an access token from the memos web UI under *Settings → My Account → Access Tokens*.

The app installs as `nu.bacher.memos.debug` (debug builds get a `.debug` suffix), so you can keep it side-by-side with a release build.

## Tests

There are none yet. The `commonTest` source set is wired (via `withHostTestBuilder { }` in `:shared`) so adding tests is just a matter of dropping files into `shared/src/commonTest/kotlin/`. No test dependencies are pre-declared — add them inside `commonTest.dependencies { }` when you write the first test.

For Android-side instrumented tests in `:app`, the standard `src/androidTest/` path works (the module uses plain `com.android.application`).

## CI

[`.github/workflows/build.yml`](.github/workflows/build.yml) runs `:app:assembleDebug` on every push to `main` and every PR targeting `main`. The debug APK is uploaded as an artifact on `main` builds.

Caching is handled by `gradle/actions/setup-gradle@v4` (Gradle home + build cache + configuration cache). PR jobs use the cache read-only so they don't pollute it.

## Project layout

```
.
├── app/                              :app (Android application)
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/nu/bacher/memos/
│       │   ├── MainActivity.kt
│       │   ├── MemosApp.kt           Application — starts Koin
│       │   ├── di/AppModule.kt       binds AlarmScheduler
│       │   ├── reminder/             AlarmReceiver, BootReceiver, AlarmScheduler
│       │   ├── share/                ShareReceiverActivity
│       │   ├── ui/                   Compose screens + theme + navigation
│       │   └── widget/               Glance widget
│       └── res/
├── shared/                           :shared (KMP library)
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/nu/bacher/memos/
│       │   ├── data/
│       │   │   ├── api/              Ktor MemosApi + DTOs
│       │   │   ├── auth/             AuthStore (multiplatform-settings)
│       │   │   ├── db/               Room MemosDatabase + ReminderDao + ReminderEntity
│       │   │   └── repo/             MemoRepository, ReminderRepository
│       │   ├── di/CommonModule.kt    Koin bindings shared across platforms
│       │   ├── reminder/time/        ReminderScheduler interface
│       │   ├── ui/                   ViewModels (Compose-free)
│       │   └── util/Time.kt          expect fun currentTimeMillis
│       └── androidMain/kotlin/nu/bacher/memos/
│           ├── data/db/              Room database builder (Android)
│           ├── di/AndroidModule.kt   OkHttp engine + Settings + Database
│           └── util/Time.android.kt  actual currentTimeMillis
├── gradle/libs.versions.toml         single source of truth for versions
├── settings.gradle.kts               includes :app, :shared
└── .github/workflows/build.yml       CI
```

## License

MIT — see [`LICENSE`](LICENSE).
