# Security

**Status:** partially implemented. Password hashing, registration, credential verification, the database-backed user lookup, JWT issuance, and Bearer authentication on the filter chain all exist. There is no auth controller yet, so a client still has no HTTP route to obtain a token.

This document records the security model required by `SPECIFICATIONS.md`. Sections marked planned are not built yet.

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

Registration (`auth.service.RegistrationService`, implemented):

- validate email and password. The specification says "validate password" without a policy; the implemented rule is 12–72 characters. The upper bound matters because BCrypt only consumes the first 72 bytes;
- reject duplicate email. `RegistrationService` pre-checks and also translates a `uq_users_email_lower` violation, which is the real guard between two concurrent registrations;
- hash the password with a `DelegatingPasswordEncoder` (BCrypt default), so stored hashes carry an algorithm prefix and can be upgraded later;
- assign role `CUSTOMER`. `RegistrationRequest` has no role field, so no other role is reachable;
- enable the user by default;
- return `UserResponse`, which has no password hash.

Login (`auth.service.AuthenticationService`, implemented): `authenticate` verifies credentials and returns `AuthenticatedUser`; `login` composes that with token issuance and returns an `AccessTokenResponse`. No token is issued unless the credential check passes.

Every failed login raises the same `InvalidCredentialsException`, whether the address is unknown, the password is wrong, or the account is disabled, so the endpoint cannot be used to enumerate users. When no user matches, the encoder is still run against a decoy hash so an unknown address costs the same work as a wrong password and cannot be identified by response timing.

`auth.service.DatabaseUserDetailsService` is the Spring Security lookup: it resolves users by email case-insensitively, maps `UserRole` to a `ROLE_` authority, and honours the `enabled` flag. Providing it also stops Spring Boot from falling back to a generated in-memory user.

Email lookups are written with `lower(...)` rather than Spring Data's `IgnoreCase` keyword, which generates `upper(...)` and could not use the `uq_users_email_lower` functional index that the login path depends on.

Login returns an access token. Authenticated requests use:

```http
Authorization: Bearer <JWT>
```

The specification does not require refresh tokens, logout denylists, password reset, or OAuth2. Those flows will not be added unless the specification is updated.

### Tokens (implemented)

`auth.service.JwtTokenService` issues an HS256-signed JWT carrying `sub` (user id), `email`, `role`, `iat`, and `exp`. The lifetime is `app.jwt.expiration-ms` (`JWT_EXPIRATION`) and the signing secret is `app.jwt.secret` (`JWT_SECRET`). Neither has a hardcoded value or a committed default.

Signing and verification use Spring Security's own resource-server support (Nimbus) rather than a third-party JWT library, per section 81. `common.security.JwtConfig` builds the `JwtEncoder` and `JwtDecoder` from the configured secret and rejects a secret shorter than 32 bytes at startup, because HS256 needs a 256-bit key. `ProductionEnvironmentValidator` separately rejects placeholder secrets in production.

Requests authenticate with `Authorization: Bearer <JWT>` through the standard resource-server filter, so signature and expiry validation is not hand-rolled. A `JwtAuthenticationConverter` maps the signed `role` claim to a `ROLE_` authority; the claim is trusted only because the signature has already been verified.

The decoder additionally requires `exp`, `sub`, `email`, and `role` to be present. Spring's default validator only checks `exp` when it is there, so without this a signed token with no `exp` would never expire and one with no `sub` would receive its role without an identifiable principal. `role` must be exactly `CUSTOMER` or `ADMIN`; `admin`, `ROLE_ADMIN`, and invented roles are rejected as invalid tokens rather than authenticated-but-forbidden. `iat` is not on that list: Spring's default claim-set converter derives it from `exp` when absent, so a decoded token always has one, and the issuer sets it explicitly.

The decoder is locked to HS256 with the configured HMAC secret. Tokens that declare `none`, a different MAC algorithm, or an asymmetric algorithm are rejected.

Authentication and authorization failures return an empty body and, for 401, `WWW-Authenticate: Bearer` with no `error_description`. Spring's default resource-server entry point would otherwise copy the decoder exception into that header.

Tokens are credentials: `AccessTokenResponse.toString` is redacted so a token cannot reach a log.

See [ADR 0005](adr/0005-jwt-authentication.md).

## Authorization

The URL rules in `SecurityConfig` are implemented and tested; the handlers they protect are not written yet. Reusable rules live in `common.security`: `@RequireAdmin` (a `@PreAuthorize` meta-annotation, backed by `@EnableMethodSecurity`) and `CurrentUserProvider` for ownership.

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

Only the specified catalog reads are public: `GET /api/v1/products`, `GET /api/v1/products/{id}`, and the same pair for categories. Nested catalog paths such as `/products/{id}/inventory` are not public. Every other method on those paths requires `ADMIN`, declared explicitly rather than left to fall through to `anyRequest().authenticated()` — otherwise any logged-in customer could create or delete catalog entries.

`PATCH`/`PUT`/`POST` of `/api/v1/orders/{id}/status` also requires `ADMIN`. The specified customer cancel path is `POST /api/v1/orders/{id}/cancel`; status changes belong on `/api/v1/admin/orders/{id}/status`. The extra rule exists so a future handler mapped onto the customer resource cannot become a privilege escalation.

Administrators may be provisioned through development seed data, controlled administrative tooling, or bootstrap configuration. Registration cannot create `ADMIN` users.

## Ownership boundary

Requests must not treat client-supplied ownership fields as authoritative. Example: a body containing `"userId": 123` must not select the cart or order owner. Ownership is taken from the authenticated security context.

`CurrentUserProvider` is the only sanctioned way to resolve the acting user. It reads the user id from the verified token's `sub` claim, and `requireSelf(ownerId)` raises `AccessDeniedException` when the resource belongs to somebody else. Administrators are not exempt from `requireSelf`: cross-user access belongs on the administrative endpoints, not on a customer's own path.

Inbound payloads are structurally prevented from carrying ownership: `ApiBoundaryTest` fails the build if any DTO whose name ends in `Command` or `Request` declares a `userId`, `ownerId`, or `customerId` component.

## CORS

`CorsConfig` applies to `/api/**` only, with an explicit origin allowlist and credentials disabled — the API authenticates with a Bearer header, not cookies, so no origin ever needs credential support. Preflight from an unlisted origin is refused with 403, and a successful preflight does not exempt the actual request from authentication.

## Passwords and tokens

- Store only password hashes.
- Never return hashes from any API.
- Do not log passwords, password hashes, JWTs, authentication secrets, or complete authorization headers. `RegistrationRequest`, `LoginRequest`, and `AccessTokenResponse` override the record-generated `toString` to redact secrets. `AuthenticationService` logs failed logins as `Authentication failed for email=...` and never writes the password.
- Disabled users (`enabled = false`) must not authenticate. `AuthenticationService` enforces this and reports it as invalid credentials rather than as a distinct state. A token issued before disablement remains valid until `exp`; there is no denylist (the specification does not require one).

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
- Use secure HTTP headers where applicable. Spring Security's defaults are enabled (`X-Content-Type-Options`, `X-Frame-Options: DENY`, `Cache-Control`).
- Form login and HTTP Basic are disabled. Query-string tokens are ignored; only `Authorization: Bearer` authenticates.

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

Covered so far: registration cannot create `ADMIN` (structurally, plus a reflection assertion and an ignored extra `role` JSON field); passwords are absent from responses and from every persisted column; failed logins are indistinguishable; and missing, malformed, foreign-signed, expired, unsigned, algorithm-confused, and unknown-role tokens are all rejected with 401 through the real filter chain, while a `CUSTOMER` token receives 403 on `/api/v1/admin/**` and an `ADMIN` token passes.

Also covered: unauthenticated access is rejected on every non-public path; a customer receives 403 on catalog writes, nested catalog paths, `/api/v1/admin/**`, and order-status changes on both the admin and customer resources; a query-string token does not authenticate; 401 responses name the Bearer scheme and nothing else; default secure headers are present; CORS refuses unlisted and `null` origins; and ownership is proven to come from the token rather than from a request parameter, using probe handlers that exist only in the test sources.

Cross-customer order access will be re-tested against the real endpoints once the order controllers exist.

## Related documents

- [architecture.md](architecture.md)
- [deployment.md](deployment.md)
- [ADR 0005](adr/0005-jwt-authentication.md)
