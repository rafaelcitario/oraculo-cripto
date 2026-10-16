#!/usr/bin/env bash
# Compila o núcleo + rede + sessão com um JDK comum e roda os dois testes.
# Precisa de uma implementação do org.json (no Android ela já vem no sistema):
#   git clone --depth 1 https://github.com/stleary/JSON-java.git /tmp/JSON-java
# Uso: tools/run-tests.sh [/caminho/para/JSON-java/src/main/java]
set -euo pipefail
cd "$(dirname "$0")/.."
JSON_SRC="${1:-/tmp/JSON-java/src/main/java}"
rm -rf out && mkdir out
javac -encoding UTF-8 -d out \
  "$JSON_SRC"/org/json/*.java \
  app/src/main/java/com/oraculo/core/*.java \
  app/src/main/java/com/oraculo/net/*.java \
  app/src/main/java/com/oraculo/app/Session.java \
  tools/CoreSelfTest.java tools/SessionTest.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp out CoreSelfTest
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp out SessionTest
