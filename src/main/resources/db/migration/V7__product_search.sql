-- Product catalog search: normalized text + trigram indexes (PostgreSQL only).
-- External search engines are out of scope per the specification.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE products
    ADD COLUMN search_text TEXT
        GENERATED ALWAYS AS (
            lower(
                btrim(coalesce(name, '')) || ' ' ||
                btrim(coalesce(sku, '')) || ' ' ||
                btrim(coalesce(description, ''))
            )
        ) STORED;

COMMENT ON COLUMN products.search_text IS
    'Lowercased name/sku/description for ILIKE/LIKE catalog search';

CREATE INDEX ix_products_search_text_trgm
    ON products
    USING gin (search_text gin_trgm_ops);

-- Supports the common browse pattern: active + category + price range.
CREATE INDEX ix_products_active_category_price
    ON products (active, category_id, price);
