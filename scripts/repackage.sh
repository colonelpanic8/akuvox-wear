#!/usr/bin/env bash
set -euo pipefail

readonly original_package="com.akuvox.mobile.smartplus"
readonly replacement_package="com.ivanmalison.akuvoxwear"
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly project_dir

usage() {
  echo "usage: $0 <SmartPlus.xapk|directory-of-installed-apks> [output-directory]" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage

input_path="$(realpath "$1")"
output_dir="${2:-$project_dir/dist}"
mkdir -p "$output_dir"
output_dir="$(realpath "$output_dir")"

for command_name in apktool apksigner unzip zipalign; do
  command -v "$command_name" >/dev/null || {
    echo "missing required command: $command_name (run this through nix-shell)" >&2
    exit 1
  }
done

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

source_dir="$input_path"
if [[ -f "$input_path" ]]; then
  source_dir="$work_dir/source"
  mkdir -p "$source_dir"
  unzip -q "$input_path" -d "$source_dir"
elif [[ ! -d "$input_path" ]]; then
  usage
fi

base_apk=""
for candidate in \
  "$source_dir/base.apk" \
  "$source_dir/$original_package.apk" \
  "$source_dir/com.akuvox.mobile.smartplus.apk"; do
  if [[ -f "$candidate" ]]; then
    base_apk="$candidate"
    break
  fi
done
if [[ -z "$base_apk" ]]; then
  echo "could not find the SmartPlus base APK in $source_dir" >&2
  exit 1
fi

echo "Building the Wear bridge and watch app"
"$project_dir/gradlew" --no-daemon -p "$project_dir" :mobile:assembleDebug :wear:assembleDebug

payload_apk="$project_dir/mobile/build/outputs/apk/debug/mobile-debug.apk"
watch_apk="$project_dir/wear/build/outputs/apk/debug/wear-debug.apk"
decoded_base="$work_dir/base"
decoded_payload="$work_dir/payload"

echo "Decompiling SmartPlus"
apktool d -f "$base_apk" -o "$decoded_base"
apktool d -f -r "$payload_apk" -o "$decoded_payload"

while IFS= read -r split_apk; do
  if unzip -Z1 "$split_apk" | awk '/^lib\/[^/]+\/[^/]+\.so$/ { found = 1 } END { exit !found }'; then
    echo "Merging native libraries from $(basename "$split_apk")"
    unzip -q -o "$split_apk" 'lib/*' -d "$decoded_base"
  fi
done < <(find "$source_dir" -maxdepth 1 -type f -name '*.apk' ! -samefile "$base_apk" -print)

if ! find "$decoded_base/lib" -type f -name '*.so' -print -quit 2>/dev/null | grep -q .; then
  echo "no native-library split was found; use an XAPK or APKs pulled from the phone" >&2
  exit 1
fi

manifest="$decoded_base/AndroidManifest.xml"
apktool_config="$decoded_base/apktool.yml"
sed -i \
  -e 's/ android:requiredSplitTypes="[^"]*"//' \
  -e 's/android:extractNativeLibs="false"/android:extractNativeLibs="true"/' \
  -e "s/$original_package/$replacement_package/g" \
  "$manifest"

# Several bundled SDK screens (QR scan, the Aliyun pickers, the log viewers) are
# pinned to portrait, and nothing declares itself resizable. Drop the locks and
# opt the whole application in to resizing so the window follows the display.
echo "Removing portrait locks and size limits for large screens"
sed -i -E \
  -e 's/ android:screenOrientation="[^"]*"//g' \
  -e 's/ android:resizeableActivity="[^"]*"//g' \
  -e 's/ android:(max|min)AspectRatio="[^"]*"//g' \
  "$manifest"
perl -0pi -e 's#<supports-screens\b[^>]*/>\s*##g; s#<supports-screens\b.*?</supports-screens>\s*##gs' "$manifest"
perl -0pi -e 's#<application\b#<application android:resizeableActivity="true"#' "$manifest"
if grep -E -q 'android:screenOrientation=|android:resizeableActivity="false"' "$manifest"; then
  echo "large-screen manifest patch left a restriction behind" >&2
  exit 1
fi

if [[ -n "${SMARTPLUS_WEAR_VERSION_CODE:-}" ]]; then
  [[ "$SMARTPLUS_WEAR_VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || {
    echo "SMARTPLUS_WEAR_VERSION_CODE must be a positive integer" >&2
    exit 1
  }
  sed -i -E \
    "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$SMARTPLUS_WEAR_VERSION_CODE\"/" \
    "$manifest"
  sed -i -E \
    "s/^([[:space:]]*versionCode:).*/\\1 $SMARTPLUS_WEAR_VERSION_CODE/" \
    "$apktool_config"
fi
if [[ -n "${SMARTPLUS_WEAR_VERSION_NAME:-}" ]]; then
  sed -i -E \
    "s/android:versionName=\"[^\"]*\"/android:versionName=\"$SMARTPLUS_WEAR_VERSION_NAME\"/" \
    "$manifest"
  sed -i -E \
    "s/^([[:space:]]*versionName:).*/\\1 $SMARTPLUS_WEAR_VERSION_NAME/" \
    "$apktool_config"
fi

service_fragment="$(<"$project_dir/repack/service-manifest.xml")"
SERVICE_FRAGMENT="$service_fragment" perl -0pi -e \
  's#</application>#$ENV{SERVICE_FRAGMENT}\n    </application>#' "$manifest"

if [[ -f "$decoded_base/res/values/strings.xml" ]]; then
  perl -0pi -e \
    's#(<string name="app_name"[^>]*>).*?(</string>)#$1SmartPlus Wear$2#' \
    "$decoded_base/res/values/strings.xml"
fi

while IFS= read -r smali_file; do
  sed -i "s/$original_package/$replacement_package/g" "$smali_file"
done < <(grep -rl --include='*.smali' "$original_package" "$decoded_base"/smali* || true)

# The manifest is not enough: SmartPlus also re-locks orientation at runtime from
# its base activities. Dropping the calls leaves each activity in the orientation
# the device chooses.
orientation_call=";->setRequestedOrientation(I)V"
orientation_calls=0
while IFS= read -r smali_file; do
  orientation_calls=$((orientation_calls + $(grep -c -F -e "$orientation_call" "$smali_file")))
  sed -i -e "/$orientation_call/d" "$smali_file"
done < <(grep -rl -F --include='*.smali' -e "$orientation_call" "$decoded_base"/smali* || true)
echo "Removed $orientation_calls runtime orientation requests"

find "$decoded_base"/smali* -type f -printf '%P\n' | sort -u > "$work_dir/base-classes"
find "$decoded_payload"/smali* -type f -printf '%P\n' | sort -u > "$work_dir/payload-classes"
if comm -12 "$work_dir/base-classes" "$work_dir/payload-classes" | grep -q .; then
  echo "the bridge payload duplicates classes already present in this SmartPlus version" >&2
  comm -12 "$work_dir/base-classes" "$work_dir/payload-classes" | head -20 >&2
  exit 1
fi

next_dex=2
for base_smali in "$decoded_base"/smali_classes*; do
  [[ -d "$base_smali" ]] || continue
  dex_number="${base_smali##*smali_classes}"
  if [[ "$dex_number" =~ ^[0-9]+$ && "$dex_number" -ge "$next_dex" ]]; then
    next_dex=$((dex_number + 1))
  fi
done
for payload_smali in "$decoded_payload"/smali*; do
  [[ -d "$payload_smali" ]] || continue
  cp -a "$payload_smali" "$decoded_base/smali_classes$next_dex"
  ((next_dex += 1))
done

unsigned_apk="$work_dir/smartplus-wear-unsigned.apk"
aligned_apk="$work_dir/smartplus-wear-aligned.apk"
phone_apk="$output_dir/smartplus-wear-phone.apk"
watch_output="$output_dir/smartplus-wear-watch.apk"

echo "Rebuilding SmartPlus with the Wear bridge"
apktool b "$decoded_base" -o "$unsigned_apk"
zipalign -f -p 4 "$unsigned_apk" "$aligned_apk"

release_keystore="$work_dir/release.keystore"
if [[ -n "${ANDROID_KEYSTORE_BASE64:-}" ]]; then
  printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$release_keystore"
  chmod 600 "$release_keystore"
elif [[ -n "${ANDROID_KEYSTORE_FILE:-}" ]]; then
  release_keystore="$(realpath "$ANDROID_KEYSTORE_FILE")"
else
  android_config_dir="${ANDROID_USER_HOME:-${HOME}/.android}"
  release_keystore="$android_config_dir/debug.keystore"
  : "${ANDROID_KEY_ALIAS:=androiddebugkey}"
  : "${ANDROID_KEYSTORE_PASSWORD:=android}"
  : "${ANDROID_KEY_PASSWORD:=android}"
fi

if [[ ! -f "$release_keystore" ]]; then
  echo "signing keystore not found: $release_keystore" >&2
  exit 1
fi
for signing_variable in ANDROID_KEY_ALIAS ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD; do
  if [[ -z "${!signing_variable:-}" ]]; then
    echo "release signing requires $signing_variable" >&2
    exit 1
  fi
done
export ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD

apksigner sign \
  --ks "$release_keystore" \
  --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
  --key-pass env:ANDROID_KEY_PASSWORD \
  --out "$phone_apk" \
  "$aligned_apk"
apksigner sign \
  --ks "$release_keystore" \
  --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
  --key-pass env:ANDROID_KEY_PASSWORD \
  --out "$watch_output" \
  "$watch_apk"

apksigner verify --verbose "$phone_apk" >/dev/null
apksigner verify --verbose "$watch_output" >/dev/null

phone_cert="$(apksigner verify --print-certs "$phone_apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
watch_cert="$(apksigner verify --print-certs "$watch_output" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [[ -z "$phone_cert" || "$phone_cert" != "$watch_cert" ]]; then
  echo "phone and watch signing certificates do not match" >&2
  exit 1
fi

echo "Built:"
echo "  $phone_apk"
echo "  $watch_output"
echo "Signing certificate: $phone_cert"
