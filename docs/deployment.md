# Deployment

**Status:** planned. There is no Dockerfile, Compose file, Helm chart, Kubernetes manifest, or GitHub Actions workflow in this repository yet.

This document describes the delivery model required by `SPECIFICATIONS.md`. Commands below are the specified targets, not a working runbook for the current tree.

## Local stack

Specified Compose services: `application`, `postgres`, `redis`.

Intended flow, after those files exist:

```bash
cp .env.example .env
docker compose up --build
```

The application must take database, Redis, JWT, CORS, port, and logging settings from the environment. See `.env.example`.

Development may use local PostgreSQL, local Redis, optional seed data, verbose logging, and Swagger UI. Production must disable development seed data, use external secrets, restrict CORS, and avoid exposing sensitive Actuator endpoints.

## Container image

Specified Dockerfile properties:

- multi-stage build;
- minimal runtime image;
- non-root execution;
- configurable JVM options;
- no secrets in the image;
- deterministic build;
- only what is required to run the application;
- application port only;
- SIGTERM and graceful shutdown (stop new requests, finish in-flight work within a timeout, close PostgreSQL and Redis connections).

Recommended image reference:

```text
ghcr.io/<owner>/ecommerce-api:<git-sha>
```

Optional `latest` may be published for development convenience. Production-style deploys must prefer the immutable SHA tag.

## Kubernetes

Required resources:

```text
Namespace
Deployment
Service
ConfigMap
Secret
Ingress
```

Deployment requirements:

- default `replicas: 2`;
- rolling updates;
- readiness probe: `/actuator/health/readiness`;
- liveness probe: `/actuator/health/liveness`;
- resource requests and limits;
- environment injection;
- graceful termination;
- no planned full downtime when replacing healthy replicas;
- unready pods must not receive traffic.

Container security, where practical:

- run as non-root;
- read-only root filesystem (JVM `/tmp` will need a writable volume such as `emptyDir`);
- drop unnecessary Linux capabilities;
- no privileged mode;
- explicit resource limits.

Secrets must not be stored in ordinary ConfigMaps.

PostgreSQL and Redis may be deployed as in-cluster resources for a portfolio environment. Charts and this document must keep calling that **demo infrastructure**. It is not a production-managed database service.

The specification does not name a target cluster. When CD is implemented, kubeconfig or equivalent credentials must come from GitHub Actions secrets (or an equivalent secret store). A workflow must not report a successful deploy unless a real rollout was observed.

## Helm

Specified chart layout:

```text
Chart.yaml
values.yaml
values-dev.yaml
values-prod.yaml
templates/
  deployment.yaml
  service.yaml
  configmap.yaml
  secret.yaml
  ingress.yaml
  serviceaccount.yaml
  _helpers.tpl
```

Only resources that are actually required should be included. The list above is the specified starting set.

Configurable values must include: `replicaCount`, `image.repository`, `image.tag`, `service.port`, `ingress.enabled`, `resources`, database configuration, Redis configuration, and environment variables.

Default resource values must be reasonable for a local or demo cluster and remain overridable:

```yaml
resources:
  requests:
    cpu:
    memory:
  limits:
    cpu:
    memory:
```

`templates/secret.yaml` is required. Real secret values must not be committed. Planned approach: template the Secret from values or an external source; commit placeholders only; inject production values with `--set`, sealed secrets, or a cluster-managed Secret.

CI must run:

```bash
helm lint
helm template
```

Generated manifests must be syntactically valid.

See [ADR 0006](adr/0006-helm-kubernetes-deployment.md).

## CI/CD

### Continuous integration

GitHub Actions on pull requests and pushes. Specified pipeline:

```text
Checkout
  → Set up Java
  → Restore Maven cache
  → Compile
  → Unit tests
  → Integration tests
  → Static analysis
  → Package
  → Docker build
```

The Java quality gate is `./mvnw verify`. The pipeline fails if any mandatory gate fails. Workflow permissions follow least privilege. Secrets are stored as GitHub Actions secrets and must not be printed. Third-party actions should be pinned to stable versions or commit SHAs.

### Continuous delivery

After successful CI on `main`:

```text
Build
  → Test
  → Build Docker image
  → Tag image
  → Push to GHCR
  → Helm lint
  → Helm template
  → Deploy to Kubernetes
  → Wait for rollout
  → Verify deployment
```

Deployment failure fails the workflow.

Additional infrastructure gates: `docker build`, `helm lint`, `helm template`.

## Configuration by environment

| Topic | Development | Production |
|---|---|---|
| Data stores | Local or Compose PostgreSQL and Redis | Externalized connection settings; demo in-cluster stores only if explicitly chosen |
| Seed data | Optional, documented as dev-only | Must not load automatically |
| JWT | From environment | Secure external secret |
| CORS | Configured local origins | Explicit allowlist |
| Logging | May be verbose | Appropriate production level; no secrets |
| Actuator | Health, liveness, readiness, Prometheus scrape | Same endpoints; no env/heapdump/beans; health details never shown |
| Image tag | May use `latest` for convenience | SHA tag |

## Health and shutdown

The application must not receive production traffic before readiness succeeds. Graceful shutdown is required for rolling updates.

## Related documents

- [architecture.md](architecture.md)
- [security.md](security.md)
- [ADR 0006](adr/0006-helm-kubernetes-deployment.md)
