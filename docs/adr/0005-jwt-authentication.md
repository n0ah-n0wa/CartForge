# ADR 0005 — JWT authentication

- **Status:** Accepted
- **Date:** 2026-08-22
- **Implementation:** complete — `AuthController` register/login; HS256 via Spring Security OAuth2 Resource Server (Nimbus); no refresh tokens

## Context

The API must authenticate customers and administrators over HTTP without browser sessions or an external IdP. The specification requires Spring Security and JWT Bearer tokens. Ownership must come from the verified token, never from a client-supplied `userId`.

## Decision

On successful login, issue a signed **JWT access token** (`JwtTokenService`). Clients send `Authorization: Bearer <JWT>`.

| Topic | Choice |
|---|---|
| Algorithm | HS256 only; `none` / foreign algorithms rejected |
| Claims | `sub` (user id), `email`, `role`, `iat`, `exp` |
| Secret / TTL | `JWT_SECRET` / `app.jwt.*` — no committed default; production validates length |
| Passwords | `DelegatingPasswordEncoder` (BCrypt by default); hashes never returned |
| Registration role | Always `CUSTOMER`; no role field on the request |

`ADMIN` is provisioned **out-of-band** (controlled tooling / DB). No automatic seed admin is shipped.

Refresh tokens, cookie sessions, logout denylist, and OAuth social login are out of scope.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Server sessions / sticky cookies | Extra store; poorer fit for multi-replica API clients |
| Asymmetric JWT (RS256) | More key-management ops; HS256 meets the portfolio scope |
| Opaque tokens in Redis | Couples auth to Redis availability; conflicts with fail-open posture |
| Allow registration to create `ADMIN` | Privilege-escalation risk; forbidden by design |

## Consequences

- Authorization uses JWT `sub` / `role` via `CurrentUserProvider`.
- Expired or invalid tokens → 401; cross-customer access → 403.
- Tokens disabled-user issued remain valid until `exp` (no denylist).
- JWTs, passwords, and hashes must never appear in logs or `toString`.
