#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./apps/api/mvnw -f apps/api/pom.xml spotless:check verify
npm --prefix apps/web run lint
npm --prefix apps/web run typecheck
npm --prefix apps/web test
npm --prefix apps/web run e2e
