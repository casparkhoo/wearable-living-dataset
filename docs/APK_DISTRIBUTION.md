# Distributing the Android app

## The problem this solves

The phone app currently gets its server addresses at build time: `CLOUD_BACKEND_URL`,
`CLOUD_BROKER_URL` and the Supabase settings are compiled in from the instance settings
(`android-application/android/app/build.gradle`). An APK built for one instance only works
with that instance, so every new server needs a new build. The console now offers the app
for download together with a **setup link** that tells an installed app which server to use,
so one APK can serve any instance.

## Setup link format (stable, v1)

Two equivalent forms. Parameters are the same in both.

```
https://<console-host>/setup?backend=<url>&broker=<url>
wearablesetup://configure?backend=<url>&broker=<url>
```

| Parameter | Required | Meaning |
|-----------|----------|---------|
| `backend` | yes | Base URL of the backend API. Must start with `https://`. No trailing slash needed (strip it). |
| `broker`  | no  | MQTT broker URL, e.g. `wss://host/mqtt` (the form every deployment here serves) or `mqtts://host:8883`. If absent the app uses `wss://<backend host>/mqtt`. |

Rules:

- Each value is percent-encoded (`encodeURIComponent`), so `backend=https%3A%2F%2Fapi.example.org`.
  Decode with a standard URL query parser.
- The `https://` form is what the QR code on the console encodes. `<console-host>` is the
  console origin; the path is exactly `/setup`. The `wearablesetup://` form is offered as an
  "Open in the app" button on the same card.
- Unknown parameters must be ignored (forward compatibility). A future version may add
  `v=` (not sent in v1; absence means v1).
- The phone must accept only `https://` for `backend`, and only `wss://`, `wss://`, `mqtts://` or `ssl://` for
  `broker`; reject anything else, and show the host to the user for confirmation before
  saving it (a link is untrusted input).
- The link carries **no secrets**: no tokens, passwords or keys. The user signs in inside the app.
- Android handling: register an intent filter for scheme `wearablesetup` host `configure`, and
  optionally a verified App Link for `https` + `/setup` on the console host. Scanning the QR
  with the camera opens the https link in the browser (the console page shows the card with
  the download and "Open in the app" button) or directly in the app if App Links are verified.

The console builds the link from the same backend URL logic as the rest of the console
(`frontend/src/lib/backendUrl.ts`: `VITE_BACKEND_URL`, or a saved address) and the optional
`VITE_BROKER_URL`. Nothing is hard-coded. If the backend is not an `https://` address, no
QR code is shown. Implementation: `frontend/src/lib/appSetup.ts`.

## Console configuration (Vite env vars, set in Vercel project settings)

| Variable | Purpose |
|----------|---------|
| `VITE_APK_URL` | Download URL of the APK. If unset, the card says the app is not published yet and points to this document. |
| `VITE_APK_VERSION` | Shown as the app version (optional). |
| `VITE_APK_SHA256` | Lowercase hex SHA-256 of the APK, shown for verification (optional). |
| `VITE_APK_SIZE_BYTES` | File size in bytes, shown as KB/MB (optional). |
| `VITE_BROKER_URL` | Broker URL placed in the setup link (optional). |

The card appears under the **Get the app** tab of the signed-in console, in a collapsed
section on the sign-in screen, and `https://<console-host>/setup` opens that tab directly
(`frontend/vercel.json` rewrites `/setup` to the single-page app).

## Building and signing a release APK

Debug build for a connected phone: `scripts/local.sh` (it runs `:app:assembleDebug` in
`android-application/android` and installs with adb).

Release build to hand out, one command (bash; Git Bash on Windows):

```
scripts/release_apk.sh                                   # no default server: asked on first launch
scripts/release_apk.sh --backend https://api.example.org --broker wss://mqtt.example.org/mqtt
```

(`RELEASE_BACKEND_URL` / `RELEASE_BROKER_URL` work instead of the flags.) The script:

1. builds `assembleRelease` with version `1.0.<commit count>` (`versionName`) and the commit
   count as `versionCode`, so every commit gives a newer, installable version; a `-dirty`
   suffix means uncommitted changes under `android-application`;
2. bakes in only the server you pass. With none, the APK has no default server and shows the
   server choice on first launch; it does not read the addresses in `instance.properties`
   (the Supabase URL and publishable key still do come from there, or from `SUPABASE_URL` /
   `SUPABASE_PUBLISHABLE_KEY`);
3. zipaligns and signs with `apksigner`, then verifies the signature;
4. writes `dist/wearable-controller-<version>.apk` and `.apk.sha256` (`dist/` is gitignored)
   and prints the `VITE_APK_VERSION`, `VITE_APK_SHA256` and `VITE_APK_SIZE_BYTES` lines to
   paste into the Vercel project settings.

**Signing key.** It lives outside the repository: `%USERPROFILE%\.thesiselease.keystore`, with
its passwords in `%USERPROFILE%\.thesiselease-signing.properties` (`storePassword`,
`keyAlias`, `keyPassword`). The first run creates both with a random password (never printed).
Override the locations with `RELEASE_KEYSTORE` and `RELEASE_SIGNING_PROPERTIES`, or give the
secrets as `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. **Back both
files up**: phones refuse an update signed with a different key, so losing the key means
everyone has to uninstall and reinstall. Never commit them (`*.keystore`, `*.jks` and
`release-signing.properties` are gitignored under `android-application/android`).

What the release contains: the Supabase URL and publishable key (public by design), the default
server if you passed one, and nothing else instance specific. The script also fails if it finds
a `sb_secret_` or `service_role` string in the code. Broker logins and API tokens are never
compiled in; they are issued after sign-in and stored on the phone.

Check a file yourself: `apksigner verify --print-certs <apk>` and `sha256sum <apk>`.

## Where to host it

1. **GitHub Release asset** (preferred). Create a release, attach the APK, and use the asset
   download URL as `VITE_APK_URL`. Keeps the binary out of git history and gives download
   counts. Use a release in a repository the audience can read (the public export repo,
   not the private thesis repo).
2. **Static file in the console** (`frontend/public/app.apk`, then `VITE_APK_URL=/app.apk`).
   Simple, but the binary is then part of the repo and of every deployment. If used, keep
   it out of git (add to `.gitignore`) and copy it in only at deploy time. **Never commit an
   APK to git.**

## Caveats

- **Sideloading.** Users must allow "install unknown apps" for the browser or file manager
  they download with. Android warns about this; the install steps on the card say so. The
  app is not distributed through Google Play.
- **Integrity.** Publish the SHA-256 (shown on the card) and tell users to compare it, since
  the download is only as trustworthy as the host. Serve over HTTPS only.
- **No secrets in the APK.** An APK can be unpacked by anyone. It must contain no passwords,
  access keys, tokens or private keys. Only public identifiers (server URLs, the Supabase
  publishable key) are acceptable. Per-user credentials are issued after sign-in.
- **Size.** Check the built size before choosing a host; GitHub release assets allow up to 2 GB,
  Vercel static deployments are poor for large binaries. Show the size on the card
  (`VITE_APK_SIZE_BYTES`) so users on mobile data know what they are fetching.
- **Setup links are untrusted input.** A malicious link could point the app at an attacker's
  server; the app must display the target host and ask for confirmation.

## Phone side

The app takes the cloud server from a saved choice first and from the build second.

- **Where it is read.** `InstanceConfig` is the only reader of `BuildConfig.CLOUD_BACKEND_URL` /
  `CLOUD_BROKER_URL`. A saved override (SharedPreferences file `instance`) wins; with no override
  the compiled-in values are used, so existing builds behave as before. An override that has no
  broker means "no broker", never the built-in broker (it belongs to a different server).
- **Opening a link.** The manifest registers `wearablesetup://configure` on `LoginActivity`.
  The `https://<console-host>/setup` form is not registered (that would hard-code a host);
  instead, on the sign-in screen, **Switch server** > **Add server** accepts a pasted setup link or a bare
  `https://` address. `SetupLink` does the parsing: https backend required, `mqtts://` or `ssl://`
  broker optional, unknown parameters ignored, user-info in a URL rejected, clear error text.
- **Confirmation.** The app shows the backend and broker host and asks before saving. Saving
  signs the account out and removes the stored broker login, topic settings and cloud token
  (`u2` on one server is not `u2` on another). Choosing **Cloud** in the server list (the built-in address) resets
  the choice (same clean-up).
- **Profiles.** A profile is a name, a backend URL and an optional broker URL, stored on the
  phone (`ServerProfiles`, kept in the `instance` preferences). The list always offers **Cloud**
  (the built-in address, when the APK has one) and the saved profiles, with the active one
  marked; tapping one switches in one step. When a setup link or typed address is confirmed the
  app offers **Save as profile**, with the host as the default name. **Delete** removes a saved
  profile (not the active one). The current server name and host are shown on the sign-in
  screen, at the top of the main screen and in Settings. Switching clears the broker login,
  topic settings and token, signs out, and restarts at the sign-in screen with the old screens
  removed from the back stack; it is refused while a recording is running.
- **Not built.** The first-run checklist card on the record screen.
- **Tests.** `SetupLinkTest`, `InstanceConfigTest`, `ServerProfilesTest` (JVM). The dialogs and the
  switch-and-sign-out flow have not been exercised on a phone yet.
