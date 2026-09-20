# Agent instructions

Short reference for coding agents (Claude Code, Cursor, etc.) working on this repo. See [`README.md`](README.md) for the prose version.

## Build & verify

```sh
./gradlew :app:assembleDebug          # primary build target
./gradlew :app:installDebug           # build + install on device
./gradlew :shared:testAndroidHostTest # unit tests (matches CI); :shared:test also works
./gradlew clean                       # rare; only if state is wrong
```

Java **21+** required (CI and the dev container run Temurin 25). The Gradle wrapper handles everything else. If the host has no Android SDK / `ANDROID_HOME`, build inside the dev container — `.devcontainer/` mirrors CI (JDK + SDK packages) and the project wrapper supplies Gradle.

`commonTest` has real coverage now: DTO parsing, `AttachmentUrl`, `LayoutPreferences`, `MemoRepository` (streaming upload against a Ktor `MockEngine` via `FakeMemoDao`), the offline write queue (`OfflineQueueTest` — enqueue/replay, poison cap, orphan sweep), and SSO sign-in (`PkceTest` against the RFC 7636 vector, `LoginViewModelSsoTest` for discovery/authorization/redirect handling). Add new tests next to these. **Don't add a test without a real assertion** — empty scaffolding tests get committed and never get filled in.

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
- **Offline queue semantics in `MemoRepository.syncPending()` are deliberate, don't "simplify" them:** a queued action is dropped as poison only after `MAX_SYNC_ATTEMPTS` replay rounds that *reached the server* (5xx). Network failures never count toward the cap — offline stretches must not eat the user's queued writes. The orphan temp-row sweep only touches `memos/local-*` rows older than `ORPHAN_MIN_AGE_MS` so it can't race an in-flight `create()` into a duplicate.
- **Queue flush triggers** are the `ConnectivityManager` default-network callback in `MainActivity` (registered `onStart`/`onStop`; fires immediately on registration when a network is up, so it covers app-open too) plus the cold-start flush in `MemoListViewModel.init`. Don't re-add an `onResume` flush — it's redundant.
- **SSO sign-in ends by minting a personal access token, and that's deliberate.** `AuthService.SignIn` hands back a short-lived session token plus a refresh token in an HttpOnly cookie; `SsoAuthenticator` spends that token on `CreatePersonalAccessToken` (`expiresInDays = 0`) and stores the PAT instead. Don't "modernize" this into storing the session token — that would require a cookie jar, a refresh-on-401 interceptor, and a new auth-expiry bucket in `RetryClassification`, all to reach the state a PAT is already in. Everything downstream of `AuthStore` is intentionally unable to tell how the user signed in.
- **The SSO redirect has two arrival paths and both are load-bearing.** `AuthTabIntent` returns it as an activity result (the happy path), but `AuthTabIntent.build()` deliberately leaves the intent interpretable as a plain Custom Tab, and browsers without Auth Tab support take that route — the redirect then resolves through MainActivity's manifest intent-filter and arrives via `onNewIntent`. Don't delete the intent-filter as "unused"; `LoginViewModel.onSsoCancelled` is non-destructive for the same reason (both paths can fire for one attempt).
- **`nu.bacher.memos://oauth2redirect` is written in three places** — `SsoAuthenticator.REDIRECT_SCHEME`/`REDIRECT_HOST`, the `<data>` element on MainActivity, and the user's identity provider. The manifest can't read the Kotlin const; if you change one, change all three.
- **No raw exception messages in UI state.** ViewModels expose typed errors (`MemoEditViewModel.EditError`, mapped via `classify()`); screens map those to string resources. The list screen's `friendlyErrorMessage` is the same pattern.
- **expect/actual classes** are still in beta. The `-Xexpect-actual-classes` flag in `:shared` silences the warning. Don't re-add the warning suppression elsewhere.
- **gradlew is tracked as executable in git** (`100755`). If you regenerate the wrapper on Windows, re-set the bit with `git update-index --chmod=+x gradlew`.

## Architecture

- DI: Koin. `MemosApp.onCreate()` starts Koin with `commonModule() + androidPlatformModule() + appModule()`.
- HTTP: Ktor client. Per-request host rewrite + bearer token via `DefaultRequest` reading from `AuthStore` on every request — don't add a long-lived auth header at install time.
- Persistence: Room KMP. Single `reminders` table; reminders are ephemeral.
- Background: `AlarmManager` (no WorkManager). Alarms don't survive reboot — `BootReceiver` re-arms them.
- UI: Jetpack Compose + Navigation Compose. ViewModels live in `:shared` (`androidx.lifecycle.ViewModel`, the multiplatform variant). Composables resolve them via `koinViewModel()`.
- Auth: server URL + bearer token in `AuthStore`, encrypted at rest. Two ways to get the token — pasted by the user, or minted by `SsoAuthenticator` at the end of an OAuth2/SSO flow (`GET identity-providers` → Auth Tab → `POST auth/signin` → `POST personalAccessTokens`). PKCE material comes from `Pkce`, which delegates to stdlib Base64 and a platform `MessageDigest`/`SecureRandom` via `expect fun`s in `Crypto.kt`.
- Share: `ShareReceiverActivity` accepts `text/plain`, `image/*`, and `video/*` (`SEND` + `SEND_MULTIPLE`). Streams are uploaded as attachments before the memo is created, so image shares require being logged in — text falls back to a new-memo handoff, attachments show a "sign in first" toast.

## Don't

- Don't add tests with no assertions just to fill the `commonTest` directory.
- Don't add `// removed for X` comments, `@Deprecated` shims, or compatibility aliases. Delete cleanly.
- Don't reintroduce Hilt, KAPT, Retrofit, OkHttp interceptors, or SQLDelight — they were each evaluated and removed.
- Don't put platform-detection (`if (Build.VERSION.SDK_INT...)`) checks in `:shared` for sub-`minSdk` (34) APIs. `minSdk = 34`; anything below that doesn't exist for us.
- Don't commit a keystore. Signing config (when added) reads from env vars; the keystore lives outside the repo.
