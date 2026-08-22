# CartForge

**Status:** implementation in progress. This repository currently contains the authoritative specification and the documentation foundation. The application, tests, containers, and delivery pipelines have not been implemented yet.

CartForge is a production-style REST API for an online store. The intended system is a Java 21 modular monolith built with Spring Boot. `SPECIFICATIONS.md` is the source of truth for functional and technical requirements.

Do not treat this README as a claim that catalog, authentication, cart, checkout, or deployment already work.

## Overview

The specified system is a versioned HTTP API (`/api/v1`) that must support:

- customer registration and JWT authentication;
- role-based authorization (`CUSTOMER`, `ADMIN`);
- product and category management;
- search, filtering, sorting, and pagination;
- shopping carts and transactional checkout;
- order lifecycle and inventory consistency;
- PostgreSQL persistence with Flyway migrations;
- Redis as a cache, not as the source of truth;
- Docker, Kubernetes, Helm, and GitHub Actions delivery.

The architecture is a **modular monolith**, not a microservice system.

Out of scope: real payment processing, card storage, shipping providers, email/SMS, search engines, Kafka, frontend, and mobile clients. See `SPECIFICATIONS.md` section 4.

## Architecture

All business domains run in one Spring Boot process. PostgreSQL stores durable state. Redis caches read-heavy catalog data and must not make the API unusable when it is down.

```text
                         ┌─────────────────────┐
                         │       Client        │
                         │ Browser / Postman   │
                         │ Swagger / API Tests │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ Ingress / HTTP      │
                         └──────────┬──────────┘
                                    │
                                    ▼
                  ┌──────────────────────────────────┐
                  │          Spring Boot API         │
                  │                                  │
                  │  Authentication                  │
                  │  Users                           │
                  │  Products                        │
                  │  Categories                      │
                  │  Cart                            │
                  │  Orders                          │
                  │  Inventory                       │
                  │  Administration                 │
                  └───────────────┬──────────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
                    ▼                           ▼
             ┌─────────────┐             ┌─────────────┐
             │ PostgreSQL  │             │    Redis    │
             │ Persistent  │             │ Cache       │
             │ Data        │             │             │
             └─────────────┘             └─────────────┘
```

Code organization is package-by-feature under `com.example.ecommerce` (`auth`, `user`, `category`, `product`, `cart`, `order`, `inventory`, `common`). Inventory is an internal service over product stock, not a separate HTTP resource.

Details: [docs/architecture.md](docs/architecture.md). Decisions: [docs/adr/](docs/adr/).

## Technology Stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Build | Maven |
| Web | Spring Web / Spring MVC |
| Persistence | Spring Data JPA, Hibernate |
| Database | PostgreSQL |
| Migrations | Flyway |
| Security | Spring Security, JWT |
| Validation | Jakarta Bean Validation |
| Cache | Spring Cache + Redis |
| API documentation | Springdoc OpenAPI |
| Tests | JUnit 5, Mockito, Testcontainers |
| Quality | Checkstyle, SpotBugs |
| Local orchestration | Docker Compose |
| Runtime packaging | Docker |
| Orchestration | Kubernetes |
| Packaging | Helm |
| CI/CD | GitHub Actions |
| Registry | GitHub Container Registry (GHCR) |
| Health / metrics | Spring Boot Actuator |

These technologies are required by the specification. They are not present as a runnable project in this repository yet.

## Features

The following capabilities are **specified**, not implemented:

- registration and login with hashed passwords and JWT access tokens;
- public catalog reads; administrative catalog writes;
- product search, filters, allowlisted sorting, and pagination;
- customer cart operations;
- transactional checkout with order-item snapshots and inventory decrement;
- order history, customer cancellation where allowed, administrative status updates;
- optimistic locking on concurrent stock updates;
- Redis cache for product and category reads, with graceful degradation;
- idempotency for order creation;
- rate limiting on authentication endpoints;
- centralized JSON errors, correlation IDs, and Actuator health probes.

Nothing in this list is available as a running API today.

## Local Development

**Current state:** there is no Maven project, Docker Compose file, or application to start.

The specified local flow, once those files exist, is:

```bash
git clone <repository-url>
cp .env.example .env
docker compose up --build
```

### Prerequisites (planned)

- Java 21
- Maven Wrapper (`./mvnw`), once added
- Docker and Docker Compose
- access to the environment variables listed in `.env.example`

### Environment variables

Copy `.env.example` to `.env`. Replace every `change-me` placeholder. Never commit `.env`. Required categories:

- database URL, username, and password;
- Redis URL;
- JWT secret and expiration;
- CORS origins;
- application port;
- logging level.

Spring profiles specified for later implementation: `dev`, `test`, `prod`.

### Database migrations

Flyway will own the schema. Production configuration must use Hibernate `ddl-auto=validate` (or equivalent). Migration files are not in the repository yet.

### Running the application

Not available. The intended Compose stack is `application`, `postgres`, and `redis`.

### Swagger

OpenAPI / Swagger UI will be generated by Springdoc when the API exists. The UI path will be documented here after that implementation lands. Do not assume a live documentation endpoint today.

## Testing

**Current state:** there is no test suite.

The specified Java quality gate is:

```bash
./mvnw verify
```

That command must eventually run compile, unit tests, integration tests (Testcontainers for PostgreSQL and Redis where required), static analysis, and packaging. CI must use the same command.

Additional infrastructure gates, once those artifacts exist: `docker build`, `helm lint`, `helm template`.

## Docker

**Current state:** no Dockerfile or Compose file.

The specification requires a multi-stage, non-root, secret-free production image and a Compose file that starts the application, PostgreSQL, and Redis.

## Kubernetes

**Current state:** no manifests.

Required resources, when implemented: Namespace, Deployment (default 2 replicas), Service, ConfigMap, Secret, Ingress. Readiness and liveness probes must use `/actuator/health/readiness` and `/actuator/health/liveness`. PostgreSQL and Redis may run in-cluster for a portfolio demo and must be documented as demo infrastructure, not as a production-managed database service.

See [docs/deployment.md](docs/deployment.md).

## Helm

**Current state:** no chart.

The specified chart layout is `Chart.yaml`, `values.yaml`, `values-dev.yaml`, `values-prod.yaml`, and templates for Deployment, Service, ConfigMap, Secret, Ingress, ServiceAccount, and helpers. Values must not be hardcoded in templates when they belong in `values.yaml`. CI must run `helm lint` and `helm template`.

## CI/CD

**Current state:** no GitHub Actions workflows.

Specified CI on push and pull request: checkout, Java setup, Maven cache, compile, unit tests, integration tests, static analysis, package, Docker build.

Specified CD after successful CI on `main`: build and test, publish `ghcr.io/<owner>/ecommerce-api:<git-sha>` to GHCR, Helm validation, deploy to Kubernetes, wait for rollout, verify. Production-style deploys must prefer immutable SHA tags. A failed rollout must fail the workflow.

The target cluster is not named in the specification. Credentials must come from GitHub Actions secrets when CD is implemented. A deploy step must not be reported as successful unless a real rollout occurred.

## API

There is no running API.

The specified public surface is versioned under `/api/v1`. Public reads are limited to products, categories, registration, and login. Customer endpoints require a Bearer JWT. Administrative writes require `ADMIN`. Entities must not be returned from controllers; DTOs form the API boundary.

When Springdoc is implemented, this section will record the exact OpenAPI and Swagger UI paths.

## Security

Specified model (not implemented):

- passwords hashed with BCrypt or Argon2; never stored or returned in plaintext;
- JWT contains subject/user id, email, role, issued-at, and expiration;
- signing secret supplied by configuration, never hardcoded;
- customers may access only their own cart, orders, and profile;
- registration always creates `CUSTOMER`; the public API cannot create or promote administrators;
- ownership is taken from the security context, not from client-supplied user ids;
- secrets stay out of Git; Kubernetes secrets are injected as Secret resources.

Details: [docs/security.md](docs/security.md).

## Engineering Decisions

Significant decisions are recorded as Architecture Decision Records. They capture specification choices; they do not imply that the corresponding code exists.

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-modular-monolith.md) | Modular monolith instead of microservices |
| [0002](docs/adr/0002-postgresql-source-of-truth.md) | PostgreSQL is the source of truth |
| [0003](docs/adr/0003-redis-as-cache.md) | Redis is a cache, not durable state |
| [0004](docs/adr/0004-optimistic-locking.md) | Optimistic locking for concurrent stock and updates |
| [0005](docs/adr/0005-jwt-authentication.md) | JWT Bearer authentication |
| [0006](docs/adr/0006-helm-kubernetes-deployment.md) | Helm-packaged Kubernetes deployment |

## Documentation

| Document | Purpose |
|---|---|
| [SPECIFICATIONS.md](SPECIFICATIONS.md) | Authoritative requirements |
| [docs/architecture.md](docs/architecture.md) | Runtime and package architecture |
| [docs/database.md](docs/database.md) | Schema, constraints, migrations |
| [docs/security.md](docs/security.md) | Authentication, authorization, secrets |
| [docs/deployment.md](docs/deployment.md) | Docker, Kubernetes, Helm, CI/CD |
| [docs/adr/](docs/adr/) | Architecture Decision Records |

## Credentials

Do not commit real JWT secrets, database passwords, private keys, or production credentials. Seed users, when added, will be development-only and must never be applied automatically in production.
