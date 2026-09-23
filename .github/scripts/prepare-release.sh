#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?Set DEPS_TOKEN with Contents read access to TF-Minecraft/ServerAssets}"
ref=4b80431398e4ff35d703cad915b7ee4e5924a763
mkdir -p libs
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/355f7117af95/ProtocolLib-5.5.0-SNAPSHOT.jar?ref=$ref" > "libs/ProtocolLib-5.5.0-SNAPSHOT.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/5a01b37bd744/ItemsAdder-4.0.18.jar?ref=$ref" > "libs/ItemsAdder-4.0.18.jar"
bash .github/scripts/install-local-dependencies.sh "$@"
