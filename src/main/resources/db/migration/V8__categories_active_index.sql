-- Speeds up the public active-category listing: WHERE active = true ORDER BY name.
CREATE INDEX ix_categories_active_name
    ON categories (active, name);
