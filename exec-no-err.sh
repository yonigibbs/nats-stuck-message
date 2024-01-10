#! /bin/bash

set -e -o pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
pushd $SCRIPT_DIR

java -jar ./build/libs/nats-stuck-message-1.0-SNAPSHOT-uber.jar --no-err

popd