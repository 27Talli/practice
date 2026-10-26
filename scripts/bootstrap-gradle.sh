#!/bin/sh
# Fetch only Gradle's official wrapper JAR and verify its published SHA-256.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
WRAPPER_DIR="$PROJECT_DIR/gradle/wrapper"
mkdir -p "$WRAPPER_DIR"
TEMP_DIR=$(mktemp -d "$WRAPPER_DIR/.bootstrap.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM
download() {
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 1 --connect-timeout 10 --max-time 120 --silent --show-error "$1" -o "$2"
    elif command -v wget >/dev/null 2>&1; then
        wget --timeout=30 --tries=2 -q "$1" -O "$2"
    else
        echo 'Install curl or wget to bootstrap the official Gradle wrapper.' >&2
        exit 1
    fi
}
echo 'Downloading the official Gradle 8.9 wrapper (first run only)…'
download 'https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256' "$TEMP_DIR/wrapper.sha256"
download 'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar' "$TEMP_DIR/gradle-wrapper.jar"
EXPECTED=$(tr -d '[:space:]' < "$TEMP_DIR/wrapper.sha256")
if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$TEMP_DIR/gradle-wrapper.jar" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 256 "$TEMP_DIR/gradle-wrapper.jar" | awk '{print $1}')
else
    echo 'A SHA-256 tool (sha256sum or shasum) is required.' >&2
    exit 1
fi
if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo 'Gradle wrapper checksum mismatch; the download was rejected.' >&2
    exit 1
fi
mv "$TEMP_DIR/gradle-wrapper.jar" "$WRAPPER_DIR/gradle-wrapper.jar"
echo 'Official Gradle wrapper checksum verified.'
