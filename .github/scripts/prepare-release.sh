#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?Set DEPS_TOKEN with Contents read access to TF-Minecraft/ServerAssets}"
ref=8a44414cd5b74b7d1c7a258ccf5a86a5f6e0294b
mkdir -p libs
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/b7156eab5677/spigot-api.jar?ref=$ref" > "libs/spigot-api.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/runtime/plugins/ProtocolLib.jar?ref=$ref" > "libs/ProtocolLib.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/0116d714822b/ItemsAdder_3.5.0-r2.jar?ref=$ref" > "libs/ItemsAdder_3.5.0-r2.jar"
sha256sum --check .github/dependencies.sha256
