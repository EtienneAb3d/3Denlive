# 3Denlive

Stereoscopic photo/video editor

Documentation, videos and tutorials: <a href="https://3denlive.com">3Denlive</a>

# Download

Each package is self-contained: it includes its own Java runtime and ffmpeg, so nothing else needs to be installed.

Download 3Denlive for <a href="https://3denlive.com/3DenliveWin-x64.zip">Windows (x64)</a><br/>
Download 3Denlive for <a href="https://3denlive.com/3DenliveMacSilicon.tar.gz">macOS Apple Silicon (M1 and later)</a><br/>
Download 3Denlive for <a href="https://3denlive.com/3DenliveMacIntel.tar.gz">macOS Intel</a><br/>
Download 3Denlive for <a href="https://3denlive.com/3DenliveLinux.tar.gz">Linux (x64)</a><br/>
Download 3Denlive for <a href="https://3denlive.com/3DenlivePPC64le.tar.gz">Linux PPC64le</a> (uses the ffmpeg installed on the system)<br/>

To launch it, extract the archive, then run `3Denlive.bat` (Windows) or `3Denlive.sh` (macOS, Linux).
On Linux, the news and demos pages are shown inside the application only when WebKitGTK is installed (Debian / Ubuntu: `sudo apt install libwebkit2gtk-4.1-0`); otherwise they open in the web browser of the system.

<img src="https://3denlive.com/LatestScreenCopyEN.png?date=2026-11-11"/>

# Dev config

Configurations: `Linux`, `PPC64le`, `Win-x64`, `MacIntel`, `MacSilicon`
(one folder per configuration in `distrib/`, holding its SWT library and launcher).

1- Install a JDK 27 or later, for example Eclipse Temurin<br/>
https://adoptium.net/

2- Clone the repository<br/>
`git clone https://github.com/EtienneAb3d/3Denlive.git`

3- Download the ffmpeg binaries into `distrib/ffmpeg/` (they are not stored in Git)<br/>
`./Install-Dev.sh --ffmpeg-only` (all configurations), or for example `./Install-Dev.sh --ffmpeg-only MacSilicon`<br/>
Without `--ffmpeg-only`, it also downloads the Java runtimes (`JVMs/`) needed to build the deliveries.<br/>
Run it again with `-f` later to update to the latest releases.<br/>
On Windows, run it from Git Bash.

4- Compile into `bin/`

```sh
javac --release 27 -encoding UTF-8 -d bin -cp "distrib/*:distrib/Linux/swt.jar" $(find src -name '*.java')
```

5- Launch 3Denlive from `distrib/` (ffmpeg, lumas and frames are loaded from there),
with `src/` on the classpath (language files, images and fonts) and the SWT library of your configuration.
Example for macOS Apple Silicon:

```sh
cd distrib
java -XstartOnFirstThread -Xmx4G -XX:G1PeriodicGCInterval=30000 --enable-native-access=ALL-UNNAMED \
  -cp "../bin:../src:*:MacSilicon/swt.jar" -Djava.library.path=MacSilicon/lib \
  com.cubaix.TDenlive.TDenlive
```

On macOS Intel, use `MacIntel/` instead of `MacSilicon/`.
On Linux, use `Linux/` (or `PPC64le/swt-PPC64le.jar` and `PPC64le/lib`), remove `-XstartOnFirstThread`, and set `GTK_OVERLAY_SCROLLING=0`.
On Windows (Git Bash), use `Win-x64/swt.jar`, remove `-XstartOnFirstThread` and `-Djava.library.path`, and separate the classpath entries with `;` (in the `javac` command too).

**With VS Code**: open the repository folder and install the recommended Java extension pack.
The project is configured in `.vscode/`. Run and debug from the Run view, choosing the configuration for your platform
(e.g. "3Denlive (macOS Apple Silicon)"). `Install-Dev.sh` and `build_delivery.sh` are available under Terminal > Run Task.
VS Code must use a JDK 27+ (setting `java.jdt.ls.java.home`, or the `JAVA_HOME` environment variable).

6- You may start experimenting with the Demo Packs available on the website<br/>
https://3denlive.com/demos/en.php

# Build the deliveries

`./Install-Dev.sh` then `./build_delivery.sh` (all configurations), or for example `./build_delivery.sh MacSilicon`.<br/>
It compiles the application (JDK 27+ required) and creates one self-contained archive per configuration in `delivery/`:<br/>
`3DenliveLinux.tar.gz`, `3DenlivePPC64le.tar.gz`, `3DenliveMacIntel.tar.gz`, `3DenliveMacSilicon.tar.gz` and `3DenliveWin-x64.zip`.<br/>
Each archive includes its own Java runtime, the platform's ffmpeg, and a launcher (`3Denlive.sh`, or `3Denlive.bat` on Windows).

<hr>
This tool is a demonstration of our know-how.<br/>
If you are interested in a commercial/industrial AI linguistic project, contact us:<br/>
https://cubaix.com
