# ADR 0005 — JWT authentication

- **Status:** Accepted (mandated by `SPECIFICATIONS.md` v1.0.0)
- **Date:** 2026-08-22
- **Implementation:** not started

## Context

The API must authenticate customers and administrators over HTTP without a browser session or an external identity provider. The specification requires Spring Security and JWT.

## Decision

Issue a signed JWT access token on login. Clients send `Authorization: Bearer <JWT>`. Tokens include subject/user id, email, role, issued-at, and expiration.

The signing secret and expiration come from environment or application configuration. They are never hardcoded and never committed.

Registration always creates `CUSTOMER`. `ADMIN` is provisioned only by seed data, controlled tooling, or bootstrap configuration.

Passwords are hashed with BCrypt or Argon2. The public API never returns hashes.

Refresh tokens, cookie sessions, and OAuth2 are out of scope unless the specification changes.

## Consequences

- Authorization decisions use the token's subject and role, not client-supplied user ids.
- Token expiration is a configuration concern; there is no specified refresh flow.
- Security tests must cover missing, invalid, and expired tokens, cross-customer access, and the registration role constraint.
- JWT values must never appear in logs.
