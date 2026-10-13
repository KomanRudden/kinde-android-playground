# Kinde Android SDK Playground

An Android app that exercises every public part of the [Kinde Android SDK](../kinde-sdk-android) the way a real
app uses it, plus a regression suite that tests the SDK against a fake Kinde over HTTPS, on an emulator, and
against your real Kinde business.

It's the Android counterpart of `kinde-python-playground`: every page pairs a live result with a
"How this works" panel showing the SDK code behind it. By default it builds the SDK **from the checkout next to
it**, so every run tests the code on disk.

| Page | What it exercises |
| --- | --- |
| Dashboard | Configuration (client ID masked) and setup checks: OIDC discovery, SDK endpoints vs discovery, JWKS vs the keys the SDK cached, redirect scheme, browser |
| Sign-in lab | `login()` / `register()` / `createOrg()` / `handleInvitation()` / `logout()` with every option, `GrantType` PKCE vs default, domain and client ID overrides, the real last authorization request |
| Profile | `getUserDetails()`, `getUser()`, `getUserProfileV2()`, the main-thread guard |
| Tokens | Access, ID and refresh tokens (masked), expiry countdown, refresh schedule, `refreshState()`, `clearCache()` |
| Claims | `getClaim()` for either token, every claim decoded |
| Permissions & roles | Token claims and the Account API side by side, `ApiOptions(forceApi, useCache)` |
| Feature flags | `getFlag()`, `getBooleanFlag` / `getStringFlag` / `getIntegerFlag`, `getAllFlags()`, token vs API |
| Billing & entitlements | `getEntitlements()`, `getEntitlement(key)`, `register(pricingTableKey)` |
| Organizations | `getOrganization()`, `getUserOrganizations()`, switching org, creating one |
| Background access | `KindeClient.getInstance(context)` from WorkManager, an OkHttp interceptor and a plain thread |
| Activities & lifecycle | One client across activities, recreation and a second activity with its own facade |
| Management API | `getUsers()` and guarded `createUser()` through the SDK |
| Security audit | Token storage (incl. decrypting it with only the domain name), backups, PKCE/nonce of the last sign-in, logcat scan, forged-redirect probe |
| Regression | On-device self-checks and live checks, plus the host suite results pushed by `scripts/regression.sh` |
| SDK events | Every `SDKListener` callback with its thread; silent token rotations |

## 1. Emulator

Use **`Kinde_Playground_API36`**: Pixel 7, Android 16 (API 36), *Google APIs* image (arm64 on Apple Silicon).
Google APIs images ship Chrome, which sign-in needs for Custom Tabs; they have no Play Store, so `adb root` works
if you want to inspect app data. The script creates the AVD the first time (from the system image you already
have; it downloads it otherwise) and boots it:

```bash
./scripts/emulator.sh
```

Options: `--wipe` for a clean data partition, `--headless` for no window. It waits for boot and turns off
animations (they make UI tests flaky). You can also start it from Android Studio's Device Manager once created.

Don't use `Kinde_Root_API36` for this: its data partition is a temp file, so the signed-in session is lost on
every boot. `Pixel_9_Pro_XL` works but is a Play Store image (no `adb root`).

## 2. Set up Kinde

1. **Android application.** *Settings → Applications → Add application → Android*. On *Details*:
   - *Allowed callback URLs*: `com.kinde.playground://kinde_callback`
   - *Allowed logout redirect URLs*: `com.kinde.playground://kinde_logout`
   - Copy the domain and client ID.
2. **Refresh tokens.** The app requests the `offline` scope; make sure refresh tokens are enabled for the app.
3. **Optional, for richer pages:** create the permission `read:reports`, the role `admin`, the feature flags
   `beta_dashboard` (boolean), `theme` (string) and `max_items` (integer), assign them to your user's org, and add
   roles, permissions and flags to the access token (*application → Tokens*). Billing with a `pro_reports`
   feature enables the entitlement examples. Change the keys with `PLAYGROUND_DEMO_*`.
4. **Optional, host live tests:** a *Machine to machine* app authorized for the Kinde Management API with
   `read:application_redirect_uris`, `read:application_logout_uris`, `read:permissions`, `read:roles`,
   `read:environment_feature_flags`, `read:users`. Its credentials stay on your Mac; they are never compiled
   into the APK.

## 3. Configure, build, run

```bash
cp .env.example .env
```

Fill in `KINDE_DOMAIN` and `KINDE_CLIENT_ID` (and the M2M values for the live tests). Values are compiled in
by `app/build.gradle`, so **rebuild after editing `.env`**. Real environment variables win over the file.

```bash
./gradlew :app:installDebug
```

Then open *Kinde Playground* on the emulator, go to *Sign-in lab* and tap `login()`. Requirements: JDK 17,
Android SDK with platform 36 (`ANDROID_HOME`, default `~/Library/Android/sdk`); `local.properties` is created by
the scripts if missing.

| Variable | Purpose |
| --- | --- |
| `KINDE_DOMAIN`, `KINDE_CLIENT_ID`, `KINDE_AUDIENCE` | Your Android application. The domain may include `https://`. |
| `KINDE_REDIRECT_SCHEME` | AppAuth's redirect scheme (`appAuthRedirectScheme`). Must match the URLs registered in Kinde. |
| `PLAYGROUND_CONFIG_MODE` | `programmatic` (`KindeClient.getInstance(context, KindeConfig(...))` in `Application.onCreate`) or `manifest` (meta-data). |
| `PLAYGROUND_SCOPES` | Scopes passed to `KindeSDK`. |
| `PLAYGROUND_DEBUG` | Token reveal button. Never in a build you share. |
| `PLAYGROUND_ALLOW_MUTATIONS` | Enables `createUser()`. Use a test business. |
| `PLAYGROUND_DEMO_*` | Keys the demo checks use. |
| `KINDE_MANAGEMENT_CLIENT_ID` / `_SECRET` | Host-only, for `LiveKindeTest`. |
| `KINDE_MANAGEMENT_DOMAIN` | Host-only. Your `your-business.kinde.com` host. Required with a custom domain: Kinde only accepts `https://<subdomain>.kinde.com/api` as the Management API audience. |

Build against a published SDK instead of the checkout: `./gradlew :app:installDebug -PkindeSdkVersion=2.2.0`
(or set it in `gradle.properties`). Point at another checkout with `-PkindeSdkPath=...`.

## 4. Test it

```bash
./scripts/regression.sh            # everything (needs the emulator running)
./scripts/regression.sh --quick    # JVM suites only, ~2 minutes, no emulator
./scripts/regression.sh --live     # also the host live tests against your Kinde business
./scripts/live-test.sh             # live checks on the emulator with your signed-in session
```

`regression.sh` runs, in order:

1. **SDK unit tests**: `kinde-sdk-android`'s own Robolectric suite against the checkout.
2. **SDK contract suite (JVM)**, `app/src/test`: the real, unmodified SDK talking to `FakeKinde`, a fake Kinde
   business over real HTTPS on localhost (JWKS, token endpoint with rotating refresh tokens, profile endpoints,
   Account API, Management endpoints). It covers session restore and signature verification, every
   authorize parameter of `login` / `register` / `createOrg` / invitations, the code exchange, logout, the
   refresh schedule, 401/403 handling, concurrent refreshes, caching, flags, entitlements, storage and
   configuration. It uses public API only, so it also runs against a published AAR.
3. **Builds**: debug and `minified` (R8 with only the SDK's consumer rules).
4. **Instrumented tests** on the emulator: every page renders, storage and the IV race on a real runtime, one
   client across recreation and activities, the on-device self-checks, and the security audit with the
   forged-redirect probe.

It writes `reports/regression.html` / `.json` and copies the JSON into the app, so *Regression → Host suites*
shows the same results on the device.

**Known issues.** Tests marked `@KnownIssue` assert the *correct* behaviour for an SDK defect found in the
review below. While the defect exists they're reported as *known* (the suite stays green); when the SDK is fixed
they fail with `FIXED: remove @KnownIssue`, so the list can't go stale. Today: 23 in the JVM suite (including 3 confirmed against your live Kinde business) and 1 on the emulator.

**Live tests.** `LiveKindeTest` (host, `--live`) checks discovery against the paths the SDK hard-codes, that the
JWKS parses into the SDK's model, the real error shapes for an invalid refresh token and an invalid access
token, that the playground's callback and logout URLs are registered on your app, that the demo
permission/role/flag exist, and runs the SDK's `UsersApi` against the real Management API. `LiveChecksTest`
(emulator) runs the app's live checks with your session: tokens, profile, permissions and roles from token and
API, flags, caching, entitlements. Sign in once in the app, then `./scripts/live-test.sh` (a plain
`connectedAndroidTest` uninstalls the app and loses the session).

## SDK review findings

From reading the SDK (v2.2.0, `c2f1f9b`) and confirmed by the tests named. Severity is for apps using the SDK.

### High

1. **Tokens at rest are effectively unencrypted.** *Fixed on `kinde-sdk-android` branch `fix/secure-token-storage`.*
   In 2.2.0, `Store` derives the AES key from the domain name (padded to 16 bytes) and encrypts every value with
   one stored IV, so anyone with the prefs file (a backup, a rooted device) decrypts the session, refresh token
   included, with public information. The fix uses AES-256-GCM with a key generated in the Android Keystore, a
   fresh IV per value, and migrates 2.2.0 values on first read (verified on the emulator with a real session).
   The SDK still ships no backup rules, so apps should keep `allowBackup` off. → `StorageTest`,
   `OnDeviceSdkTest`, Security audit page.
2. **PKCE is off by default, no nonce, no ID token validation.** `authorize()` calls `setCodeVerifier(null)`
   unless `GrantType.PKCE` is passed, so `login()` sends no code challenge (RFC 8252 requires PKCE for native
   apps); `setNonce(null)` disables the nonce; and the SDK replaces AppAuth's token exchange with its own, which
   never validates the ID token (iss, aud, exp, nonce). → `LoginFlowTest`.
3. **A revoked refresh token never signs the user out.** Token endpoints answer with HTTP 400 `invalid_grant`
   (verified live), but
   `TokenRepository` only parses OAuth errors from 2xx bodies; non-2xx becomes a generic error with
   `error = null`, so the `invalid_grant` / "401" check in `getToken` never matches. The scheduled refresh then
   retries every 10 s forever, reporting `TokenException` each time. → `TokenRefreshTest`, `LiveKindeTest`.
4. **Race that corrupts stored data.** *Fixed by the same branch* (per-value IVs, one lock around storage crypto). In 2.2.0, `Store.getCipher()` creates the IV lazily without synchronisation. Two
   first writes on different threads (the JWKS callback on the main thread and the code exchange on a
   background thread, on a fresh install) each create one; the value encrypted with the losing IV no longer
   decrypts (first block garbage). If it's the JWKS, `isAuthenticated()` is false on every launch until the app's
   data is cleared. Reproduced in 25/40 JVM cold starts and on the emulator. → `StorageTest`, `OnDeviceSdkTest`.
5. **`getUsers()` cannot work.** It expects a JSON array of `User` with `Int` ids and an `Int` `userId` filter;
   Kinde returns `{code, message, users: [...], next_token}` with `kp_…` string ids, so it always returns null.
   Both Management calls also send the *user's* access token, while the Management API expects an M2M token —
   and M2M secrets must not ship in apps. Consider removing them from the mobile SDK. → `ManagementApiTest`,
   `LiveKindeTest`.

6. **Role checks from the token are always false.** Kinde's `roles` claim is an array of `{id, key, name}`
   objects (verified live), but `ClaimDelegate` reads it as a list of strings. Token-based `getRoles()` returns
   each role as raw JSON text, and `getRole("role_admin")` is false even when the user has the role. Only
   `ApiOptions(forceApi = true)` works. → `AccountApiTest`, live check *Roles from the token*.

### Medium

7. **`isAuthenticated()` checks the signature only**: not `exp`, `iss` or `aud`. An expired token reads as signed
   in until a refresh fails. → `SessionRestoreTest`.
8. **Key rotation breaks sessions.** Verification uses `keys.firstOrNull()` and ignores the token's `kid`, and the
   cached JWKS is never refreshed. When Kinde publishes a second key or rotates, users read as signed out until
   app data is cleared. → `SessionRestoreTest`; the Dashboard compares cached and live keys.
9. **Expired tokens on the Account API are never refreshed.** `callApi` refreshes and retries only on 403.
   Verified live: Kinde's OAuth profile endpoints answer an invalid token with 403 (so `getUser()` recovers),
   but the Account API answers with **400** `TOKEN_ERROR`, so `getPermissions/getRoles/flags(forceApi)` and
   `getEntitlements()` fail instead of refreshing. A standard 401 isn't handled either, and any genuine 403
   (missing scope) triggers a pointless refresh. → `AccountApiTest`, `LiveKindeTest`.
10. **Scheduled refreshes don't call `onNewToken`** (`notifyListener = false`), so apps that keep the token from
   the callback hold a stale one. The Events page shows these silent rotations. → `TokenRefreshTest`.
11. **SDK 2.2.0 forces Compose apps onto compileSdk 37.** `lifecycle-process:2.11.0`'s version constraints pull
    `lifecycle-runtime-compose` (a Compose UI dependency) to 2.11.0, which requires compileSdk 37
    (`minCompileSdk=37` in its AAR metadata). A Compose app on compileSdk 36 fails `checkDebugAarMetadata` as
    soon as it adds the SDK. The playground pins `lifecycle-runtime-compose` to 2.10.0 (see `app/build.gradle`).
12. **Logout doesn't revoke the refresh token**, so it stays valid at Kinde if the end-session page fails or the
    logout completes locally. → `LogoutTest`.
13. **Silent failure on launch.** With a saved session but no cached keys, a failed JWKS fetch (e.g. HTTP 503)
    produces no callback at all: non-2xx responses are dropped in `onResponse`. The app gets neither
    `onNewToken` nor `onLogout`. A cold start also fetches the JWKS twice. → `SessionRestoreTest`.

### Low

14. **Flag inconsistencies.** On the token path a flag of the wrong type returns null and ignores the default;
    the API path returns the default. Integer flags from `getAllFlags(forceApi)` are `Double` (Gson) but `Int`
    from the token. → `AccountApiTest`.
15. **Model gaps.** `UserProfileV2` drops `email`, `email_verified`, `picture`, `sub`, `preferred_username`;
    `EntitlementsMetadata.nextPageStartingAfter` is a non-null `String` that is null on the last page (NPE for
    Kotlin callers); `getEntitlements()` can't paginate; `getClaim()` returns every value as a string.
16. **Threading is undocumented.** `onNewToken` after sign-in arrives on a background thread; network methods
    return null (and report `WrongThreadException`) on the main thread. → `LoginFlowTest`.
17. **No public refresh method**, so apps can't refresh after the app was backgrounded past expiry (the schedule
    pauses in the background) other than by making an API call that gets a 403.
18. **Consumer ProGuard rules only keep the entitlements models.** `Keys`, `UserProfile`, the Account API
    responses and the request models rely on Gson's and Retrofit's own rules. The `minified` build type checks
    this: `./gradlew :app:installMinified`, then run the setup checks and sign in. Not yet confirmed either way.

## How it's built

```
app/src/main/java/com/kinde/playground/
  PlaygroundApp.kt        Application: configures KindeClient (programmatic mode) before any activity
  config/                 Settings from .env (via BuildConfig), validated with the SDK's rules
  core/KindeHub.kt        One shared SDKListener → StateFlows; the event log; access to the client
  core/SdkLog.kt          Reads this process's logcat to show the HTTP calls the SDK made (debug SDK builds)
  core/Redact.kt, Jwt.kt  Masking/redaction; JWT decoding for display
  core/TokenWatcher.kt    Notices token rotations the listener wasn't told about
  checks/                 Setup, live, security and on-device self-checks; host report model
  ui/                     Compose pages, MainActivity (facade per activity), SecondActivity
  work/                   WorkManager worker and an OkHttp interceptor using KindeClient
app/src/test/             SDK contract suite (FakeKinde over HTTPS), playground unit tests, LiveKindeTest
app/src/androidTest/      Pages, on-device SDK tests, LiveChecksTest
app/src/sharedTest/       @KnownIssue, shared by both
scripts/                  emulator.sh, regression.sh, live-test.sh, junit_report.py
```

Patterns worth copying into your own app:

- **Configure in `Application.onCreate`**, construct a `KindeSDK` in every activity's `onCreate`, and pass all of
  them **one shared `SDKListener`**. Use `KindeClient.getInstance(context)` where there's no activity.
- **Always `login(type = GrantType.PKCE)`** until PKCE is the default.
- **Hop to the main thread in listener callbacks**, and call network methods from a background dispatcher.
- **Turn off backups** for the SDK's prefs (`allowBackup="false"` or data-extraction rules), as this app does.
- **Read the token when you need it** (`getToken()`), not once from `onNewToken`.
- **Wrap `forceApi` calls in try/catch**: they throw, while the token versions return empty results.
- **Never show raw tokens or API errors.** The playground shows exception types and HTTP status only.

## Troubleshooting

- **App says "not configured"**: `.env` is missing or `KINDE_DOMAIN` / `KINDE_CLIENT_ID` are blank. Rebuild after editing.
- **Browser shows "invalid redirect URI"**: register `com.kinde.playground://kinde_callback` (and the logout URL) exactly.
- **Sign-in returns but you're still signed out**: check *SDK events* for `TokenException`, and *Security audit*.
  If the stored keys are corrupted (finding 4), clear app data: `adb shell pm clear com.kinde.playground`.
- **`checkDebugAarMetadata` fails mentioning compileSdk 37**: see finding 11.
- **`M2M token request: invalid_request … not been whitelisted`**: with a custom domain, set `KINDE_MANAGEMENT_DOMAIN` to your `*.kinde.com` host (your hosted login page shows it). If it still fails, the M2M app isn't authorized for the Kinde Management API: *Settings → APIs → Kinde Management API → Applications*, enable the app and grant its scopes.
- **No HTTP lines on pages**: you're building against a published AAR (`-PkindeSdkVersion`); its release build doesn't log.
