# ADR 0006 — Kubernetes + Helm deployment

- **Status:** Accepted
- **Date:** 2026-08-22
- **Implementation:** complete — `helm/cartforge`, reference manifests in `k8s/`, prod values with external DB/Redis

## Context

The specification requires deployability to Kubernetes with rolling updates, probes, resource limits, and externalized configuration. Maintaining only raw YAML per environment duplicates drift. Packaging and CD must not embed secrets in Git or images.

## Decision

Package runtime resources as a **Helm chart** (`helm/cartforge`) with `values.yaml`, `values-dev.yaml`, and `values-prod.yaml`.

Templates render Deployment, Service, ConfigMap, Secret, Ingress, and ServiceAccount (plus optional demo Postgres/Redis/NetworkPolicy).

| Topic | Choice |
|---|---|
| Image | `ghcr.io/<owner>/ecommerce-api:<git-sha>` (immutable) |
| Production secrets | Existing Secret (`secrets.create=false`); CD does not create credentials |
| Data stores (prod) | Externally managed PostgreSQL and Redis; demo infra disabled |
| Rollout | `replicas: 2`, `maxUnavailable: 0`, readiness/liveness actuator probes |

Raw `k8s/` manifests remain as a non-Helm reference of the same resource kinds.

CI/CD orchestration is separately recorded in [ADR 0010](0010-github-actions-cicd.md).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Raw manifests only | Environment drift; harder templating of image tags and config |
| Kustomize overlays only | Spec mandates Helm |
| In-cluster Postgres/Redis as production | Fine for demos; not a managed-data claim; disabled in `values-prod.yaml` |
| Bake secrets into the image or ConfigMap | Security violation |

## Consequences

- Dev and prod differ by values files, not forked template trees.
- Deploy readiness depends on real cluster credentials and an existing app Secret.
- Graceful SIGTERM / `preStop` support safe rolling updates.
- Operators must provision Ingress controller and external data stores separately.
