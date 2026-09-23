#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after downloading the pinned JARs.
# Hash-qualified versions prevent different private JARs sharing a Maven cache key.
sha256sum --check .github/dependencies.sha256

mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ProtocolLib-5.5.0-SNAPSHOT.jar" -DgroupId="local" -DartifactId="ProtocolLib" \
    -Dversion="5.5.0-SNAPSHOT-tfmc-355f7117af95" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ItemsAdder-4.0.18.jar" -DgroupId="local" -DartifactId="ItemsAdder" \
    -Dversion="4.0.18-tfmc-5a01b37bd744" -Dpackaging=jar -DgeneratePom=true "$@"
