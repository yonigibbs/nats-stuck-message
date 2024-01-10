#! /bin/bash

set -e -o pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
pushd $SCRIPT_DIR

echo
echo "Compiling code..."
./gradlew uberJar
echo "Code compiled"
echo

echo
echo "Bringing up Docker environment..."
docker compose down
docker compose up -d
echo "Docker environment up"

echo
echo "Executing code..."

COUNT=1
while true
do
  echo
  echo "Starting execution $COUNT..."
  java -jar ./build/libs/nats-stuck-message-1.0-SNAPSHOT-uber.jar
  echo "Execution $COUNT complete"
  ((COUNT++))
  sleep 2
done

popd