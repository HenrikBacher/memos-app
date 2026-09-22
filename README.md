# Memos Android

Android client for [memos](https://usememos.com) — a self-hosted note-taking server. Sign in with a server URL + access token, then browse, search, write, and archive memos, attach images and video, set time-based reminders, share content in from other apps, and open a quick note from a home-screen widget.

## Features

- **Offline-first** — the Room cache is the UI's source of truth, so the list renders instantly on cold start. Creates/updates/deletes apply optimistically and queue in `pending_actions` for replay when the network returns; queued memos show a "sync pending" badge.
- **Paging** — memos stream from the local cache via Paging 3, refilled from the server by a `RemoteMediator`.
- **Search & tags** — server-side filter query with an optional tag filter.
- **Markdown** — memos render with `multiplatform-markdown-renderer`; links open through a `UriHandler` that reports failures instead of swallowing them.
- **Attachments** — image/video upload streamed to the server before the memo is created, with an in-app image viewer (Coil).
- **Reminders** — per-memo time reminders backed by `AlarmManager` + notifications, with quick presets (tomorrow, this weekend, next week) and re-arming after reboot.
- **Share target** — accepts `text/plain`, `image/*`, and `video/*` via `SEND` and `SEND_MULTIPLE`.
- **Widget** — Glance app widget listing recent memos; tap a row to open it, or the `+` to start a new one.
- **Appearance** — light/dark/system mode, dynamic color opt-in, and grid/list layout, all persisted across sign-out.
- **Token encrypted at rest** — the access token is sealed with Tink AEAD under an Android Keystore master key; a failed decrypt sends the user back to login rather than crashing.

## Architecture

Two-module Kotlin Multiplatform project, Android-only target. Layout follows the [Android KMP guide](https://developer.android.com/kotlin/multiplatform/plugin):

- **`:shared`** — KMP library using `com.android.kotlin.multiplatform.library`. Holds platform-agnostic code (DTOs, Ktor `MemosApi`, Room database / DAOs / entities, repositories, ViewModels, Koin `commonModule`) plus Android-specific platform glue (`OkHttp` engine binding, Room `databaseBuilder`, `SharedPreferencesSettings` binding, Tink cipher).
- **`:app`** — Standard `com.android.application`. Holds Activities, broadcast receivers, Compose UI, Glance widget, notifications, resources, manifest, and `AlarmScheduler` (which references `AlarmReceiver`, hence app-side). Depends on `:shared` via `project(":shared")`.

### Stack

| Concern | Library |
|---|---|
| Networking | Ktor client (`okhttp` engine on Android) |
| Persistence | Room (KMP) via KSP |
| Paging | Paging 3 (`paging-common` + `room-paging` + `paging-compose`) |
| Settings | `multiplatform-settings` (SharedPreferences backend) |
| Token encryption | Tink (`tink-android`, Android Keystore master key) |
| DI | Koin (`commonModule` + `androidPlatformModule` + `appModule`) |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Markdown | `multiplatform-markdown-renderer-m3` |
| Images | Coil 3 (`coil-network-ktor3`) |
| Licenses screen | AboutLibraries |
| Widget | Glance AppWidget |
| Background work | `AlarmManager` (no WorkManager) |
| Serialization | `kotlinx.serialization` |

### Versions

This README deliberately names no version numbers — they move on every Dependabot PR. Look them up where they're actually declared:

| What | Where |
|---|---|
| Kotlin, AGP, KSP, and every library | [`gradle/libs.versions.toml`](gradle/libs.versions.toml) |
| `compileSdk`, `targetSdk`, `minSdk` | [`app/build.gradle.kts`](app/build.gradle.kts) (`:shared` mirrors compileSdk/minSdk) |
| JVM toolchain | `jvmToolchain(...)` in both modules' `build.gradle.kts` |
| Gradle | `gradle/wrapper/gradle-wrapper.properties` |
| CI JDK + SDK packages | [`.github/workflows/build.yml`](.github/workflows/build.yml) |

`./gradlew dependencyUpdates` reports newer stable releases (pre-release candidates are filtered out in the root build script); Dependabot also opens PRs against the catalog and the workflows.

## Requirements

- **A JDK matching the `jvmToolchain(...)` declared in the build files.** The Foojay toolchain resolver in `settings.gradle.kts` can provision it if your local JDK doesn't match.
- **Android SDK** with the platform and build-tools matching `compileSdk` — the exact package IDs CI installs are in the `setup-android` step of the workflow.
- Android Studio (or IntelliJ) recent enough for the AGP major version in use, for IDE work.

The Gradle wrapper at `./gradlew` pins everything else. If the host has no Android SDK, `.devcontainer/` mirrors CI (JDK + SDK packages).

## Building

```sh
./gradlew :app:assembleDebug            # debug APK at app/build/outputs/apk/debug/
./gradlew :app:assembleRelease          # release APK — minified + shrunk; signed only if release env vars are set
./gradlew :app:bundleRelease            # release AAB (what CI publishes)
./gradlew :app:installDebug             # build + install on the attached device/emulator
./gradlew :shared:testAndroidHostTest   # :shared unit tests (matches CI); :shared:test also works
./gradlew clean                         # if Gradle gets confused
```

On Windows use `gradlew.bat`. CI uses the Linux wrapper.

App version metadata comes from Gradle properties, falling back to the defaults in `app/build.gradle.kts` when unset:

```sh
./gradlew :app:bundleRelease -PappVersionCode=42 -PappVersionName=1.2.3
```

`./gradlew generatePlayIcon` rasterizes the adaptive launcher icon to the 512×512 PNG used in the Play listing under `app/src/main/play/`.

### Release signing

Release builds pick up a signing config when these env vars are set (otherwise the APK/AAB is unsigned — there is no fallback keystore):

| Variable | Meaning |
|---|---|
| `ANDROID_KEYSTORE_PATH` | Path to the `.jks` keystore file (kept outside the repo) |
| `ANDROID_KEYSTORE_PASSWORD` | Store + key password (currently shared) |
| `ANDROID_KEY_ALIAS` | Key alias inside the keystore |

For Play Publisher uploads, set `ANDROID_PUBLISHER_CREDENTIALS_PATH` to the service-account JSON, or fall back to the plugin's own `ANDROID_PUBLISHER_CREDENTIALS` env (JSON contents).

### Running on a device

After `installDebug`, the launcher carries the **Memos** icon. First launch goes to a login screen — enter your memos server URL (e.g. `https://memos.example.com`) and an access token from the memos web UI under *Settings → My Account → Access Tokens*.

The app installs as `nu.bacher.memos.debug` (debug builds get a `.debug` suffix), so you can keep it side-by-side with a release build.

## Tests

`:shared` has a `commonTest` source set (wired via `withHostTestBuilder { }`) with kotlin-test / coroutines-test / ktor mock / multiplatform-settings test deps declared. Current coverage:

- `data/api/DtosTest.kt`, `data/api/AttachmentUrlTest.kt` — DTO parsing + URL helpers
- `data/repo/MemoRepositoryTest.kt` (with `FakeMemoDao`) — streaming upload + repo behavior against a Ktor `MockEngine`
- `data/repo/OfflineQueueTest.kt` (with `FakePendingActionDao`) — enqueue/replay, poison cap, orphan sweep
- `data/repo/BuildSearchFilterTest.kt` — search/tag filter construction
- `data/settings/LayoutPreferencesTest.kt` — settings round-trip
- `util/QuickRemindersTest.kt`, `util/ReminderRelativeTest.kt` — reminder presets + relative formatting

Run with `./gradlew :shared:testAndroidHostTest`. New tests go under `shared/src/commonTest/kotlin/` — don't add empty assertion-free scaffolding.

For Android-side instrumented tests in `:app`, the standard `src/androidTest/` path works (the module uses plain `com.android.application`).

## CI

[`.github/workflows/build.yml`](.github/workflows/build.yml) triggers on pushes to `main`, PRs against `main`, and `workflow_dispatch`, with two jobs:

- **build & test** — sets up Temurin and the Android SDK packages, then runs `:shared:testAndroidHostTest` followed by `:app:assembleDebug`. Uploads the debug APK as an artifact; on test failure, uploads the HTML report too.
- **release & publish** — `needs: build`, and gated on `github.ref == 'refs/heads/main'`, so it runs after every green push to `main`. Decodes the upload keystore + Play service-account JSON from secrets, builds a signed AAB with a `git rev-list --count`-derived `versionCode`, uploads the AAB and R8 mapping as artifacts, then publishes the bundle and listing to the Play internal track. Play credentials are wiped in an `always()` step.

Note: the release job also carries `refs/tags/v*`-conditional steps (generated Play release notes, GitHub Release creation), but the workflow has no tag trigger — pushing a `v*` tag does not start it, so those steps never fire as written. Add `tags: ['v*']` to the `push` trigger if tag-driven releases are wanted.

Caching is handled by `gradle/actions/setup-gradle` (Gradle home + build cache + configuration cache). PR jobs use the cache read-only so they don't pollute it.

## Project layout

```
.
├── app/                              :app (Android application)
│   ├── build.gradle.kts              also: generatePlayIcon, play publisher config
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/nu/bacher/memos/
│       │   ├── MainActivity.kt       nav host host + connectivity-driven queue flush
│       │   ├── MemosApp.kt           Application — starts Koin
│       │   ├── di/AppModule.kt       binds AlarmScheduler
│       │   ├── reminder/             AlarmReceiver, AlarmScheduler, BootReceiver, NotificationHelper
│       │   ├── share/                ShareReceiverActivity
│       │   ├── ui/                   Compose screens (list, edit, login, settings, licenses),
│       │   │                         attachments, reminder pickers, theme, navigation, link handling
│       │   └── widget/               Glance widget + receiver
│       ├── play/                     Play listing metadata (title, descriptions, graphics)
│       └── res/
├── shared/                           :shared (KMP library)
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/nu/bacher/memos/
│       │   ├── data/
│       │   │   ├── api/              Ktor MemosApi, DTOs, attachment URL + streaming upload
│       │   │   ├── auth/             AuthStore + SecretCipher (expect)
│       │   │   ├── db/               Room MemosDatabase + Memo/Reminder/PendingAction DAOs & entities
│       │   │   ├── repo/             MemoRepository, ReminderRepository, RemoteMediator,
│       │   │   │                     paging source, pending-action payloads, retry classification
│       │   │   └── settings/         LayoutPreferences, ThemePreferences
│       │   ├── di/CommonModule.kt    Koin bindings shared across platforms
│       │   ├── reminder/time/        ReminderScheduler interface
│       │   ├── ui/                   ViewModels (Compose-free): list, edit, login, settings, root
│       │   └── util/                 Time (expect), QuickReminders, ReminderRelative
│       ├── androidMain/kotlin/nu/bacher/memos/
│       │   ├── data/auth/            TinkSecretCipher (actual)
│       │   ├── data/db/              Room database builder (Android)
│       │   ├── di/AndroidModule.kt   OkHttp engine + Settings + Database
│       │   └── util/Time.android.kt  actual currentTimeMillis
│       └── commonTest/kotlin/        unit tests + fakes
├── gradle/libs.versions.toml         single source of truth for versions
├── settings.gradle.kts               includes :app, :shared; Foojay toolchain resolver
├── AGENTS.md                         conventions + gotchas for coding agents
├── .devcontainer/                    JDK + Android SDK image mirroring CI
└── .github/
    ├── workflows/build.yml           CI
    └── dependabot.yml
```

## License

MIT — see [`LICENSE`](LICENSE).
