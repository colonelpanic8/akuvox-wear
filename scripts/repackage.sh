#!/usr/bin/env bash
set -euo pipefail

readonly original_package="com.akuvox.mobile.smartplus"
readonly replacement_package="com.ivanmalison.akuvoxwear"
readonly project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
  if unzip -Z1 "$split_apk" | grep -qE '^lib/[^/]+/[^/]+\.so$'; then
    echo "Merging native libraries from $(basename "$split_apk")"
    unzip -q -o "$split_apk" 'lib/*' -d "$decoded_base"
  fi
done < <(find "$source_dir" -maxdepth 1 -type f -name '*.apk' ! -samefile "$base_apk" -print)

if ! find "$decoded_base/lib" -type f -name '*.so' -print -quit 2>/dev/null | grep -q .; then
  echo "no native-library split was found; use an XAPK or APKs pulled from the phone" >&2
  exit 1
fi

manifest="$decoded_base/AndroidManifest.xml"
sed -i \
  -e 's/ android:requiredSplitTypes="[^"]*"//' \
  -e 's/android:extractNativeLibs="false"/android:extractNativeLibs="true"/' \
  -e "s/$original_package/$replacement_package/g" \
  "$manifest"

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

android_config_dir="${ANDROID_USER_HOME:-${HOME}/.android}"
debug_keystore="$android_config_dir/debug.keystore"
if [[ ! -f "$debug_keystore" ]]; then
  echo "Gradle did not create the expected debug keystore at $debug_keystore" >&2
  exit 1
fi

apksigner sign \
  --ks "$debug_keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$phone_apk" \
  "$aligned_apk"
cp "$watch_apk" "$watch_output"

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
