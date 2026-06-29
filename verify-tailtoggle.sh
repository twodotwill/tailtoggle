#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/pebblecode"
node --check src/pkjs/index.js
node test/bridge.test.js
pebble build
cd ..
mkdir -p dist
cp pebblecode/build/pebblecode.pbw dist/tailtoggle.pbw
printf '%s\n' "Built dist/tailtoggle.pbw"
