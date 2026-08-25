# ADR 0010 — GitHub Actions CI/CD

- **Status:** Accepted
- **Date:** 2026-08-24
- **Implementation:** complete — `ci.yml`, `publish-image.yml`, `cd.yml`; smoke via `scripts/ci/smoke-test.sh`

## Context

Every change needs a repeatable quality gate before an image is published and before production receives traffic. Deploy must use immutable tags, keep cluster credentials and app secrets out of the repository, and fail when rollout or smoke verification fails. Packaging target is Helm on Kubernetes ([ADR 0006](0006-helm-kubernetes-deployment.md)).

## Decision

Use **three chained GitHub Actions workflows**:

| Workflow | Trigger | Responsibility |
|---|---|---|
| **CI** | PR + push to `main` | `./mvnw verify`, Docker build (no push), Helm lint/template |
| **Publish Image** | Successful CI on `main` (or manual SHA) | Push `ghcr.io/<owner>/ecommerce-api:<git-sha>` (+ `latest` on main) |
| **CD** | Successful Publish on `main` (or manual SHA) | Helm upgrade `--atomic --wait` to Environment `production`, rollout status, smoke test |

Production deploys always pin the **full git SHA** tag, never `latest`.

Cluster access: Environment secret `KUBE_CONFIG`. Application credentials stay in the cluster Secret (`cartforge-secrets`); CD sets `secrets.create=false`.

Smoke (`scripts/ci/smoke-test.sh`): readiness (incl. `db`), liveness, public catalog GETs, auth login expecting 401/429. Redis is not required for readiness.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Single workflow for build+push+deploy | Harder least-privilege permissions; couples PR CI to prod credentials |
| Deploy `:latest` | Non-reproducible rollbacks; ambiguous which commit is live |
| Skip smoke / treat deploy as success on `helm upgrade` exit only | Masks readiness and wiring failures |
| Store JWT/DB passwords in GitHub Actions | Wrong trust boundary; belong in the cluster Secret |
| Other CI hosts (Jenkins, etc.) | Spec/portfolio path is GitHub Actions + GHCR |

## Consequences

- Main stays gated: broken verify or chart render blocks publish.
- Failed rollout or smoke fails CD; Helm `--atomic` rolls back an unready release.
- Operators configure Environment `production` (kubeconfig + optional vars) before CD can succeed.
- Image publish uses `packages: write`; CD needs only `contents: read` plus environment secrets.
