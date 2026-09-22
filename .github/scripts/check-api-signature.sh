#!/usr/bin/env bash
set -euo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
baseline="$repository_root/.github/api-v1.signature"
api_jar=${1:-}

if [[ -z "$api_jar" ]]; then
  api_jar=$(find "$repository_root/api/target" -maxdepth 1 -type f \
    -name 'velocity-limbo-handler-api-*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)
fi

if [[ -z "$api_jar" || ! -f "$api_jar" ]]; then
  echo "API JAR not found. Run ./mvnw -pl api -am package first." >&2
  exit 1
fi

current=$(mktemp)
trap 'rm -f "$current"' EXIT

jar tf "$api_jar" \
  | awk '/^com\/akselglyholt\/velocitylimbohandler\/api\/.*\.class$/ && !/package-info\.class$/ {
      sub(/\.class$/, ""); gsub(/\//, "."); print
    }' \
  | sort \
  | while IFS= read -r class_name; do
      javap -classpath "$api_jar" -public -constants "$class_name"
    done > "$current"

if [[ "${UPDATE_API_SIGNATURE:-false}" == "true" ]]; then
  cp "$current" "$baseline"
  echo "Updated $baseline"
  exit 0
fi

if ! diff -u "$baseline" "$current"; then
  echo >&2
  echo "Public API signature changed." >&2
  echo "Review compatibility, update Javadocs and the wiki, then regenerate intentionally with:" >&2
  echo "UPDATE_API_SIGNATURE=true .github/scripts/check-api-signature.sh '$api_jar'" >&2
  exit 1
fi

echo "Public API signature matches $baseline"
