#!/usr/bin/env bash
# Compiles src/ into bin/ and launches 3Denlive in the DEV configuration:
# classes from bin/, resources (languages, images, fonts, VERSION.txt) from src/,
# working directory distrib/ (ffmpeg/, lumas/ and frames/ are loaded from there).
#
# Usage: ./run_3De.sh [-n] [config] [-- app args...]
#   config : Linux PPC64le MacIntel MacSilicon (default: detected from the OS and CPU)
#   -n     : no compilation, run the classes already in bin/
# Compiles with a JDK 27+: JAVA_HOME, the PATH, JVMs/JDK (./Install-Dev.sh --jdk-only) or an
# installed JDK (see find_jdk.sh). Runs with the JRE of the config, JVMs/<config>, as the
# delivery does (./Install-Dev.sh --jvm-only <config>), else with any Java 27+ found.
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
		-h|--help) sed -n '2,13p' "$0"; exit 0 ;;
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

# Runs with the JRE of the delivery of this machine (JVMs/<config>, ./Install-Dev.sh <config>),
# the one bundled in 3Denlive<config>; otherwise with any Java 27+ found (find_jdk.sh).
. "$ROOT/find_jdk.sh"
JAVA_BIN="$ROOT/JVMs/$CONFIG/bin"
if [ ! -x "$JAVA_BIN/java" ] || [ "$(java_major "$JAVA_BIN/java")" -lt "$JAVA_RELEASE" ]; then
	echo "No Java $JAVA_RELEASE+ in JVMs/$CONFIG (./Install-Dev.sh --jvm-only $CONFIG): looking for another one" >&2
	JAVA_BIN="$(find_java_bin "$JAVA_RELEASE" java)"
	if [ -z "$JAVA_BIN" ]; then
		echo "No Java $JAVA_RELEASE+ found (run ./Install-Dev.sh $CONFIG)" >&2; exit 1
	fi
fi
JAVA="$JAVA_BIN/java"

if [ "$COMPILE" = 1 ]; then
	JAVAC_BIN="$(find_java_bin "$JAVA_RELEASE" javac)"
	if [ -z "$JAVAC_BIN" ]; then
		echo "No JDK $JAVA_RELEASE+ found (javac $(command -v javac >/dev/null && java_major javac || echo "absent") on the PATH):" >&2
		echo "set JAVA_HOME, or run ./Install-Dev.sh --jdk-only" >&2; exit 1
	fi
	JAVAC="$JAVAC_BIN/javac"
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
	*) export GTK_OVERLAY_SCROLLING=0
	   export GDK_BACKEND="${GDK_BACKEND:-x11}" ;; # JavaFX (web pages without WebKitGTK) needs X11
esac
# Started from a snap application (VS Code installed as a snap...): its GTK / GIO module paths point to
# modules built against the libc of the snap, which make the WebKit network process crash (no page loads).
# Removed whenever they point into a snap (SNAP itself is not always passed on).
for V in GTK_PATH GTK_EXE_PREFIX GTK_IM_MODULE_FILE GIO_MODULE_DIR GDK_PIXBUF_MODULE_FILE GDK_PIXBUF_MODULEDIR LOCPATH GSETTINGS_SCHEMA_DIR LD_LIBRARY_PATH LD_PRELOAD; do
	eval "SNAP_V=\${$V:-}"
	case "$SNAP_V" in
		*/snap/*) unset "$V"; echo "Snap environment: $V removed" >&2 ;;
	esac
done
for V in $(env | sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)_VSCODE_SNAP_ORIG=.*/\1/p'); do
	eval "export $V=\"\${${V}_VSCODE_SNAP_ORIG}\""
done
case "${XDG_DATA_DIRS:-}" in
	*/snap/*) SNAP_V=""
		OLD_IFS="$IFS"; IFS=:
		for D in $XDG_DATA_DIRS; do
			case "$D" in */snap/*) ;; *) SNAP_V="${SNAP_V:+$SNAP_V:}$D" ;; esac
		done
		IFS="$OLD_IFS"
		export XDG_DATA_DIRS="$SNAP_V"; echo "Snap environment: XDG_DATA_DIRS cleaned" >&2 ;;
esac
unset SNAP_V

LOG_DIR="$ROOT/log"
LOG_FILE="$LOG_DIR/3De.log"
mkdir -p "$LOG_DIR"
echo "Running 3Denlive $(tr -d '[:space:]' < "$ROOT/src/com/cubaix/TDenlive/VERSION.txt") ($CONFIG) ..."
echo "Java: $JAVA"
echo "Log: $LOG_FILE"
cd "$DISTRIB"
exec "$JAVA" "${JVM_OPTS[@]}" \
	-cp "$ROOT/bin:$ROOT/src:$DISTRIB/*:$SWT_JAR" \
	-Djava.library.path="$DISTRIB/$CONFIG/lib" \
	com.cubaix.TDenlive.TDenlive ${APP_ARGS[@]+"${APP_ARGS[@]}"} > "$LOG_FILE" 2>&1
