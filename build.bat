@echo off
REM Builds target\module-updater.jar (Java 8 bytecode) and runs the end-to-end tests. Needs JDK 9+.
setlocal
cd /d "%~dp0"
if exist target rmdir /s /q target
mkdir target\classes target\test-classes
dir /s /b src\main\java\*.java > target\sources.txt
javac --release 8 -d target\classes @target\sources.txt || exit /b 1
javac -nowarn -cp target\classes -d target\test-classes src\test\java\org\facility\updater\SelfTest.java || exit /b 1
java -cp target\classes;target\test-classes org.facility.updater.SelfTest || exit /b 1
echo Main-Class: org.facility.updater.Main> target\MANIFEST.MF
jar cfm target\module-updater.jar target\MANIFEST.MF -C target\classes . || exit /b 1
echo Built target\module-updater.jar
