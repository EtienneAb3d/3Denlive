#!/usr/bin/env bash
# Initialise the 3Denlive dev/build environment (both folders are gitignored):
#  - distrib/ffmpeg/ : latest stable static ffmpeg builds (GPL, with libx264), where
#                      the app looks for them (Ffmpeg.findFfmpeg(), relative to the working dir)
#  - JVMs/<config>/  : a Java runtime (JRE) per delivery configuration, bundled by
#                      build_delivery.sh into each 3Denlive<config>.zip
#  - distrib/Linux/javafx/ : JavaFX (jars + natives) for the Linux config: its WebKit shows the web pages
#                      when the system has no WebKitGTK (GUI/FxBrowser), bundled into the Linux delivery
#  - JVMs/JDK/       : a Java JDK for this machine, used by run_3De.sh and build_delivery.sh
#                      when no JDK 27+ is installed (find_jdk.sh)
#
# Usage: ./Install-Dev.sh [-f] [--ffmpeg-only|--jvm-only|--jdk-only|--javafx-only] [config...]
#   configs: Linux PPC64le Win-x64 MacIntel MacSilicon (default: all)
#   -f            : re-download even if already present (to update to the latest versions)
#   --ffmpeg-only : only ffmpeg (enough to develop and run from distrib/)
#   --jvm-only    : only the JVMs (needed by build_delivery.sh)
#   --jdk-only    : only the JDK of this machine (to compile when the system has no JDK 27+)
#   --javafx-only : only JavaFX (Linux config)
#
# Sources:
#   ffmpeg Linux / Windows : https://github.com/BtbN/FFmpeg-Builds (latest nX.Y release branch)
#   ffmpeg macOS           : https://ffmpeg.martin-riedl.de (latest release)
#   JavaFX                 : Gluon OpenJFX SDK (https://gluonhq.com/products/javafx/), version JAVAFX_VERSION
#   JVMs, JDK              : Eclipse Temurin (https://adoptium.net), except MacIntel:
#                            Azul Zulu (https://www.azul.com), Temurin has no macOS x64 build
# Requires: curl, tar (with xz support), unzip.

set -euo pipefail

JAVAFX_VERSION=27.0.1   # JavaFX of the Linux config (its WebKit replaces WebKitGTK when missing)
JAVA_VERSION=27   # must be >= the --release used to compile (see build_delivery.sh)
ALL_CONFIGS=(Linux PPC64le Win-x64 MacIntel MacSilicon)

ROOT="$(cd "$(dirname "$0")" && pwd)"
FFMPEG_DEST="$ROOT/distrib/ffmpeg"
JVM_DEST="$ROOT/JVMs"
FORCE=0
DO_FFMPEG=1
DO_JVM=1
DO_JDK=1
DO_JAVAFX=1
CONFIGS=()

for arg in "$@"; do
	case "$arg" in
		-f|--force) FORCE=1 ;;
		--ffmpeg-only) DO_JVM=0; DO_JDK=0; DO_JAVAFX=0 ;;
		--jvm-only) DO_FFMPEG=0; DO_JDK=0; DO_JAVAFX=0 ;;
		--jdk-only) DO_FFMPEG=0; DO_JVM=0; DO_JAVAFX=0 ;;
		--javafx-only) DO_FFMPEG=0; DO_JVM=0; DO_JDK=0 ;;
		-h|--help) sed -n '2,26p' "$0"; exit 0 ;;
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

jvm_url() { # $1 = config, $2 = jre | jdk
	local temurin="https://api.adoptium.net/v3/binary/latest/$JAVA_VERSION/ga" pkg="${2:-jre}"
	case "$1" in
		Linux)      echo "$temurin/linux/x64/$pkg/hotspot/normal/eclipse" ;;
		LinuxArm)   echo "$temurin/linux/aarch64/$pkg/hotspot/normal/eclipse" ;;
		PPC64le)    echo "$temurin/linux/ppc64le/$pkg/hotspot/normal/eclipse" ;;
		Win-x64)    echo "$temurin/windows/x64/$pkg/hotspot/normal/eclipse" ;;
		MacSilicon) echo "$temurin/mac/aarch64/$pkg/hotspot/normal/eclipse" ;;
		MacIntel)   curl -fsS "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$JAVA_VERSION&os=macos&arch=x64&java_package_type=$pkg&archive_type=tar.gz&javafx_bundled=false&release_status=ga&latest=true" \
		                | grep -oE '"download_url": *"[^"]+"' | head -1 | sed -E 's/.*"(https[^"]+)"/\1/' ;;
	esac
}

install_jvm() { # $1 = config, [$2 = jdk: the JDK of this machine into JVMs/JDK]
	local config="$1" pkg="${2:-jre}" dest="$JVM_DEST/$1" url work java home
	local label="JVM"
	[ "$pkg" = jdk ] && dest="$JVM_DEST/JDK" && label="JDK"
	if [ -f "$dest/release" ] && [ "$FORCE" -eq 0 ]; then
		echo "[$config] $label: already present, $(grep '^JAVA_VERSION=' "$dest/release") (use -f to update)"
		return
	fi
	url="$(jvm_url "$config" "$pkg")"
	[ -n "$url" ] || { echo "[$config] $label: could not find a download URL" >&2; return 1; }
	# Resolve the API redirect to get the real archive name (and its extension)
	case "$url" in
		https://api.adoptium.net/*) url="$(curl -fsS -o /dev/null -w '%{redirect_url}' "$url")" ;;
	esac
	[ -n "$url" ] || { echo "[$config] $label: no Java $JAVA_VERSION runtime available" >&2; return 1; }

	echo "[$config] $label: downloading $url"
	work="$TMP/jvm-$config-$pkg"
	fetch_extract "$url" "$work"
	# The runtime home is the folder holding bin/java (macOS archives nest it in .../Contents/Home)
	java="$(find "$work" -type f \( -path '*/bin/java' -o -path '*/bin/java.exe' \) | head -1)"
	[ -n "$java" ] || { echo "[$config] $label: bin/java not found in archive" >&2; return 1; }
	home="$(dirname "$(dirname "$java")")"
	rm -rf "${dest:?}"
	mkdir -p "$JVM_DEST"
	mv "$home" "$dest"
	printf '%s\t%s\n' "$url" "$(date +%Y-%m-%d)" > "$dest/SOURCE.txt"
	echo "[$config] $label: installed $dest, $(grep '^JAVA_VERSION=' "$dest/release")"
}

########## JavaFX ##########

# Only the Linux config: SWT has its own browser on macOS and Windows, and Gluon has no ppc64le build
install_javafx() {
	local config="$1" dest="$ROOT/distrib/$1/javafx" url work lib
	[ "$config" = Linux ] || return 0
	if [ -f "$dest/javafx.web.jar" ] && [ "$FORCE" -eq 0 ]; then
		echo "[$config] JavaFX: already present, $(cat "$dest/VERSION.txt" 2>/dev/null) (use -f to update)"
		return
	fi
	url="https://download2.gluonhq.com/openjfx/$JAVAFX_VERSION/openjfx-${JAVAFX_VERSION}_linux-x64_bin-sdk.zip"
	echo "[$config] JavaFX: downloading $url"
	work="$TMP/javafx-$config"
	fetch_extract "$url" "$work"
	lib="$(dirname "$(find "$work" -name javafx.web.jar | head -1)")"
	[ -f "$lib/javafx-swt.jar" ] || { echo "[$config] JavaFX: javafx-swt.jar not found in archive" >&2; return 1; }
	rm -rf "${dest:?}"
	mkdir -p "$dest"
	# What FxBrowser needs: base, graphics, controls, media, web, the SWT bridge, and all the natives
	for j in javafx.base javafx.graphics javafx.controls javafx.media javafx.web javafx-swt jdk.jsobject; do
		cp "$lib/$j.jar" "$dest/"
	done
	cp "$lib"/*.so "$dest/"
	echo "$JAVAFX_VERSION" > "$dest/VERSION.txt"
	printf '%s\t%s\n' "$url" "$(date +%Y-%m-%d)" > "$dest/SOURCE.txt"
	echo "[$config] JavaFX: installed $dest ($(du -sh "$dest" | cut -f1 | tr -d ' '))"
}

# JDK of this machine (to compile, when the system has no JDK 27+)
if [ "$DO_JDK" -eq 1 ]; then
	case "$(uname -s)/$(uname -m)" in
		Darwin/arm64) host=MacSilicon ;;
		Darwin/x86_64) host=MacIntel ;;
		Linux/x86_64) host=Linux ;;
		Linux/aarch64) host=LinuxArm ;;
		Linux/ppc64le) host=PPC64le ;;
		*) host="" ;;
	esac
	if [ -n "$host" ]; then
		install_jvm "$host" jdk
	else
		echo "JDK: no download for $(uname -s)/$(uname -m), install a JDK $JAVA_VERSION+ yourself"
	fi
fi

for c in "${CONFIGS[@]}"; do
	[ "$DO_FFMPEG" -eq 1 ] && install_ffmpeg "$c"
	[ "$DO_JVM" -eq 1 ] && install_jvm "$c"
	[ "$DO_JAVAFX" -eq 1 ] && install_javafx "$c"
done
exit 0
