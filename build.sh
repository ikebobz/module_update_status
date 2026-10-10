#!/bin/sh
# Builds target/module-updater.jar (Java 8 bytecode) and runs the end-to-end tests. Needs JDK 9+.
set -e
cd "$(dirname "$0")"
rm -rf target && mkdir -p target/classes target/test-classes
javac --release 8 -d target/classes $(find src/main/java -name '*.java')
javac -nowarn -cp target/classes -d target/test-classes $(find src/test/java -name '*.java')
java -cp target/classes:target/test-classes org.facility.updater.SelfTest
printf 'Main-Class: org.facility.updater.Main\n' > target/MANIFEST.MF
jar cfm target/module-updater.jar target/MANIFEST.MF -C target/classes .
echo "Built target/module-updater.jar"
