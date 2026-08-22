# Database

**Status:** planned. No Flyway migrations, entities, or running PostgreSQL schema exist in this repository yet.

PostgreSQL is the authoritative persistence layer. Redis must not be treated as durable business state. See [ADR 0002](adr/0002-postgresql-source-of-truth.md) and [ADR 0003](adr/0003-redis-as-cache.md).

## Engine and ownership

| Topic | Requirement |
|---|---|
| Engine | PostgreSQL |
| Migrations | Flyway; files are immutable once committed |
| Hibernate in production | `ddl-auto=validate` or an equivalent validation-only strategy |
| Schema creation | The application must not rely on Hibernate to create production tables |

Specified migration naming:

```text
V1__create_users.sql
V2__create_categories.sql
V3__create_products.sql
V4__create_carts.sql
V5__create_orders.sql
```

Later versions may add constraints, indexes, or supporting tables (for example idempotency records) as implementation requires. Those files do not exist yet.

## Core entities

The specification requires these entities. Field lists are the specified columns, not an implemented schema.

### User

`id`, `email`, `passwordHash`, `firstName`, `lastName`, `role`, `enabled`, `createdAt`, `updatedAt`, `version`

- Email is unique. Comparison is case-insensitive.
- Password is stored only as a hash (BCrypt or Argon2).
- Role is an enum: `CUSTOMER`, `ADMIN`.
- Registration default role is `CUSTOMER`. Users are enabled by default.

### Category

`id`, `name`, `slug`, `description`, `active`, `createdAt`, `updatedAt`

- Name and slug are unique. Slug is URL-safe.
- Inactive categories are omitted from the default public catalog.
- Deleting a category must not silently delete products. Deletion is rejected when products exist, or requires explicit reassignment.
- When historical references exist, deactivation (`active = false`) is preferred over physical delete.

### Product

`id`, `sku`, `name`, `slug`, `description`, `price`, `currency`, `stockQuantity`, `categoryId`, `active`, `createdAt`, `updatedAt`, `version`

- SKU and slug are unique.
- Price is non-negative `BigDecimal`. Currency is explicit; default is EUR.
- `stockQuantity` must never be negative.
- A product belongs to a category.
- Inactive products and zero-stock products are not purchasable.
- Updates use optimistic locking (`version`).

### Cart and CartItem

Cart: `id`, `userId`, `createdAt`, `updatedAt`

CartItem: `id`, `cartId`, `productId`, `quantity`, `createdAt`, `updatedAt`

- Each customer has at most one cart (planned as one row per user).
- Quantity must be greater than zero.
- The same product must not appear twice in one cart; adding again increases quantity.
- Cart contents do not reserve inventory. Stock is checked again at checkout.

### Order and OrderItem

Order: `id`, `orderNumber`, `userId`, `status`, `totalAmount`, `currency`, `shippingAddress`, `createdAt`, `updatedAt`, `version`

OrderItem: `id`, `orderId`, `productId`, `productName`, `sku`, `unitPrice`, `quantity`, `lineTotal`

- `orderNumber` is unique and human-readable (example: `ORD-2026-000001`). It must not be only the internal database id.
- Generation must remain unique with multiple application replicas. The planned approach is a PostgreSQL sequence (or equivalent) allocated inside the checkout transaction.
- Order items snapshot name, SKU, and unit price.

## Relationships and foreign keys

```text
User 1 ─── 1 Cart
Cart 1 ─── N CartItem
Product 1 ─── N CartItem

User 1 ─── N Order
Order 1 ─── N OrderItem
Product 1 ─── N OrderItem

Category 1 ─── N Product
```

Foreign-key behavior must be configured explicitly when migrations are written. Category deletion must not cascade silently to products.

## Constraints

Database constraints must complement application validation. At minimum:

| Invariant | Enforcement |
|---|---|
| Unique email | Unique constraint; case-insensitive (for example `LOWER(email)` or `citext`) |
| Unique SKU, product slug, category name, category slug | Unique constraints |
| Unique order number | Unique constraint |
| Unique product per cart | Unique `(cart_id, product_id)` |
| `price >= 0` | Check constraint |
| `stock_quantity >= 0` | Check constraint |
| Line `quantity > 0` | Check constraint |
| Required columns | `NOT NULL` where the domain requires a value |
| Timestamps | Timezone-aware (`timestamptz` / `Instant`) |

Money must never be stored as `float` or `double`.

## Required indexes

Minimum indexes from the specification:

```text
users.email
products.sku
products.slug
products.category_id
products.active
products.price
products.name
categories.slug
orders.order_number
orders.user_id
orders.status
cart_items.cart_id
cart_items.product_id
```

Additional indexes should follow actual query patterns, not every column.

Product text search uses PostgreSQL (`ILIKE` / `LIKE` or equivalent). A btree on `products.name` supports equality and sorting; leading-wildcard search will not use that index efficiently. That limit is accepted at portfolio scale. External search engines are out of scope.

## Transactions

Services own transaction boundaries. Controllers must not begin or commit transactions.

Required transactional operations:

- checkout (all checkout steps in one transaction);
- order cancellation with stock restore;
- inventory modification;
- administrative order status updates where necessary.

Default PostgreSQL isolation is used unless a specific operation documents a stronger level. Transactions should stay as short as reasonably possible. Checkout must not perform remote I/O inside the transaction.

Failed optimistic-lock updates become a controlled application error (typically HTTP 409). The database check constraint remains the last guard against `stock_quantity < 0`.

## Inventory storage

Inventory is `Product.stockQuantity`. Internal operations: increase, decrease, restore. There is no inventory table and no public inventory API.

## Idempotency records

Order creation should honor `Idempotency-Key` for the same authenticated user. Redis is allowed by the specification as a store, but Redis outage must not break the API. The planned store is a PostgreSQL table written in the same checkout transaction so duplicate submits cannot create two orders when Redis is down.

## Seed data

Development may load deterministic seed data: several categories, at least 15 products, at least one admin, and at least one customer. Credentials will be documented as development-only when that data is added. Production must not apply development seed users automatically.

## Related documents

- [architecture.md](architecture.md)
- [security.md](security.md)
- [ADR 0002](adr/0002-postgresql-source-of-truth.md)
- [ADR 0004](adr/0004-optimistic-locking.md)
