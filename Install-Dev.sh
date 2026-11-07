#!/usr/bin/env bash
# Initialise the 3Denlive dev/build environment (both folders are gitignored):
#  - distrib/ffmpeg/ : latest stable static ffmpeg builds (GPL, with libx264), where
#                      the app looks for them (Ffmpeg.findFfmpeg(), relative to the working dir)
#  - JVMs/<config>/  : a Java runtime (JRE) per delivery configuration, bundled by
#                      build_delivery.sh into each 3Denlive<config>.zip
#
# Usage: ./Install-Dev.sh [-f] [--ffmpeg-only|--jvm-only] [config...]
#   configs: Linux PPC64le Win-x64 MacIntel MacSilicon (default: all)
#   -f            : re-download even if already present (to update to the latest versions)
#   --ffmpeg-only : only ffmpeg (enough to develop and run from distrib/)
#   --jvm-only    : only the JVMs (needed by build_delivery.sh)
#
# Sources:
#   ffmpeg Linux / Windows : https://github.com/BtbN/FFmpeg-Builds (latest nX.Y release branch)
#   ffmpeg macOS           : https://ffmpeg.martin-riedl.de (latest release)
#   JVMs                   : Eclipse Temurin (https://adoptium.net), except MacIntel:
#                            Azul Zulu (https://www.azul.com), Temurin has no macOS x64 build
# Requires: curl, tar (with xz support), unzip.

set -euo pipefail

JAVA_VERSION=27   # must be >= the --release used to compile (see build_delivery.sh)
ALL_CONFIGS=(Linux PPC64le Win-x64 MacIntel MacSilicon)

ROOT="$(cd "$(dirname "$0")" && pwd)"
FFMPEG_DEST="$ROOT/distrib/ffmpeg"
JVM_DEST="$ROOT/JVMs"
FORCE=0
DO_FFMPEG=1
DO_JVM=1
CONFIGS=()

for arg in "$@"; do
	case "$arg" in
		-f|--force) FORCE=1 ;;
		--ffmpeg-only) DO_JVM=0 ;;
		--jvm-only) DO_FFMPEG=0 ;;
		-h|--help) sed -n '2,19p' "$0"; exit 0 ;;
		Linux|PPC64le|Win-x64|MacIntel|MacSilicon) CONFIGS+=("$arg") ;;
		*) echo "Unknown argument: $arg (configs: ${ALL_CONFIGS[*]})" >&2; exit 1 ;;
	esac
done
[ ${#CONFIGS[@]} -eq 0 ] && CONFIGS=("${ALL_CONFIGS[@]}")

for tool in curl tar unzip; do
	command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Downloads $1 and extracts it into $2
fetch_extract() {
	local url="$1" out="$2"
	mkdir -p "$out"
	case "$url" in
		*.tar.xz) curl -fL --progress-bar -o "$out.tar.xz" "$url"; tar -xJf "$out.tar.xz" -C "$out" ;;
		*.tar.gz) curl -fL --progress-bar -o "$out.tar.gz" "$url"; tar -xzf "$out.tar.gz" -C "$out" ;;
		*.zip)    curl -fL --progress-bar -o "$out.zip" "$url";    unzip -q "$out.zip" -d "$out" ;;
		*) echo "Unexpected archive type: $url" >&2; return 1 ;;
	esac
}

########## ffmpeg ##########

# File name expected by the app: ffmpeg-<os.arch><suffix>. Empty = no bundled ffmpeg for this config.
ffmpeg_name() {
	case "$1" in
		Linux)      echo "ffmpeg-amd64" ;;
		Win-x64)    echo "ffmpeg-amd64.exe" ;;
		MacIntel)   echo "ffmpeg-x86_64-osx" ;;
		MacSilicon) echo "ffmpeg-aarch64-osx" ;;
		*)          echo "" ;;
	esac
}

BTBN_ASSETS=""
btbn_url() { # $1 = linux64 | win64 -> URL of the most recent release-branch GPL build
	if [ -z "$BTBN_ASSETS" ]; then
		BTBN_ASSETS="$(curl -fsSL https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest \
			| grep -oE '"browser_download_url": *"[^"]+"' | sed -E 's/.*"(https[^"]+)"/\1/')"
	fi
	echo "$BTBN_ASSETS" | grep -E "/ffmpeg-n[0-9.]+-latest-$1-gpl-[0-9.]+\.(tar\.xz|zip)$" | sort -V | tail -1
}

riedl_url() { # $1 = amd64 | arm64 -> resolved URL of the latest macOS release
	curl -fsS -o /dev/null -w '%{redirect_url}' "https://ffmpeg.martin-riedl.de/redirect/latest/macos/$1/release/ffmpeg.zip"
}

install_ffmpeg() {
	local config="$1" name url work bin
	name="$(ffmpeg_name "$config")"
	if [ -z "$name" ]; then
		echo "[$config] ffmpeg: no static build available, the system ffmpeg will be used"
		return
	fi
	if [ -f "$FFMPEG_DEST/$name" ] && [ "$FORCE" -eq 0 ]; then
		echo "[$config] ffmpeg: $name already present (use -f to update)"
		return
	fi
	case "$config" in
		Linux)      url="$(btbn_url linux64)" ;;
		Win-x64)    url="$(btbn_url win64)" ;;
		MacIntel)   url="$(riedl_url amd64)" ;;
		MacSilicon) url="$(riedl_url arm64)" ;;
	esac
	[ -n "$url" ] || { echo "[$config] ffmpeg: could not find a download URL" >&2; return 1; }

	echo "[$config] ffmpeg: downloading $url"
	work="$TMP/ffmpeg-$config"
	fetch_extract "$url" "$work"
	bin="$(find "$work" -type f \( -name ffmpeg -o -name ffmpeg.exe \) | head -1)"
	[ -n "$bin" ] || { echo "[$config] ffmpeg not found in archive" >&2; return 1; }
	mkdir -p "$FFMPEG_DEST"
	mv -f "$bin" "$FFMPEG_DEST/$name"
	chmod 755 "$FFMPEG_DEST/$name"
	echo "[$config] ffmpeg: installed $FFMPEG_DEST/$name ($(du -h "$FFMPEG_DEST/$name" | cut -f1 | tr -d ' '))"
	printf '%s\t%s\t%s\n' "$name" "$url" "$(date +%Y-%m-%d)" >> "$FFMPEG_DEST/SOURCES.txt"
}

########## JVMs ##########

jvm_url() {
	local temurin="https://api.adoptium.net/v3/binary/latest/$JAVA_VERSION/ga"
	case "$1" in
		Linux)      echo "$temurin/linux/x64/jre/hotspot/normal/eclipse" ;;
		PPC64le)    echo "$temurin/linux/ppc64le/jre/hotspot/normal/eclipse" ;;
		Win-x64)    echo "$temurin/windows/x64/jre/hotspot/normal/eclipse" ;;
		MacSilicon) echo "$temurin/mac/aarch64/jre/hotspot/normal/eclipse" ;;
		MacIntel)   curl -fsS "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$JAVA_VERSION&os=macos&arch=x64&java_package_type=jre&archive_type=tar.gz&javafx_bundled=false&release_status=ga&latest=true" \
		                | grep -oE '"download_url": *"[^"]+"' | head -1 | sed -E 's/.*"(https[^"]+)"/\1/' ;;
	esac
}

install_jvm() {
	local config="$1" dest="$JVM_DEST/$1" url work java home
	if [ -f "$dest/release" ] && [ "$FORCE" -eq 0 ]; then
		echo "[$config] JVM: already present, $(grep '^JAVA_VERSION=' "$dest/release") (use -f to update)"
		return
	fi
	url="$(jvm_url "$config")"
	[ -n "$url" ] || { echo "[$config] JVM: could not find a download URL" >&2; return 1; }
	# Resolve the API redirect to get the real archive name (and its extension)
	case "$url" in
		https://api.adoptium.net/*) url="$(curl -fsS -o /dev/null -w '%{redirect_url}' "$url")" ;;
	esac
	[ -n "$url" ] || { echo "[$config] JVM: no Java $JAVA_VERSION runtime available" >&2; return 1; }

	echo "[$config] JVM: downloading $url"
	work="$TMP/jvm-$config"
	fetch_extract "$url" "$work"
	# The runtime home is the folder holding bin/java (macOS archives nest it in .../Contents/Home)
	java="$(find "$work" -type f \( -path '*/bin/java' -o -path '*/bin/java.exe' \) | head -1)"
	[ -n "$java" ] || { echo "[$config] JVM: bin/java not found in archive" >&2; return 1; }
	home="$(dirname "$(dirname "$java")")"
	rm -rf "${dest:?}"
	mkdir -p "$JVM_DEST"
	mv "$home" "$dest"
	printf '%s\t%s\n' "$url" "$(date +%Y-%m-%d)" > "$dest/SOURCE.txt"
	echo "[$config] JVM: installed $dest, $(grep '^JAVA_VERSION=' "$dest/release")"
}

for c in "${CONFIGS[@]}"; do
	[ "$DO_FFMPEG" -eq 1 ] && install_ffmpeg "$c"
	[ "$DO_JVM" -eq 1 ] && install_jvm "$c"
done
exit 0
