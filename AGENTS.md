# Agent instructions

Short reference for coding agents (Claude Code, Cursor, etc.) working on this repo. See [`README.md`](README.md) for the prose version.

## Build & verify

```sh
./gradlew :app:assembleDebug          # primary build target
./gradlew :app:installDebug           # build + install on device
./gradlew :shared:testAndroidHostTest # unit tests (matches CI); :shared:test also works
./gradlew clean                       # rare; only if state is wrong
```

Java **21** required (Temurin or Android Studio JBR). The Gradle wrapper handles everything else.

`commonTest` has real coverage now: DTO parsing, `AttachmentUrl`, `LayoutPreferences`, and `MemoRepository` (streaming upload against a Ktor `MockEngine` via `FakeMemoDao`). Add new tests next to these. **Don't add a test without a real assertion** — empty scaffolding tests get committed and never get filled in.

Release signing reads `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` from the env. Play Publisher reads `ANDROID_PUBLISHER_CREDENTIALS_PATH` (service-account JSON). If any keystore var is missing, the release build silently falls back to unsigned — check the env, don't add a default keystore.

## Module split (don't break it)

- **`:shared`** uses `com.android.kotlin.multiplatform.library`. Platform-agnostic logic + Android-specific platform bindings *that don't reference `:app` types*.
- **`:app`** uses `com.android.application`. UI, Activities, Receivers, resources, the Glance widget, and `AlarmScheduler` (because it references `AlarmReceiver`).

If you find yourself wanting to put a Compose `@Composable`, an `Activity`, a `BroadcastReceiver`, or anything that imports from `androidx.compose.*` into `:shared`, stop — that belongs in `:app`. If you need to put something into `:shared` that uses `Context`, it's fine as long as it doesn't reach into manifest-declared `:app` classes.

## Conventions and gotchas

- **Single source of truth for versions** is `gradle/libs.versions.toml`. Never put a literal version string in a `build.gradle.kts`.
- **Don't apply `org.jetbrains.kotlin.android`** to `:app` — AGP 9 has built-in Kotlin support and rejects the explicit plugin.
- **Don't put `debugImplementation` or `platform(...)` inside `kotlin { sourceSets { androidMain.dependencies { } } }`** — the legacy `dependencies { }` top-level block is correct for variant-specific and BOM deps in this layout.
- **Koin's `viewModelOf(::Foo)` / `singleOf(::Foo)` do NOT honor Kotlin default parameters.** If a class has a default value on any constructor param, bind it with the explicit lambda form: `single { Foo(get(), get()) }`. There's a comment in `CommonModule.kt` about exactly this — don't undo it.
- **Room `@Database`** uses `exportSchema = false` because the Room Gradle plugin doesn't currently forward `schemaDirectory` into the `kspAndroid` configuration in KMP. If you ever care about migrations, revisit this.
- **The destructive-migration fallback** in `MemosDatabaseFactory.kt` is deliberate — existing installs may carry the legacy Room v2 schema from before the SQLDelight detour, and a downgrade-throw would crash on first launch.
- **expect/actual classes** are still in beta. The `-Xexpect-actual-classes` flag in `:shared` silences the warning. Don't re-add the warning suppression elsewhere.
- **gradlew is tracked as executable in git** (`100755`). If you regenerate the wrapper on Windows, re-set the bit with `git update-index --chmod=+x gradlew`.

## Architecture

- DI: Koin. `MemosApp.onCreate()` starts Koin with `commonModule() + androidPlatformModule() + appModule()`.
- HTTP: Ktor client. Per-request host rewrite + bearer token via `DefaultRequest` reading from `AuthStore` on every request — don't add a long-lived auth header at install time.
- Persistence: Room KMP. Single `reminders` table; reminders are ephemeral.
- Background: `AlarmManager` (no WorkManager). Alarms don't survive reboot — `BootReceiver` re-arms them.
- UI: Jetpack Compose + Navigation Compose. ViewModels live in `:shared` (`androidx.lifecycle.ViewModel`, the multiplatform variant). Composables resolve them via `koinViewModel()`.

## Don't

- Don't add tests with no assertions just to fill the `commonTest` directory.
- Don't add `// removed for X` comments, `@Deprecated` shims, or compatibility aliases. Delete cleanly.
- Don't reintroduce Hilt, KAPT, Retrofit, OkHttp interceptors, or SQLDelight — they were each evaluated and removed.
- Don't put platform-detection (`if (Build.VERSION.SDK_INT...)`) checks in `:shared` for sub-`minSdk` (34) APIs. `minSdk = 34`; anything below that doesn't exist for us.
- Don't commit a keystore. Signing config (when added) reads from env vars; the keystore lives outside the repo.
