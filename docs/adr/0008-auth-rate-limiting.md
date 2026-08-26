# ADR 0008 — Authentication rate limiting

- **Status:** Accepted
- **Date:** 2026-08-24
- **Implementation:** complete — Redis fixed-window on login/register; fail-open

## Context

Authentication endpoints are abuse targets. The specification requires basic rate limiting and continued availability if Redis is down. Responses must not leak secrets.

## Decision

Use a **fixed-window counter** in Redis keyed by endpoint and client IP (`auth-rate:{login|register}:{ip}`). Exceed → HTTP 429 + `Retry-After`. Redis failure → **fail open** (request proceeds, warning logged).

By default forwarded headers are ignored (anti-spoofing). When `app.rate-limit.auth.trusted-proxies` lists the Ingress/`remoteAddr`, the left-most `X-Forwarded-For` hop is used so Kubernetes clients are not collapsed into one shared bucket.

Configurable via `APP_RATE_LIMIT_AUTH_*` / `app.rate-limit.auth`.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Sliding window / token bucket | More state; not required for “basic” protection |
| Fail closed | Would take login/register offline when Redis is down |
| Email-keyed limits | Stores identifiers; bypassed by rotating addresses |

## Consequences

- Auth stays available when Redis is down; burst shielding is lost until Redis returns.
- Fixed windows can admit ~`2 × limit` at a boundary.
- IP keys are coarse on NAT; still cap a single noisy client.
