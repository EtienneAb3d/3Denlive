@echo off
rem Launches 3Denlive with the bundled JVM (jre\) if present, else the system javaw (Java 27+)
cd /d "%~dp0"
set JAVA=javaw
if exist "%~dp0jre\bin\javaw.exe" set JAVA="%~dp0jre\bin\javaw.exe"
start "3Denlive" %JAVA% -Xmx4G -XX:G1PeriodicGCInterval=30000 --enable-native-access=ALL-UNNAMED -cp "3Denlive.jar;swt.jar;MetaDataExtractor.jar;xmpcore-6.0.6.jar;svgSalamander-1.1.2.jar;bsh-2.0b6.jar" com.cubaix.TDenlive.TDenlive %*
