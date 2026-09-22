#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after downloading the pinned JARs.
# Hash-qualified versions prevent different private JARs sharing a Maven cache key.
sha256sum --check .github/dependencies.sha256

mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ProtocolLib.jar" -DgroupId="local" -DartifactId="ProtocolLib" \
    -Dversion="1.0-tfmc-ee2e7ab9b538" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ItemsAdder_3.5.0-r2.jar" -DgroupId="local" -DartifactId="ItemsAdder" \
    -Dversion="3.5.0-tfmc-0116d714822b" -Dpackaging=jar -DgeneratePom=true "$@"
