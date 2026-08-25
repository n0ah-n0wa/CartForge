# Deployment

This document describes how CartForge is packaged and delivered. Delivery artifacts
live in the repository: Docker image, Compose stack, Kubernetes manifests, Helm
chart, and GitHub Actions workflows.

## Local stack

```bash
cp .env.example .env
./mvnw package -DskipTests
docker compose up --build
```

Compose services: `application`, `postgres`, `redis`. Configuration comes from
environment variables (see `.env.example`).

## Container image

Production image properties:

- multi-stage build (`Dockerfile`);
- non-root runtime (UID 10001);
- no secrets in the image;
- readiness/liveness health checks;
- SIGTERM / graceful shutdown.

Published image reference:

```text
ghcr.io/<owner>/ecommerce-api:<git-sha>
```

Optional convenience tag on successful main publishes:

```text
ghcr.io/<owner>/ecommerce-api:latest
```

Production deploys must use the immutable SHA tag, never `latest`.

## CI / CD chain

```text
CI (pull_request + push to main)
  → ./mvnw verify
  → Docker build (no push)
  → Helm lint / template

Publish Image (after successful CI on main)
  → build from verified JAR
  → push ghcr.io/<owner>/ecommerce-api:<sha> (+ latest)

CD (after successful Publish Image on main)
  → Helm lint
  → Helm template (immutable SHA tag)
  → helm upgrade --install --atomic --wait
  → kubectl rollout status
  → post-deployment smoke test (`scripts/ci/smoke-test.sh`)
```

A failed rollout or smoke test fails the CD workflow. Helm `--atomic`
rolls the release back when the upgrade does not become ready.

### Post-deployment smoke test

`scripts/ci/smoke-test.sh` runs against the Service via `kubectl port-forward`
and must succeed for CD to pass:

| Check | How |
|---|---|
| Application ready | `/actuator/health/readiness` → `UP` (includes PostgreSQL `db` probe) |
| Health | `/actuator/health` and `/actuator/health/liveness` → `UP` |
| Public catalog | `GET /api/v1/categories`, `GET /api/v1/products` → `200` |
| Authentication | `POST /api/v1/auth/login` with unknown credentials → `401` (or `429`) |
| Database | Covered by readiness (`db` in readiness group); auth login also hits the user store |
| Redis | Not required for readiness. Catalog/auth success with Redis up **or** fail-open is acceptable |

Local usage against a running stack:

```bash
./scripts/ci/smoke-test.sh http://localhost:8080
```

Workflow files:

- `.github/workflows/ci.yml`
- `.github/workflows/publish-image.yml`
- `.github/workflows/cd.yml`

## Required GitHub configuration

CD uses the GitHub Environment named `production`. Create it under
**Settings → Environments → production**.

### Secrets (GitHub Environment `production`)

| Name | Required | Description |
|---|---|---|
| `KUBE_CONFIG` | yes | Kubeconfig for the target cluster. Accepts raw YAML **or** base64-encoded YAML. Never commit this value. |

The workflow authenticates to GHCR for image *publishing* with `GITHUB_TOKEN`
(Publish Image workflow). CD does not need registry push credentials.

Do **not** store `JWT_SECRET`, database passwords, or other application secrets
in GitHub Actions if they belong in the cluster Secret (see below).

### Variables (GitHub Environment `production`)

All variables are optional unless noted. When unset, Helm falls back to
`values-prod.yaml` defaults / chart defaults.

| Name | Required | Default | Description |
|---|---|---|---|
| `KUBE_NAMESPACE` | no | `cartforge` | Target namespace (`helm --create-namespace`) |
| `HELM_RELEASE_NAME` | no | `cartforge` | Helm release name |
| `APP_SECRET_NAME` | no | `cartforge-secrets` | Existing Kubernetes Secret with app credentials |
| `DATABASE_URL` | recommended | (values-prod) | JDBC URL for PostgreSQL |
| `DATABASE_HOST` | no | (values-prod) | Host used when URL is constructed / wait init |
| `DATABASE_USERNAME` | no | (values-prod) | Database username (password stays in the cluster Secret) |
| `REDIS_URL` | recommended | (values-prod) | Redis connection URL |
| `REDIS_HOST` | no | (values-prod) | Redis host |
| `CORS_ORIGINS` | recommended | (values-prod) | Explicit CORS allowlist |
| `INGRESS_HOST` | recommended | (values-prod) | Ingress hostname |
| `IMAGE_PULL_SECRET_NAME` | if GHCR private | unset | Name of a pre-created `imagePullSecret` in the namespace |

### Repository permissions

Workflows use least privilege:

| Workflow | Permissions |
|---|---|
| CI | `contents: read` |
| Publish Image | `contents: read`, `packages: write`, `actions: read` |
| CD | `contents: read` (+ environment secrets) |

## Required Kubernetes configuration

Provision these in the cluster **before** enabling CD. Nothing below belongs in Git.

### Namespace

```bash
kubectl create namespace cartforge
```

(CD can also create the namespace via Helm `--create-namespace`.)

### Application Secret

The chart expects an existing Secret (CD sets `secrets.create=false`):

```bash
kubectl create secret generic cartforge-secrets \
  --namespace cartforge \
  --from-literal=POSTGRES_PASSWORD='...' \
  --from-literal=JWT_SECRET='...'   # >= 32 characters, non-placeholder
```

Keys must match chart defaults (`POSTGRES_PASSWORD`, `JWT_SECRET`) unless you
override `secrets.postgresPasswordKey` / `secrets.jwtSecretKey`.

### Image pull (private GHCR packages)

If the package is private, create a pull secret and set
`IMAGE_PULL_SECRET_NAME` in the GitHub Environment:

```bash
kubectl create secret docker-registry ghcr-pull \
  --namespace cartforge \
  --docker-server=ghcr.io \
  --docker-username='<github-username>' \
  --docker-password='<PAT with read:packages>'
```

### Data stores

CD assumes **externally managed** PostgreSQL and Redis (connection settings via
GitHub variables / `values-prod.yaml`). In-cluster Postgres/Redis in the chart
are **demo infrastructure only** and stay disabled in production values.

### Ingress

An Ingress controller compatible with `ingressClassName: nginx` (or override via
values) must already exist. CD does not install an ingress controller.

## Manual deploy

After an image exists in GHCR:

```bash
# GitHub Actions → CD → Run workflow → supply full git SHA
```

Or locally (with kubeconfig and cluster Secret already present):

```bash
helm upgrade --install cartforge ./helm/cartforge \
  -n cartforge --create-namespace \
  -f helm/cartforge/values-prod.yaml \
  --set image.repository=ghcr.io/<owner>/ecommerce-api \
  --set image.tag=<git-sha> \
  --set secrets.create=false \
  --set secrets.existingSecret=cartforge-secrets \
  --atomic --wait --timeout 10m

kubectl rollout status deployment/cartforge -n cartforge
```

## Rolling updates

The chart defaults to:

- `replicas: 2`
- `maxSurge: 1`, `maxUnavailable: 0`
- readiness `/actuator/health/readiness`
- liveness `/actuator/health/liveness`
- `terminationGracePeriodSeconds: 35`
- `preStop` delay before SIGTERM handling

Unready pods do not receive Service traffic. CD fails if rollout or readiness
verification does not succeed.

## Related documents

- [architecture.md](architecture.md)
- [security.md](security.md)
- [ADR 0006](adr/0006-helm-kubernetes-deployment.md)
