#!/usr/bin/env bash

set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}"
export PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

readonly REPOSITORY_URL="https://dl.google.com/android/repository/repository2-1.xml"
readonly DOWNLOAD_BASE_URL="https://dl.google.com/android/repository"
readonly SDKMANAGER="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

temporary_directory=""

cleanup() {
    if [[ -n "${temporary_directory}" ]]; then
        rm -rf "${temporary_directory}"
    fi
}

trap cleanup EXIT

install_command_line_tools() {
    if [[ -x "${SDKMANAGER}" ]]; then
        echo "Android SDK command-line tools are already installed."
        return
    fi

    local work_dir metadata_file archive_file download_url expected_checksum
    work_dir="$(mktemp -d)"
    metadata_file="${work_dir}/repository.xml"
    archive_file="${work_dir}/command-line-tools.zip"
    temporary_directory="${work_dir}"

    echo "Resolving the latest Android SDK command-line tools..."
    curl --fail --location --retry 3 --silent --show-error \
        "${REPOSITORY_URL}" \
        --output "${metadata_file}"

    IFS=$'\t' read -r download_url expected_checksum < <(
        python3 - "${metadata_file}" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()

for package in root.findall(".//{*}remotePackage"):
    if package.get("path") != "cmdline-tools;latest":
        continue

    for archive in package.findall("./{*}archives/{*}archive"):
        if archive.findtext("./{*}host-os") != "linux":
            continue

        complete = archive.find("./{*}complete")
        if complete is None:
            continue

        url = complete.findtext("./{*}url")
        checksum = complete.findtext("./{*}checksum")
        if url and checksum:
            print(f"{url}\t{checksum}")
            raise SystemExit(0)

raise SystemExit("Latest Linux Android command-line tools were not found")
PY
    )

    echo "Downloading ${download_url}..."
    curl --fail --location --retry 3 --silent --show-error \
        "${DOWNLOAD_BASE_URL}/${download_url}" \
        --output "${archive_file}"

    echo "${expected_checksum}  ${archive_file}" | sha1sum --check -

    mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"
    unzip -q "${archive_file}" -d "${work_dir}/extracted"
    mv "${work_dir}/extracted/cmdline-tools" \
        "${ANDROID_SDK_ROOT}/cmdline-tools/latest"

    rm -rf "${work_dir}"
    temporary_directory=""
}

accept_android_licenses() {
    echo "Accepting Android SDK licenses..."
    set +o pipefail
    yes | "${SDKMANAGER}" --licenses >/dev/null
    local sdkmanager_status="${PIPESTATUS[1]}"
    set -o pipefail

    if [[ "${sdkmanager_status}" -ne 0 ]]; then
        echo "Android SDK license acceptance failed." >&2
        return "${sdkmanager_status}"
    fi
}

update_local_properties() {
    local local_properties="${PROJECT_ROOT}/local.properties"

    python3 - "${local_properties}" "${ANDROID_SDK_ROOT}" <<'PY'
import pathlib
import sys

properties_path = pathlib.Path(sys.argv[1])
sdk_root = sys.argv[2]
lines = properties_path.read_text().splitlines() if properties_path.exists() else []

updated = []
sdk_dir_written = False
for line in lines:
    if line.lstrip().startswith("sdk.dir="):
        if not sdk_dir_written:
            updated.append(f"sdk.dir={sdk_root}")
            sdk_dir_written = True
        continue
    updated.append(line)

if not sdk_dir_written:
    updated.append(f"sdk.dir={sdk_root}")

properties_path.write_text("\n".join(updated) + "\n")
PY

    echo "Configured ${local_properties} with sdk.dir=${ANDROID_SDK_ROOT}"
}

install_command_line_tools
accept_android_licenses

echo "Installing Android SDK packages..."
"${SDKMANAGER}" --install \
    "platform-tools" \
    "platforms;android-36" \
    "build-tools;36.0.0"

accept_android_licenses
update_local_properties

echo
echo "Verifying Android SDK installation..."
"${SDKMANAGER}" --list

echo
echo "Verifying Java..."
java -version

echo
echo "Verifying Gradle..."
cd "${PROJECT_ROOT}"
./gradlew --version
