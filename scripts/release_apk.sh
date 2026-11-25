#!/usr/bin/env bash
#
# Build a signed release APK of the phone app. See docs/APK_DISTRIBUTION.md.
#
#   scripts/release_apk.sh [--backend URL] [--broker URL] [--out DIR]
#
# Built-in default server (optional): --backend / --broker, or RELEASE_BACKEND_URL /
# RELEASE_BROKER_URL. With none given the APK has no default server and asks on first
# launch (setup link or typed address); it never picks up the addresses in
# android-application/android/instance.properties. The Supabase URL and publishable key
# (public by design) still come from instance.properties, or SUPABASE_URL /
# SUPABASE_PUBLISHABLE_KEY in the environment.
#
# Signing key, kept OUTSIDE the repository:
#   keystore  RELEASE_KEYSTORE (default ~/.thesis/release.keystore)
#   secrets   RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD in the
#             environment, or storePassword / keyAlias / keyPassword lines in
#             RELEASE_SIGNING_PROPERTIES (default ~/.thesis/release-signing.properties)
# If the keystore does not exist it is created with a random password, written only to
# that properties file. Back both up: phones refuse updates signed with a different key.
#
# Version: versionName 1.0.<commit count> (base from APP_VERSION_BASE), versionCode = commit count.
# Output:   dist/wearable-controller-<version>.apk and .apk.sha256, plus the Vercel env lines.
#
# Works in bash on Linux and macOS, and in Git Bash on Windows.

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/android-application/android"
die() { echo "error: $*" >&2; exit 1; }

BACKEND="${RELEASE_BACKEND_URL:-}"
BROKER="${RELEASE_BROKER_URL:-}"
OUT="$ROOT/dist"
while [ $# -gt 0 ]; do
  case "$1" in
    --backend) BACKEND="${2:-}"; shift 2 ;;
    --broker)  BROKER="${2:-}"; shift 2 ;;
    --out)     OUT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done
[ -z "$BACKEND" ] || case "$BACKEND" in https://*) ;; *) die "--backend must start with https://" ;; esac
[ -z "$BROKER" ] || case "$BROKER" in mqtts://*|ssl://*|wss://*) ;; *) die "--broker must start with mqtts://, ssl:// or wss://" ;; esac
[ -n "$BACKEND" ] || [ -z "$BROKER" ] || die "--broker needs --backend"

to_unix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi; }
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

HOME_DIR="$(to_unix "${USERPROFILE:-$HOME}")"
KEYSTORE="$(to_unix "${RELEASE_KEYSTORE:-$HOME_DIR/.thesis/release.keystore}")"
PROPS="$(to_unix "${RELEASE_SIGNING_PROPERTIES:-$HOME_DIR/.thesis/release-signing.properties}")"

# --- tools ------------------------------------------------------------------
find_sdk() {
  local c
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do [ -n "$c" ] && [ -d "$c" ] && { to_unix "$c"; return; }; done
  if [ -f "$APP/local.properties" ]; then
    c=$(grep -E '^sdk.dir=' "$APP/local.properties" | head -n1 | cut -d= -f2- | tr -d '\r' | sed 's#\\\\#/#g; s#\\:#:#g')
    [ -n "$c" ] && [ -d "$(to_unix "$c")" ] && { to_unix "$c"; return; }
  fi
  return 1
}
SDK="$(find_sdk)" || die "Android SDK not found (set ANDROID_HOME or android-application/android/local.properties)"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n1)"
BT="${BT%/}"
[ -n "$BT" ] && [ -f "$BT/lib/apksigner.jar" ] || die "no build-tools with apksigner under $SDK/build-tools"
ZIPALIGN="$BT/zipalign"; [ -x "$ZIPALIGN" ] || ZIPALIGN="$BT/zipalign.exe"
[ -x "$ZIPALIGN" ] || die "zipalign not found in $BT"

find_keytool() {
  if command -v keytool >/dev/null 2>&1; then command -v keytool; return; fi
  local c
  for c in "${JAVA_HOME:-}/bin/keytool" "${JAVA_HOME:-}/bin/keytool.exe" \
           "/c/Program Files/Android/Android Studio/jbr/bin/keytool.exe" \
           /c/Program\ Files/Java/*/bin/keytool.exe /usr/lib/jvm/*/bin/keytool; do
    [ -x "$c" ] && { echo "$c"; return; }
  done
  return 1
}
KEYTOOL="$(find_keytool)" || die "keytool not found (install a JDK or set JAVA_HOME)"
command -v java >/dev/null 2>&1 || die "java not found"

rand_secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24
  else python3 -c 'import secrets; print(secrets.token_hex(24))' 2>/dev/null || python -c 'import secrets; print(secrets.token_hex(24))'
  fi
}
prop() { grep -E "^$1=" "$PROPS" 2>/dev/null | head -n1 | cut -d= -f2- | tr -d '\r' || true; }

# --- signing key ------------------------------------------------------------
STORE_PASS="${RELEASE_KEYSTORE_PASSWORD:-$(prop storePassword)}"
KEY_ALIAS="${RELEASE_KEY_ALIAS:-$(prop keyAlias)}"; KEY_ALIAS="${KEY_ALIAS:-wearable-release}"
KEY_PASS="${RELEASE_KEY_PASSWORD:-$(prop keyPassword)}"

if [ ! -f "$KEYSTORE" ]; then
  [ -z "$STORE_PASS" ] || die "keystore $KEYSTORE is missing but a password is already configured; restore the keystore (a new key cannot update installed apps)"
  echo "Creating release keystore at $KEYSTORE (keep it and $PROPS safe; never commit them)"
  mkdir -p "$(dirname "$KEYSTORE")" "$(dirname "$PROPS")"
  STORE_PASS="$(rand_secret)"; KEY_PASS="$STORE_PASS"
  ( umask 077; printf 'storePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' "$STORE_PASS" "$KEY_ALIAS" "$KEY_PASS" > "$PROPS" )
  export KS_PASS="$STORE_PASS"
  "$KEYTOOL" -genkeypair -keystore "$(to_native "$KEYSTORE")" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 3072 -validity 10950 \
    -dname "CN=Wearable Controller, O=Wearable Living Dataset" \
    -storepass:env KS_PASS -keypass:env KS_PASS >/dev/null
  chmod 600 "$KEYSTORE" "$PROPS" 2>/dev/null || true
  echo "Saved the generated signing password to $PROPS"
fi
[ -n "$STORE_PASS" ] || die "no keystore password: set RELEASE_KEYSTORE_PASSWORD or put storePassword in $PROPS"
KEY_PASS="${KEY_PASS:-$STORE_PASS}"
export KS_PASS="$STORE_PASS" KEY_PASS

# --- version ----------------------------------------------------------------
COUNT="$(git -C "$ROOT" rev-list --count HEAD)"
SHA="$(git -C "$ROOT" rev-parse --short HEAD)"
VERSION="${APP_VERSION_BASE:-1.0}.$COUNT"
if [ -n "$(git -C "$ROOT" status --porcelain -- android-application 2>/dev/null)" ]; then
  VERSION="$VERSION-dirty"
  echo "warning: uncommitted changes under android-application; version is $VERSION" >&2
fi
CODE="$COUNT"
echo "Version $VERSION (code $CODE, commit $SHA)"

# --- build ------------------------------------------------------------------
GRADLE_ARGS=(":app:assembleRelease" -q
  "-PappVersionName=$VERSION" "-PappVersionCode=$CODE"
  "-PcloudBackendUrl=$BACKEND" "-PcloudBrokerUrl=$BROKER")
[ -z "${SUPABASE_URL:-}" ] || GRADLE_ARGS+=("-PsupabaseUrl=$SUPABASE_URL")
[ -z "${SUPABASE_PUBLISHABLE_KEY:-}" ] || GRADLE_ARGS+=("-PsupabasePublishableKey=$SUPABASE_PUBLISHABLE_KEY")
if [ -z "$BACKEND" ]; then
  echo "No default server given: the app will ask for one on first launch."
else
  echo "Built-in default server: $BACKEND"
fi
if [ -z "${SUPABASE_URL:-}" ] && ! grep -qE '^supabaseUrl=https://' "$APP/instance.properties" 2>/dev/null; then
  echo "warning: no Supabase URL configured; sign-in will be unavailable in this APK" >&2
fi
( cd "$APP" && ./gradlew "${GRADLE_ARGS[@]}" )

UNSIGNED="$APP/app/build/outputs/apk/release/app-release-unsigned.apk"
[ -f "$UNSIGNED" ] || UNSIGNED="$APP/app/build/outputs/apk/release/app-release.apk"
[ -f "$UNSIGNED" ] || die "release APK not found under app/build/outputs/apk/release"

# --- align, sign, verify ----------------------------------------------------
mkdir -p "$OUT"
FINAL="$OUT/wearable-controller-$VERSION.apk"
ALIGNED="$OUT/.aligned.tmp.apk"
rm -f "$ALIGNED" "$FINAL" "$FINAL.idsig"
"$ZIPALIGN" -f -p 4 "$(to_native "$UNSIGNED")" "$(to_native "$ALIGNED")"
java -jar "$(to_native "$BT/lib/apksigner.jar")" sign \
  --ks "$(to_native "$KEYSTORE")" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
  --out "$(to_native "$FINAL")" "$(to_native "$ALIGNED")" 2>&1 | grep -v '^WARNING' || true
[ -f "$FINAL" ] || die "signing failed"
rm -f "$ALIGNED" "$FINAL.idsig"
echo "Verifying signature:"
VERIFY="$(java -jar "$(to_native "$BT/lib/apksigner.jar")" verify -v --print-certs "$(to_native "$FINAL")" 2>&1 | grep -v '^WARNING')" \
  || { echo "$VERIFY" >&2; die "signature verification failed"; }
echo "$VERIFY" | grep -E "^(Verifies|Verified using|Signer #1 certificate (DN|SHA-256))" \
  || { echo "$VERIFY" >&2; die "signature not verified"; }

# --- guard: nothing secret in the package -----------------------------------
if unzip -p "$FINAL" 'classes*.dex' 2>/dev/null | grep -aqE 'sb_secret_|service_role'; then
  rm -f "$FINAL"; die "the APK contains a secret-looking key (sb_secret_ / service_role); removed it"
fi

# --- checksum and Vercel values ---------------------------------------------
SUM="$(sha256sum "$FINAL" | cut -d' ' -f1)"
SIZE="$(wc -c < "$FINAL" | tr -d ' ')"
printf '%s  %s\n' "$SUM" "$(basename "$FINAL")" > "$FINAL.sha256"
echo
echo "APK: $FINAL"
echo "Set these in the Vercel project (and VITE_APK_URL to where you host the file):"
echo "VITE_APK_VERSION=$VERSION"
echo "VITE_APK_SHA256=$SUM"
echo "VITE_APK_SIZE_BYTES=$SIZE"
