#!/usr/bin/env bash
set -euo pipefail

readonly package_name="com.akuvox.mobile.smartplus"
readonly project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-$project_dir/vendor/installed-smartplus}"

command -v adb >/dev/null || {
  echo "adb is unavailable (run this through nix-shell)" >&2
  exit 1
}

mapfile -t package_paths < <(
  adb shell pm path "$package_name" | tr -d '\r' | sed -n 's/^package://p'
)
if [[ ${#package_paths[@]} -eq 0 ]]; then
  echo "SmartPlus is not installed on the connected Android phone" >&2
  exit 1
fi

mkdir -p "$output_dir"
if find "$output_dir" -maxdepth 1 -type f -name '*.apk' -print -quit | grep -q .; then
  echo "$output_dir already contains APKs; choose a new output directory" >&2
  exit 1
fi

for package_path in "${package_paths[@]}"; do
  adb pull "$package_path" "$output_dir/$(basename "$package_path")"
done

echo "Pulled ${#package_paths[@]} APKs into $output_dir"
echo "Run: nix-shell --run './scripts/repackage.sh $output_dir'"
