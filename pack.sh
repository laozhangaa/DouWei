#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if [[ ! -f local.properties ]]; then
  echo "Copy local.properties.example to local.properties and configure your SDK and signing first." >&2
  exit 1
fi
bash ./gradlew :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease "$@"
