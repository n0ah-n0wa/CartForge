# CartForge

Production-style e-commerce REST API implemented as a Java 21 modular monolith on Spring Boot 3.5.

`SPECIFICATIONS.md` is the authoritative requirements document. This README describes the **current** repository state.

## Overview

CartForge exposes a versioned HTTP API under `/api/v1` for:

- customer registration and JWT authentication;
- role-based authorization (`CUSTOMER`, `ADMIN`);
- product and category catalog management;
- product search, filtering, sorting, and pagination;
- shopping carts and transactional checkout;
- order lifecycle and inventory consistency;
- PostgreSQL persistence with Flyway migrations;
- Redis catalog caching with fail-open behaviour;
- Docker, Kubernetes, Helm, and GitHub Actions delivery.

Architecture is a **modular monolith** (one deployable process), not a microservice system.

**Out of scope:** real payment processing, card storage, shipping providers, email/SMS, external search engines, Kafka, frontend, and mobile clients.

## Architecture

All business domains run in one Spring Boot process. PostgreSQL is the durable source of truth. Redis caches read-heavy catalog data and must not take the API offline when unavailable.

```text
                         ┌─────────────────────┐
                         │       Client        │
                         └──────────┬──────────┘
                                    ▼
                         ┌─────────────────────┐
                         │ Ingress / HTTP      │
                         └──────────┬──────────┘
                                    ▼
                  ┌──────────────────────────────────┐
                  │          Spring Boot API         │
                  │  auth · category · product       │
                  │  cart · order · inventory        │
                  │  common (security, errors, …)    │
                  └───────────────┬──────────────────┘
                    ┌─────────────┴─────────────┐
                    ▼                           ▼
             ┌─────────────┐             ┌─────────────┐
             │ PostgreSQL  │             │    Redis    │
             │ (source of  │             │ (cache /    │
             │  truth)     │             │  rate limit)│
             └─────────────┘             └─────────────┘
```

Package layout (feature-oriented):

```text
src/main/java/com/example/ecommerce/
├── auth/          # register, login, JWT
├── user/          # profile API (`GET /api/v1/users/me`) and persistence
├── category/
├── product/
├── cart/
├── order/         # customer checkout + /api/v1/admin/orders
├── inventory/     # internal stock operations (no dedicated HTTP API)
└── common/        # security, errors, pagination, cache, logging, config
```

Details: [docs/architecture.md](docs/architecture.md). Decisions: [docs/adr/](docs/adr/).

## Technology Stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.x |
| Build | Maven Wrapper (`./mvnw`) |
| Web | Spring MVC |
| Persistence | Spring Data JPA, Hibernate (`ddl-auto=validate`) |
| Database | PostgreSQL 16 |
| Migrations | Flyway (`V1`–`V11`) |
| Security | Spring Security OAuth2 resource server (JWT HS256) |
| Passwords | Delegating `PasswordEncoder` (BCrypt by default) |
| Validation | Jakarta Bean Validation |
| Cache | Spring Cache + Redis (fail-open) |
| Rate limiting | Redis fixed-window on auth endpoints (fail-open) |
| API docs | Springdoc OpenAPI (enabled in `dev`) |
| Tests | JUnit 5, Mockito, Testcontainers |
| Quality | Checkstyle, SpotBugs |
| Containers | Docker, Docker Compose |
| Orchestration | Kubernetes, Helm |
| CI/CD | GitHub Actions → GHCR |

## Features

Implemented today:

- Registration and login; passwords hashed; JWT access tokens (`Authorization: Bearer`)
- Public catalog reads; admin catalog writes (`@RequireAdmin`)
- Product search (`category`, `minPrice`/`maxPrice`, `search`, allowlisted `sort`, pagination)
- Customer cart CRUD
- Transactional checkout with order-line snapshots, stock decrement, cart clear
- Checkout `Idempotency-Key` header (optional but recommended for safe retries; PostgreSQL-backed when supplied)
- Order history, customer cancel (`PENDING`/`CONFIRMED`), admin status updates
- Optimistic locking on concurrent inventory updates
- Redis cache for catalog reads; graceful degradation when Redis is down
- Auth rate limiting on login/register
- Centralized JSON errors, correlation IDs, Actuator health/liveness/readiness, Prometheus scrape endpoint

Also included:

- `GET /api/v1/users/me` authenticated profile
- Optional deterministic **dev seed** (`app.seed.enabled`, never on `prod`)

Not implemented:

- Real payment, shipping, or notification integrations

## Prerequisites

- Java 21
- Docker (Compose for local stack; Docker required for Testcontainers in CI/tests)
- Maven Wrapper (committed; no global Maven install required)
- Optional: `kubectl`, Helm 3.x for cluster work

## Local Setup

```bash
git clone <repository-url>
cd CartForge
cp .env.example .env
# Edit .env: set POSTGRES_PASSWORD, REDIS_PASSWORD, and JWT_SECRET (JWT_SECRET ≥ 32 characters)
docker compose up --build
```

Compose builds with `docker/Dockerfile.ci` by default (no host `mvn package` required).  
API: `http://localhost:8080`  
Swagger UI (`dev` profile): `http://localhost:8080/swagger-ui.html`  
OpenAPI JSON: `http://localhost:8080/v3/api-docs`

When seed is enabled (`dev` + `APP_SEED_ENABLED=true`):

| Email | Password | Role |
|---|---|---|
| `admin@cartforge.local` | `CartForge-Dev-Only-1` | ADMIN |
| `customer@cartforge.local` | `CartForge-Dev-Only-1` | CUSTOMER |

Smoke check against a running stack:

```bash
./scripts/ci/smoke-test.sh http://localhost:8080
```

### Run without Compose

Provide reachable PostgreSQL and Redis, export variables from `.env.example` (use `localhost` URLs), then:

```bash
export SPRING_PROFILES_ACTIVE=dev
./mvnw spring-boot:run
```

## Docker Compose

Services: `application`, `postgres`, `redis` on network `cartforge`, with health checks and a persistent `postgres_data` volume.

```bash
cp .env.example .env          # set secrets
./mvnw package -DskipTests    # Dockerfile copies target/ecommerce-api-*.jar
docker compose up --build
```

Default image build uses host-built JAR (`Dockerfile`). For in-Docker Maven builds (CI-friendly networks), set `DOCKERFILE=docker/Dockerfile.ci` in `.env`.

If you change `POSTGRES_PASSWORD` after the volume was initialized:

```bash
docker compose down -v && docker compose up --build
```

## Environment Variables

Copy `.env.example` → `.env`. Never commit `.env`. Compose loads `.env` for substitution and injects it via `env_file`. Spring Boot does not load `.env` by itself for non-Compose runs.

| Variable | Purpose |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev`, `test`, or `prod` (set explicitly) |
| `SERVER_PORT` | HTTP port (default `8080`) |
| `LOGGING_LEVEL_ROOT` | Root log level |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Compose Postgres bootstrap |
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Application JDBC |
| `REDIS_URL` | Redis connection |
| `JWT_SECRET` | HS256 signing secret (≥ 32 chars; prod rejects placeholders) |
| `JWT_EXPIRATION` | Access token lifetime in ms (default `3600000`) |
| `CORS_ORIGINS` | Comma-separated origin allowlist (`*` rejected in `prod`) |
| `APP_PAGINATION_DEFAULT_PAGE_SIZE` | Default page size (default `20`) |
| `APP_PAGINATION_MAX_PAGE_SIZE` | Max page size (default `100`) |
| `APP_RATE_LIMIT_AUTH_ENABLED` | Auth rate limiter on/off |
| `APP_RATE_LIMIT_AUTH_LIMIT` | Max requests per window |
| `APP_RATE_LIMIT_AUTH_WINDOW_SECONDS` | Window length |
| `JAVA_OPTS` | Optional JVM flags for the container |
| `DOCKERFILE` | Compose build Dockerfile override |

Compose service DNS: `postgres`, `redis`. Localhost URLs are for non-Docker runs only.

## Database Migrations

Flyway owns schema changes under `src/main/resources/db/migration/`:

| Version | Purpose |
|---|---|
| `V1` | Schema baseline |
| `V2` | Users |
| `V3` | Categories |
| `V4` | Products |
| `V5` | Carts |
| `V6` | Orders |
| `V7` | Product search support |
| `V8` | Categories active index |
| `V9` | Order number sequence |
| `V10` | Checkout idempotency keys |

Hibernate `ddl-auto` is `validate` in all profiles. Migrations run on application startup. Do not edit committed migrations.

Details: [docs/database.md](docs/database.md).

## Testing

Authoritative quality gate:

```bash
./mvnw verify
```

Runs compile, unit and integration tests (Testcontainers PostgreSQL/Redis where required), packaging, Checkstyle, and SpotBugs.

Useful variants:

```bash
./mvnw test                 # unit + integration tests
./mvnw -DskipTests package  # JAR only (for Docker image build)
./scripts/ci/validate-local.sh   # verify + Docker build + Helm lint/template (Unix)
```

Docker must be available for Testcontainers-based integration tests.

## Swagger / OpenAPI

Enabled when `SPRING_PROFILES_ACTIVE=dev`:

| Resource | URL |
|---|---|
| Swagger UI | `/swagger-ui.html` |
| OpenAPI document | `/v3/api-docs` |

Disabled in `prod`. Controllers are annotated with Springdoc operations and JWT bearer security scheme.

## Authentication

| Item | Behaviour |
|---|---|
| Register | `POST /api/v1/auth/register` → always creates `CUSTOMER` (no client-supplied role) |
| Login | `POST /api/v1/auth/login` → JWT access token |
| Header | `Authorization: Bearer <token>` |
| Password | Hashed via Spring `PasswordEncoderFactories` (BCrypt id by default); never returned |
| JWT claims | Subject (user id), email, role, `iat`, `exp` |
| Rate limit | Fixed window per client IP on register/login; fails open if Redis is down |

Public without auth: catalog GETs, register, login, Actuator health/prometheus.  
Customer: cart and orders (own resources only).  
Admin: catalog writes and `/api/v1/admin/orders/**`.

There is no public API to create or promote administrators. Provision `ADMIN` users out-of-band (direct DB / controlled tooling).

Details: [docs/security.md](docs/security.md).

## API Examples

Base URL examples assume `http://localhost:8080`.

### Register

```bash
curl -sS -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "email": "customer@example.com",
    "password": "SecurePassword123!",
    "firstName": "Jane",
    "lastName": "Doe"
  }'
```

### Login

```bash
curl -sS -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"customer@example.com","password":"SecurePassword123!"}'
```

### Catalog

```bash
curl -sS 'http://localhost:8080/api/v1/categories'
curl -sS 'http://localhost:8080/api/v1/products?page=0&size=20&sort=price,asc&category=electronics&minPrice=100&maxPrice=2000&search=laptop'
curl -sS 'http://localhost:8080/api/v1/products/1'
```

Allowed product sort fields: `name`, `price`, `sku`, `createdAt`, `stockQuantity`.

### Cart (authenticated)

```bash
TOKEN=<access-token>

curl -sS http://localhost:8080/api/v1/cart -H "Authorization: Bearer $TOKEN"

curl -sS -X POST http://localhost:8080/api/v1/cart/items \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"productId":1,"quantity":2}'

curl -sS -X PATCH http://localhost:8080/api/v1/cart/items/1 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"quantity":3}'
```

### Checkout

```bash
curl -sS -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-checkout-1' \
  -d '{"shippingAddress":"1 Example Street, Berlin"}'
```

### Orders

```bash
curl -sS http://localhost:8080/api/v1/orders -H "Authorization: Bearer $TOKEN"
curl -sS http://localhost:8080/api/v1/orders/1 -H "Authorization: Bearer $TOKEN"
curl -sS -X POST http://localhost:8080/api/v1/orders/1/cancel -H "Authorization: Bearer $TOKEN"
```

### Admin order status

```bash
ADMIN_TOKEN=<admin-access-token>
curl -sS -X PATCH http://localhost:8080/api/v1/admin/orders/1/status \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"CONFIRMED"}'
```

Errors use a consistent JSON body (`timestamp`, `status`, `code`, `message`, `path`, correlation id when present).

## Docker

Production-oriented runtime image:

```bash
./mvnw package -DskipTests
docker build -t cartforge-api:local .
```

Properties: multi-stage build, non-root UID `10001`, no secrets in the image, configurable `JAVA_OPTS`, SIGTERM / graceful shutdown, healthcheck on liveness.

CI alternative that compiles inside Docker: `docker/Dockerfile.ci`.

Published registry image (after CD publish):

```text
ghcr.io/<owner>/ecommerce-api:<git-sha>
```

Optional convenience tag on successful main publishes: `latest`. Prefer the SHA tag for deployments.

## Kubernetes

Plain manifests live under `k8s/` (Namespace, ServiceAccount, ConfigMap, Secret placeholders, Deployment, Service, Ingress, optional demo Postgres/Redis + NetworkPolicies). Prefer Helm for environment differences.

Probes:

- readiness: `/actuator/health/readiness` (includes DB)
- liveness: `/actuator/health/liveness`

Default API Deployment: 2 replicas, rolling update (`maxUnavailable: 0`), resource requests/limits, non-root, read-only root filesystem with `emptyDir` for `/tmp`.

## Helm

Chart: `helm/cartforge/`

```bash
helm lint helm/cartforge -f helm/cartforge/values-dev.yaml
helm lint helm/cartforge -f helm/cartforge/values-prod.yaml

# Local / demo (in-cluster Postgres + Redis)
helm upgrade --install cartforge ./helm/cartforge -n cartforge --create-namespace \
  -f helm/cartforge/values-dev.yaml

# Production-style (external stores + existing Secret)
helm upgrade --install cartforge ./helm/cartforge -n cartforge --create-namespace \
  -f helm/cartforge/values-prod.yaml \
  --set image.repository=ghcr.io/<owner>/ecommerce-api \
  --set image.tag=<git-sha> \
  --set secrets.create=false \
  --set secrets.existingSecret=cartforge-secrets \
  --atomic --wait --timeout 10m
```

`values-dev.yaml` enables demo in-cluster Postgres/Redis. `values-prod.yaml` expects external data stores and does not render application secrets into Git.

## GitHub Actions

| Workflow | Trigger | Purpose |
|---|---|---|
| [CI](.github/workflows/ci.yml) | PR + push to `main` | `./mvnw verify`, Docker build (no push), Helm lint/template |
| [Publish Image](.github/workflows/publish-image.yml) | Successful CI on `main` | Push `ghcr.io/<owner>/ecommerce-api:<sha>` (+ `latest`) |
| [CD](.github/workflows/cd.yml) | Successful Publish on `main` | Helm deploy, rollout wait, smoke test |

Permissions are least-privilege (`contents: read`; publish adds `packages: write`). Application secrets are not stored in workflow files.

## GHCR

Immutable tag used for deploy:

```text
ghcr.io/<owner>/ecommerce-api:<full-git-sha>
```

Authenticate publishers with the job `GITHUB_TOKEN`. Private packages need a cluster `imagePullSecret` (see [docs/deployment.md](docs/deployment.md)).

## Deployment

Delivery chain:

```text
CI → Publish Image → CD
  → helm lint / template
  → helm upgrade --install --atomic --wait
  → kubectl rollout status
  → scripts/ci/smoke-test.sh
```

Smoke test covers readiness (DB), health, public catalog, and auth endpoint response. Redis may be up or fail-open; the API must remain usable either way. A failed smoke test fails CD.

### Required GitHub Environment `production`

| Kind | Name | Notes |
|---|---|---|
| Secret | `KUBE_CONFIG` | Raw or base64 kubeconfig |
| Vars | `DATABASE_URL`, `REDIS_URL`, `CORS_ORIGINS`, `INGRESS_HOST` | Recommended |
| Vars | `KUBE_NAMESPACE`, `HELM_RELEASE_NAME`, `APP_SECRET_NAME`, `IMAGE_PULL_SECRET_NAME` | Optional |

### Required Kubernetes Secret (pre-create)

```bash
kubectl create secret generic cartforge-secrets -n cartforge \
  --from-literal=POSTGRES_PASSWORD='...' \
  --from-literal=JWT_SECRET='...'   # ≥ 32 characters, non-placeholder
```

Full runbook: [docs/deployment.md](docs/deployment.md).

## Security

- Passwords hashed; never logged or returned
- JWT secret from environment; production validator rejects placeholders and short secrets
- Ownership from security context (no client-supplied `userId` for cart/order ownership)
- Registration cannot assign `ADMIN`
- Actuator: only `health` (+ probes) and `prometheus` are public; other actuator paths denied
- CORS allowlist; wildcard rejected in `prod`
- Secrets stay out of Git (`.env`, kubeconfig, JWT/DB passwords)
- Containers run as non-root with dropped capabilities where configured

Details: [docs/security.md](docs/security.md).

## Architecture Decisions

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-modular-monolith.md) | Modular monolith |
| [0002](docs/adr/0002-postgresql-source-of-truth.md) | PostgreSQL as source of truth |
| [0003](docs/adr/0003-redis-as-cache.md) | Redis as cache (fail-open) |
| [0004](docs/adr/0004-optimistic-locking.md) | Optimistic locking for stock |
| [0005](docs/adr/0005-jwt-authentication.md) | JWT Bearer authentication |
| [0006](docs/adr/0006-helm-kubernetes-deployment.md) | Kubernetes + Helm deployment |
| [0007](docs/adr/0007-checkout-idempotency.md) | Checkout idempotency in PostgreSQL |
| [0008](docs/adr/0008-auth-rate-limiting.md) | Auth rate limiting (Redis, fail-open) |
| [0009](docs/adr/0009-transactional-checkout.md) | Transactional checkout |
| [0010](docs/adr/0010-github-actions-cicd.md) | GitHub Actions CI/CD |

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| App fails with password auth error to Postgres | Volume initialized with a different password → `docker compose down -v` then recreate, or align `.env` |
| `JWT_SECRET` / placeholder rejected on startup | Use a real secret ≥ 32 characters; `prod` rejects `change-me` style values |
| Compose build fails finding JAR | Default uses `docker/Dockerfile.ci`. If `DOCKERFILE=Dockerfile`, run `./mvnw package -DskipTests` first |
| Redis AUTH errors in Compose | Set `REDIS_PASSWORD` in `.env` and match `REDIS_URL` (`redis://:password@redis:6379`) |
| In-Docker Maven PKIX / TLS errors | Use host-built JAR + default `Dockerfile`, not `Dockerfile.ci` |
| Tests fail without Docker | Testcontainers need a working Docker daemon |
| Helm template fails with default values only | Supply `-f values-dev.yaml` or `-f values-prod.yaml` (and secrets/image as required) |
| CD fails missing `KUBE_CONFIG` | Create GitHub Environment `production` with secret `KUBE_CONFIG` |
| Smoke test auth returns 429 | Rate limit hit; wait for window or raise `APP_RATE_LIMIT_AUTH_*` in that environment |
| Redis down but API still serves catalog | Expected fail-open behaviour |
| Swagger 401/404 in `prod` | Springdoc is disabled outside `dev` |

## Documentation Map

| Document | Purpose |
|---|---|
| [SPECIFICATIONS.md](SPECIFICATIONS.md) | Authoritative requirements |
| [docs/architecture.md](docs/architecture.md) | Runtime and package architecture |
| [docs/database.md](docs/database.md) | Schema, constraints, migrations |
| [docs/security.md](docs/security.md) | AuthN/Z and secrets |
| [docs/deployment.md](docs/deployment.md) | Docker, K8s, Helm, CI/CD, secrets |
| [docs/adr/](docs/adr/) | Architecture Decision Records |
| [`.env.example`](.env.example) | Environment variable template |

## License / Credentials

Do not commit real JWT secrets, database passwords, private keys, kubeconfigs, or production credentials. Development seed users exist only when `dev` seed is enabled and must never run in production.
