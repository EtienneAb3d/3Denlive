#!/usr/bin/env bash
# Compiles src/ into bin/ and launches 3Denlive in the DEV configuration:
# classes from bin/, resources (languages, images, fonts, VERSION.txt) from src/,
# working directory distrib/ (ffmpeg/, lumas/ and frames/ are loaded from there).
#
# Usage: ./run_3De.sh [-n] [config] [-- app args...]
#   config : Linux PPC64le MacIntel MacSilicon (default: detected from the OS and CPU)
#   -n     : no compilation, run the classes already in bin/
# Requires a JDK 27+ (javac and java) on the PATH, or in JAVA_HOME.
# For videos, run `./Install-Dev.sh --ffmpeg-only` once.
# The app's standard and error outputs go to log/3De.log (replaced at each run).

set -euo pipefail

JAVA_RELEASE=27
ROOT="$(cd "$(dirname "$0")" && pwd)"
DISTRIB="$ROOT/distrib"
COMPILE=1
CONFIG=""
APP_ARGS=()

while [ $# -gt 0 ]; do
	case "$1" in
		-n|--no-compile) COMPILE=0 ;;
		-h|--help) sed -n '2,11p' "$0"; exit 0 ;;
		Linux|PPC64le|MacIntel|MacSilicon) CONFIG="$1" ;;
		--) shift; APP_ARGS=("$@"); break ;;
		*) echo "Unknown argument: $1 (see --help)" >&2; exit 1 ;;
	esac
	shift
done

# Config from the OS and the CPU (an x86_64 SWT does not load on an arm64 JVM)
if [ -z "$CONFIG" ]; then
	case "$(uname -s)/$(uname -m)" in
		Darwin/arm64) CONFIG=MacSilicon ;;
		Darwin/x86_64) CONFIG=MacIntel ;;
		Linux/x86_64) CONFIG=Linux ;;
		Linux/ppc64le) CONFIG=PPC64le ;;
		*) echo "Unsupported platform: $(uname -s)/$(uname -m) (on Windows, use the VS Code launch configuration)" >&2; exit 1 ;;
	esac
fi

SWT_JAR="$DISTRIB/$CONFIG/swt.jar"
[ "$CONFIG" = PPC64le ] && SWT_JAR="$DISTRIB/$CONFIG/swt-PPC64le.jar"

JAVA=java
JAVAC=javac
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
	JAVA="$JAVA_HOME/bin/java"
	JAVAC="$JAVA_HOME/bin/javac"
fi

if [ "$COMPILE" = 1 ]; then
	echo "Compiling into bin/ ..."
	# From scratch, so that no class of a deleted source stays in bin/
	rm -rf "$ROOT/bin"
	find "$ROOT/src" -name '*.java' > "$ROOT/bin.sources.tmp"
	trap 'rm -f "$ROOT/bin.sources.tmp"' EXIT
	"$JAVAC" --release "$JAVA_RELEASE" -nowarn -encoding UTF-8 -d "$ROOT/bin" \
		-cp "$DISTRIB/*:$SWT_JAR" @"$ROOT/bin.sources.tmp"
	rm -f "$ROOT/bin.sources.tmp"
fi

JVM_OPTS=(-Xmx4G -XX:G1PeriodicGCInterval=30000 --enable-native-access=ALL-UNNAMED)
case "$CONFIG" in
	Mac*) JVM_OPTS=(-XstartOnFirstThread "${JVM_OPTS[@]}") ;;
	*) export GTK_OVERLAY_SCROLLING=0 ;;
esac

LOG_DIR="$ROOT/log"
LOG_FILE="$LOG_DIR/3De.log"
mkdir -p "$LOG_DIR"
echo "Running 3Denlive $(tr -d '[:space:]' < "$ROOT/src/com/cubaix/TDenlive/VERSION.txt") ($CONFIG) ..."
echo "Log: $LOG_FILE"
cd "$DISTRIB"
exec "$JAVA" "${JVM_OPTS[@]}" \
	-cp "$ROOT/bin:$ROOT/src:$DISTRIB/*:$SWT_JAR" \
	-Djava.library.path="$DISTRIB/$CONFIG/lib" \
	com.cubaix.TDenlive.TDenlive ${APP_ARGS[@]+"${APP_ARGS[@]}"} > "$LOG_FILE" 2>&1
