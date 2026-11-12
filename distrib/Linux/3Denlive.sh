#!/bin/sh
# Launches 3Denlive with the bundled JVM (./jre) if present, else the system java (Java 27+)
DIR="$( cd "$( dirname "$0" )" && pwd )"
cd "$DIR"
export GTK_OVERLAY_SCROLLING=0
# X11 (XWayland in a Wayland session): needed by JavaFX, which shows the web pages without WebKitGTK
export GDK_BACKEND="${GDK_BACKEND:-x11}"
JAVA=java
if [ -x "$DIR/jre/bin/java" ]; then
	JAVA="$DIR/jre/bin/java"
fi
exec "$JAVA" -Xmx4G -XX:G1PeriodicGCInterval=30000 --enable-native-access=ALL-UNNAMED -cp 3Denlive.jar:swt.jar:MetaDataExtractor.jar:xmpcore-6.0.6.jar:svgSalamander-1.1.2.jar:bsh-2.0b6.jar -Djava.library.path=./lib com.cubaix.TDenlive.TDenlive "$@"
