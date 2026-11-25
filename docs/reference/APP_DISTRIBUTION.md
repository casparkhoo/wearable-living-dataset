# Distributing the Android app

How the app reaches a phone, and why the answer changes once participants are involved.

## Now: the debug APK, for the developer only

`android-application/android/` builds a debug APK at
`app/build/outputs/apk/debug/app-debug.apk` (about 7.2 MB):

```text
./gradlew.bat :app:assembleDebug
```

Install it by transferring the file to the phone and opening it, or with
`adb install -r app-debug.apk`. Android Studio is not required.

**APKs are deliberately not committed to this repository.** Git stores every version
forever, so a 7 MB binary per build accumulates history that cannot easily be removed.
If a stable download URL is wanted, use a GitHub Release, which is built for binaries
and keeps them out of the source tree.

## Why not host it on the public web site

Serving the APK from the Vercel site is the most convenient option and the least suitable
one for a study. Three reasons:

1. It requires the repository to carry the binary, as Vercel builds from Git.
2. The site is public, so a debug-signed, debuggable build becomes publicly downloadable.
3. It teaches participants to enable "install from unknown sources" and sideload an APK
   from a link — which is precisely the behaviour that gets people compromised. Asking
   research participants to do that is difficult to justify in an ethics application, and
   it is a poor habit to instil regardless of the risk in this specific case.

The convenience is real, but it is convenience for the developer, paid for by the
participant's security posture.

## Before participants: proper distribution

Move to one of these before anyone outside the project installs the app:

- **Google Play internal testing** — up to 100 testers by email, installs through the
  normal Play flow with no "unknown sources" prompt, and provides an update path. Requires
  a Play Console account and a release-signed build.
- **Firebase App Distribution** — free, invite by email, no Play Console needed. Testers
  still sideload, so the "unknown sources" objection partly remains, but distribution and
  versioning are managed and revocable.

Either gives something the current arrangement does not: a way to update every participant's
app mid-study, and a way to stop distribution afterwards.

Both need a **release-signed** build rather than the debug build, which also means deciding
where the signing key lives and who holds it. That decision belongs with the ethics
submission, not after it.

## Configuration

The Supabase project URL and publishable key are compiled in through `buildConfigField`
and overridden from `gradle.properties`:

```text
supabaseUrl=https://<project-ref>.supabase.co
supabasePublishableKey=sb_publishable_...
```

Both are public values; the publishable key is designed to ship inside a client and grants
nothing by itself, because the backend reads roles and study membership from its own
database. A Supabase **secret** key must never appear in the app.
