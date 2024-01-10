#! /bin/bash

set -e -o pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
pushd $SCRIPT_DIR

echo
echo "Compiling code..."
./gradlew uberJar
echo "Code compiled"
echo
