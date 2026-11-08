#!/usr/bin/env bash
# Builds one self-contained delivery per configuration in delivery/:
#   3Denlive<config>.tar.gz for Linux, PPC64le, MacIntel, MacSilicon (keeps the executable
#   permissions of the launcher, ffmpeg and jre/bin/*, and the runtime symlinks)
#   3Denlive<config>.zip    for Win-x64
# Each archive holds a 3Denlive<config>/ folder with the app jar and libraries, the
# platform SWT, lumas/ and frames/, the platform ffmpeg (ffmpeg/), the Java runtime
# (jre/) and a launcher using that runtime (3Denlive.sh, or 3Denlive.bat on Windows).
#
# Usage: ./build_delivery.sh [config...]
#   configs: Linux PPC64le Win-x64 MacIntel MacSilicon (default: all)
#
# Prerequisites: a JDK >= JAVA_RELEASE (javac, jar), tar, zip, and ./Install-Dev.sh run
# beforehand (it fills distrib/ffmpeg/ and JVMs/).

set -euo pipefail

JAVA_RELEASE=27   # bytecode level; the JVMs prepared by Install-Dev.sh must be at least this version
ALL_CONFIGS=(Linux PPC64le Win-x64 MacIntel MacSilicon)
COMMON_JARS=(MetaDataExtractor.jar xmpcore-6.0.6.jar svgSalamander-1.1.2.jar bsh-2.0b6.jar)

ROOT="$(cd "$(dirname "$0")" && pwd)"
DISTRIB="$ROOT/distrib"
DELIVERY="$ROOT/delivery"
BUILD="$DELIVERY/build"

CONFIGS=()
for arg in "$@"; do
	case "$arg" in
		-h|--help) sed -n '2,14p' "$0"; exit 0 ;;
		Linux|PPC64le|Win-x64|MacIntel|MacSilicon) CONFIGS+=("$arg") ;;
		*) echo "Unknown argument: $arg (configs: ${ALL_CONFIGS[*]})" >&2; exit 1 ;;
	esac
done
[ ${#CONFIGS[@]} -eq 0 ] && CONFIGS=("${ALL_CONFIGS[@]}")

ffmpeg_name() {
	case "$1" in
		Linux)      echo "ffmpeg-amd64" ;;
		Win-x64)    echo "ffmpeg-amd64.exe" ;;
		MacIntel)   echo "ffmpeg-x86_64-osx" ;;
		MacSilicon) echo "ffmpeg-aarch64-osx" ;;
		*)          echo "" ;;   # PPC64le: no static build, uses the system ffmpeg
	esac
}

########## Checks ##########

for tool in javac jar tar zip; do
	command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
JDK_MAJOR="$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')"
if [ "$JDK_MAJOR" -lt "$JAVA_RELEASE" ]; then
	echo "javac $JDK_MAJOR found, JDK $JAVA_RELEASE+ required" >&2; exit 1
fi

MISSING=0
for c in "${CONFIGS[@]}"; do
	if [ ! -f "$ROOT/JVMs/$c/release" ]; then
		echo "[$c] missing JVM: JVMs/$c" >&2; MISSING=1
	elif ! grep -qE "^JAVA_VERSION=\"($JAVA_RELEASE|[0-9]{3,}|[3-9][0-9]|2[89])([.\"])" "$ROOT/JVMs/$c/release"; then
		echo "[$c] JVMs/$c is older than Java $JAVA_RELEASE: $(grep '^JAVA_VERSION=' "$ROOT/JVMs/$c/release")" >&2; MISSING=1
	fi
	f="$(ffmpeg_name "$c")"
	if [ -n "$f" ] && [ ! -f "$DISTRIB/ffmpeg/$f" ]; then
		echo "[$c] missing ffmpeg: distrib/ffmpeg/$f" >&2; MISSING=1
	fi
done
if [ "$MISSING" -ne 0 ]; then
	echo "Run ./Install-Dev.sh ${CONFIGS[*]} first." >&2; exit 1
fi

########## Compile the application jar ##########

echo "Compiling (javac --release $JAVA_RELEASE)..."
rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
find "$ROOT/src" -name '*.java' > "$BUILD/sources.txt"
javac --release "$JAVA_RELEASE" -nowarn -encoding UTF-8 -d "$BUILD/classes" \
	-cp "$DISTRIB/*:$DISTRIB/Linux/swt.jar" @"$BUILD/sources.txt"
# Resources (language files, images, fonts) next to the classes, as Eclipse does
(cd "$ROOT/src" && find . -type f ! -name '*.java' ! -name '.DS_Store' | while read -r f; do
	mkdir -p "$BUILD/classes/$(dirname "$f")"
	cp "$f" "$BUILD/classes/$f"
done)
jar --create --file "$BUILD/3Denlive.jar" --main-class com.cubaix.TDenlive.TDenlive -C "$BUILD/classes" .
VERSION="$(tr -d '[:space:]' < "$ROOT/src/com/cubaix/TDenlive/VERSION.txt")"
echo "3Denlive $VERSION: $BUILD/3Denlive.jar"

########## One zip per configuration ##########

for c in "${CONFIGS[@]}"; do
	name="3Denlive$c"
	stage="$BUILD/stage/$name"
	echo "[$c] assembling $name..."
	rm -rf "$stage"
	mkdir -p "$stage"

	cp "$BUILD/3Denlive.jar" "$stage/"
	for j in "${COMMON_JARS[@]}"; do cp "$DISTRIB/$j" "$stage/"; done
	cp -RP "$DISTRIB/lumas" "$DISTRIB/frames" "$stage/"
	# Platform files: SWT jar + natives, launcher
	cp -RP "$DISTRIB/$c/." "$stage/"
	f="$(ffmpeg_name "$c")"
	if [ -n "$f" ]; then
		mkdir -p "$stage/ffmpeg"
		cp "$DISTRIB/ffmpeg/$f" "$stage/ffmpeg/"
		chmod 755 "$stage/ffmpeg/$f"
	fi
	cp -RP "$ROOT/JVMs/$c" "$stage/jre"
	rm -f "$stage/jre/SOURCE.txt"
	find "$stage" -name '.DS_Store' -delete
	[ -f "$stage/3Denlive.sh" ] && chmod 755 "$stage/3Denlive.sh"

	if [ "$c" = "Win-x64" ]; then
		archive="$DELIVERY/$name.zip"
		rm -f "$archive"
		(cd "$BUILD/stage" && zip -qr -X "$archive" "$name")
	else
		archive="$DELIVERY/$name.tar.gz"
		rm -f "$archive"
		# COPYFILE_DISABLE: no macOS ._* resource files in the archive
		(cd "$BUILD/stage" && COPYFILE_DISABLE=1 tar -czf "$archive" "$name")
	fi
	echo "[$c] $archive ($(du -h "$archive" | cut -f1 | tr -d ' '))"
done

rm -rf "$BUILD/stage"
echo "Done."
