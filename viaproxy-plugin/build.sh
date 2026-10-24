#!/bin/sh
# Builds ViaEnchant.jar against a ViaProxy jar. Usage: [VERSION=1.1.0] ./build.sh [path/to/ViaProxy.jar]
set -e
cd "$(dirname "$0")"

VIAPROXY_JAR="${1:-$HOME/Downloads/ViaProxy-3.4.14+java8.jar}"
JDK="${JAVA_HOME:-/usr/lib/jvm/temurin-17-jdk}"
VERSION="${VERSION:-1.1.0}"

rm -rf build
mkdir -p build/classes
"$JDK/bin/javac" --release 8 -encoding UTF-8 -cp "$VIAPROXY_JAR" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
sed -i "s/\${version}/$VERSION/" build/classes/viaproxy.yml
"$JDK/bin/jar" cf build/ViaEnchant.jar -C build/classes .
echo "Built $(pwd)/build/ViaEnchant.jar"
