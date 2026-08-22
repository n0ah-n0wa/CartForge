# Security

**Status:** planned. No Spring Security configuration, JWT issuer, or secret store exists in this repository yet.

This document records the security model required by `SPECIFICATIONS.md`. It does not describe a running authentication system.

## Goals

- Authenticate customers and administrators with JWT Bearer tokens.
- Authorize by role and by ownership.
- Keep secrets out of source control and container images.
- Avoid leaking credentials, tokens, or stack traces through APIs or logs.

## Authentication

Specified endpoints:

```http
POST /api/v1/auth/register
POST /api/v1/auth/login
```

Registration:

- validate email and password;
- reject duplicate email;
- hash the password with BCrypt or Argon2;
- assign role `CUSTOMER`;
- enable the user by default;
- return a safe user representation with no password hash.

Login returns an access token. Authenticated requests use:

```http
Authorization: Bearer <JWT>
```

The specification does not require refresh tokens, logout denylists, password reset, or OAuth2. Those flows will not be added unless the specification is updated.

JWT payload must include at least: subject / user id, email, role, issued-at, expiration. The signing secret is supplied by configuration or environment variables. Expiration is configurable. The secret must never be hardcoded.

See [ADR 0005](adr/0005-jwt-authentication.md).

## Authorization

### Public

```text
GET  /api/v1/products
GET  /api/v1/products/{id}
GET  /api/v1/categories
GET  /api/v1/categories/{id}
POST /api/v1/auth/register
POST /api/v1/auth/login
```

The specification also requires an `active` filter on product listing while forbidding inactive items in the default public catalog. Planned rule: anonymous and customer listings are active-only; an `active` query parameter is administrator-only; public GET of an inactive product returns 404 except for `ADMIN`.

### Customer

Authenticated customers may access only their own cart, orders, and user profile. The specification requires own-profile access but does not list a path. Planned surface: `GET /api/v1/users/me` only. No public role-promotion API.

Customers must never:

- modify another user's cart;
- retrieve another user's order;
- change order status;
- create an administrator;
- modify product inventory.

### Administrator

Administrative catalog writes and `/api/v1/admin/orders` require role `ADMIN`.

Administrators may be provisioned through development seed data, controlled administrative tooling, or bootstrap configuration. Registration cannot create `ADMIN` users.

## Ownership boundary

Requests must not treat client-supplied ownership fields as authoritative. Example: a body containing `"userId": 123` must not select the cart or order owner. Ownership is taken from the authenticated security context.

## Passwords and tokens

- Store only password hashes.
- Never return hashes from any API.
- Do not log passwords, password hashes, JWTs, authentication secrets, or complete authorization headers.
- Disabled users (`enabled = false`) must not authenticate. That behavior follows from the `enabled` field; login implementation will enforce it.

## Input validation and errors

All external input is validated with Jakarta Bean Validation. Validation and business errors use the standard error JSON:

```json
{
  "timestamp": "2026-08-22T18:30:00Z",
  "status": 409,
  "code": "INSUFFICIENT_STOCK",
  "message": "Insufficient stock for product 42",
  "path": "/api/v1/orders"
}
```

Central handling uses `@RestControllerAdvice`. Clients must never receive SQL, stack traces, internal class names, database credentials, or JWT material.

Business conflicts (insufficient stock, illegal status transitions, optimistic-lock failure) should use HTTP 409 where appropriate. An empty cart at checkout is specified as 400 or 409; planned mapping is 400 or 422 for empty cart, and 409 for stock, version, and state conflicts.

## CORS

CORS is environment-dependent. Development may allow configured local origins. Production must use an explicit allowlist. Wildcard origins must not be used in production when credentials are enabled.

## Rate limiting

Authentication endpoints must be protected against uncontrolled bursts:

```text
POST /api/v1/auth/login
POST /api/v1/auth/register
```

Section 41 writes unversioned `/auth/login` and `/auth/register`. All business endpoints must use `/api/v1`. The planned targets are the versioned paths above.

Redis may back the limiter. If Redis is down, the API must remain usable: fail open, log a warning, and document that choice. If a robust limiter is deferred, the omission must be documented rather than silent.

## Secrets

Do not commit `.env`, JWT secrets, database passwords, production credentials, or private keys. Provide placeholders in `.env.example` only.

Kubernetes secrets are injected through Secret resources (or an equivalent secure mechanism), never through ordinary ConfigMaps.

Images must not embed secrets.

## Actuator and HTTP hardening

- Expose only Actuator endpoints that are safe and necessary.
- Production must not expose sensitive environment information.
- Kubernetes probes use `/actuator/health/readiness` and `/actuator/health/liveness`.
- Use secure HTTP headers where applicable.

## Logging and correlation

Logs should include enough context to debug a request. If the client sends `X-Correlation-ID`, propagate it; otherwise generate one. Include the correlation ID in error responses where appropriate.

Log authentication failures, administrative operations, checkout failures, inventory conflicts, and unexpected exceptions.

## Security tests (required when code exists)

The specification requires tests that prove:

- unauthenticated access is rejected where required;
- a customer cannot read another customer's order;
- a customer cannot call admin endpoints or modify products;
- invalid and expired JWTs are rejected;
- registration cannot create `ADMIN`;
- passwords are absent from API responses.

Those tests do not exist yet.

## Related documents

- [architecture.md](architecture.md)
- [deployment.md](deployment.md)
- [ADR 0005](adr/0005-jwt-authentication.md)
