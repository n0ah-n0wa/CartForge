# ADR 0006 — Helm-based Kubernetes deployment

- **Status:** Accepted (mandated by `SPECIFICATIONS.md` v1.0.0)
- **Date:** 2026-08-22
- **Implementation:** not started

## Context

The specification requires the API to be deployable to Kubernetes with rolling updates, probes, resource limits, and externalized configuration. Raw manifests would duplicate environment differences. Helm is the required packaging.

## Decision

Package Kubernetes resources as a Helm chart with `values.yaml`, `values-dev.yaml`, and `values-prod.yaml`. Templates render Deployment, Service, ConfigMap, Secret, Ingress, and ServiceAccount. Replica count, image, ports, ingress, resources, database, Redis, and extra environment variables are values, not hardcoded template literals.

Images are published to GHCR as `ghcr.io/<owner>/ecommerce-api:<git-sha>`. CI validates the chart with `helm lint` and `helm template`. CD deploys after successful CI on `main` and fails the workflow if rollout verification fails.

In-cluster PostgreSQL and Redis, if used, are labeled demo infrastructure.

Real secret values are not committed. The Secret template may exist; production values are injected at deploy time.

## Consequences

- Local/demo and production differ by values files, not by forked manifests.
- A missing or unnamed Kubernetes cluster does not authorize a fake successful deploy. Until credentials exist, CI can still build the image and validate Helm.
- Read-only root filesystem, if enabled, requires a writable volume for JVM temporary files.
- Graceful SIGTERM handling is part of making rolling updates safe.
