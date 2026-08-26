-- Align indexes with order list queries (user history, admin status filter, and
-- unfiltered admin list). Default sort is created_at DESC, id as tie-break
-- (OrderSortSupport). Existing ix_orders_user_id / ix_orders_status remain for
-- equality-only lookups; composites cover the sorted page scans.

CREATE INDEX ix_orders_user_id_created_at_id
    ON orders (user_id, created_at DESC, id DESC);

CREATE INDEX ix_orders_status_created_at_id
    ON orders (status, created_at DESC, id DESC);

CREATE INDEX ix_orders_created_at_id
    ON orders (created_at DESC, id DESC);
