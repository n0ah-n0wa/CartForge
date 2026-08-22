-- Infrastructure baseline only. Domain tables are added in later migrations.
-- Hibernate must never create or update the schema; Flyway is the source of truth.
COMMENT ON SCHEMA public IS 'CartForge schema managed by Flyway';
