# ADR 0008 — Authentication rate limiting

- **Status:** Accepted
- **Date:** 2026-08-24
- **Implementation:** Redis fixed-window limiter on `POST /api/v1/auth/login` and `POST /api/v1/auth/register`; fail-open if Redis is unavailable. Configuration: `app.rate-limit.auth`.

## Context

`SPECIFICATIONS.md` requires basic rate limiting on authentication endpoints so credential stuffing and registration floods cannot run without bound. Redis may back the limiter. The API must remain usable if Redis is down. Responses must not leak secrets (passwords, hashes, JWTs).

## Decision

Use a **fixed-window counter** in Redis, keyed by endpoint and client IP (`auth-rate:{login|register}:{ip}`).

A Lua script increments the key and sets `EXPIRE` on the first hit so count and TTL stay atomic. When the count exceeds `app.rate-limit.auth.limit` within `window-seconds`, the filter returns HTTP 429 with `RATE_LIMIT_EXCEEDED` and `Retry-After`. The body never includes the IP, email, or credentials.

If Redis throws, the limiter **fails open**: the request proceeds and a warning is logged without request bodies or tokens. Forwarded headers are ignored so clients cannot spoof `X-Forwarded-For` to evade the limit.

Limits are environment-configurable (`APP_RATE_LIMIT_AUTH_LIMIT`, `APP_RATE_LIMIT_AUTH_WINDOW_SECONDS`, `APP_RATE_LIMIT_AUTH_ENABLED`).

## Alternatives considered

- **Sliding window / token bucket:** smoother bursts, more Redis state or Lua. Not required for “basic” protection.
- **Fail closed:** stronger under Redis outage, but would take login and registration offline, which the specification forbids.
- **Email-keyed limits:** better against distributed stuffing, stores identifiers in Redis, and is bypassed by rotating addresses. IP remains the primary key.

## Trade-offs

- Fixed windows allow up to about `2 × limit` at a window boundary.
- Fail-open means a Redis outage removes the burst shield until Redis returns.
- IP keys are coarse on NAT and weak against botnets; they still cap a single noisy client.
- The limiter is not a WAF or CAPTCHA. It is a coarse application control.

## Consequences

- Authentication stays available when Redis is down.
- Operators tune limits without a code change.
- Tests must cover allow, 429, window reset, and Redis failure.
