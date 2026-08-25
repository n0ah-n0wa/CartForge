#!/usr/bin/env bash
# Local CI validation helper. Mirrors the GitHub Actions packaging gates.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELM="${HELM:-helm}"
DOCKER="${DOCKER:-docker}"

cd "$ROOT"

echo "==> Java verify"
chmod +x mvnw
./mvnw -B verify

echo "==> Docker build"
test -n "$(ls target/ecommerce-api-*.jar)"
$DOCKER build -f Dockerfile -t cartforge-api:local-ci .

echo "==> Helm lint"
$HELM lint helm/cartforge -f helm/cartforge/values-dev.yaml
$HELM lint helm/cartforge -f helm/cartforge/values-prod.yaml

echo "==> Helm template"
$HELM template cartforge helm/cartforge -f helm/cartforge/values-dev.yaml > /tmp/cartforge-dev.yaml
$HELM template cartforge helm/cartforge -f helm/cartforge/values-prod.yaml \
  --set image.tag=local-ci > /tmp/cartforge-prod.yaml
test -s /tmp/cartforge-dev.yaml
test -s /tmp/cartforge-prod.yaml

if $HELM template cartforge helm/cartforge > /tmp/cartforge-default.yaml 2>/dev/null; then
  echo "Expected default chart values to fail without an environment values file" >&2
  exit 1
fi

echo "All local CI checks passed."
