package com.example.ecommerce.common.persistence;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared SQL and JPA naming rules. Flyway remains the schema source of truth;
 * these helpers keep later migrations and {@code @Table}/{@code @JoinColumn}
 * names consistent.
 */
public final class PersistenceConventions {

    public static final String PK_PREFIX = "pk_";
    public static final String FK_PREFIX = "fk_";
    public static final String UQ_PREFIX = "uq_";
    public static final String CK_PREFIX = "ck_";
    public static final String IX_PREFIX = "ix_";

    public static final int MONEY_PRECISION = 19;
    public static final int MONEY_SCALE = 2;
    public static final CurrencyCode DEFAULT_CURRENCY = CurrencyCode.EUR;

    public static final String ID_COLUMN = "id";
    public static final String VERSION_COLUMN = "version";
    public static final String CREATED_AT_COLUMN = "created_at";
    public static final String UPDATED_AT_COLUMN = "updated_at";

    public static final String MIGRATION_FILENAME_PATTERN = "V[1-9][0-9]*__[a-z0-9_]+\\.sql";

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private PersistenceConventions() {
    }

    public static String primaryKeyName(String table) {
        return PK_PREFIX + requireIdentifier(table);
    }

    public static String foreignKeyName(String table, String referencedTable) {
        return FK_PREFIX + requireIdentifier(table) + "_" + requireIdentifier(referencedTable);
    }

    public static String uniqueConstraintName(String table, String... columns) {
        return UQ_PREFIX + requireIdentifier(table) + "_" + joinColumns(columns);
    }

    public static String checkConstraintName(String table, String rule) {
        return CK_PREFIX + requireIdentifier(table) + "_" + requireIdentifier(rule);
    }

    public static String indexName(String table, String... columns) {
        return IX_PREFIX + requireIdentifier(table) + "_" + joinColumns(columns);
    }

    public static String foreignKeyColumn(String referencedTableSingular) {
        return requireIdentifier(referencedTableSingular) + "_id";
    }

    private static String joinColumns(String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("At least one column is required");
        }
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < columns.length; index++) {
            if (index > 0) {
                joined.append('_');
            }
            joined.append(requireIdentifier(columns[index]));
        }
        return joined.toString();
    }

    private static String requireIdentifier(String value) {
        Objects.requireNonNull(value, "identifier");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier fragment: " + value);
        }
        return normalized;
    }
}
