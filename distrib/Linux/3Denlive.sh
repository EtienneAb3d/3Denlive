#!/bin/sh
# Launches 3Denlive with the bundled JVM (./jre) if present, else the system java (Java 27+)
DIR="$( cd "$( dirname "$0" )" && pwd )"
cd "$DIR"
export GTK_OVERLAY_SCROLLING=0
# X11 (XWayland in a Wayland session): needed by JavaFX, which shows the web pages without WebKitGTK
export GDK_BACKEND="${GDK_BACKEND:-x11}"
# Started from a snap application (VS Code installed as a snap...): its GTK / GIO module paths point to
# modules built against the libc of the snap, which make the WebKit network process crash (no page loads).
# Removed whenever they point into a snap (SNAP itself is not always passed on).
for V in GTK_PATH GTK_EXE_PREFIX GTK_IM_MODULE_FILE GIO_MODULE_DIR GDK_PIXBUF_MODULE_FILE GDK_PIXBUF_MODULEDIR LOCPATH GSETTINGS_SCHEMA_DIR LD_LIBRARY_PATH LD_PRELOAD; do
	eval "SNAP_V=\${$V:-}"
	case "$SNAP_V" in
		*/snap/*) unset "$V" ;;
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
		export XDG_DATA_DIRS="$SNAP_V" ;;
esac
unset SNAP_V
JAVA=java
if [ -x "$DIR/jre/bin/java" ]; then
	JAVA="$DIR/jre/bin/java"
fi
exec "$JAVA" -Xmx4G -XX:G1PeriodicGCInterval=30000 -XX:G1HeapRegionSize=32m --enable-native-access=ALL-UNNAMED -cp 3Denlive.jar:swt.jar:MetaDataExtractor.jar:xmpcore-6.0.6.jar:svgSalamander-1.1.2.jar:bsh-2.0b6.jar -Djava.library.path=./lib com.cubaix.TDenlive.TDenlive "$@"
