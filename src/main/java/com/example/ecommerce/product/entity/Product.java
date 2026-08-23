package com.example.ecommerce.product.entity;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.common.persistence.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Entity
@Table(name = "products")
public class Product extends VersionedEntity {

    static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    static final Pattern SKU_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9-]*$");

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 220)
    private String slug;

    @Column(length = 4000)
    private String description;

    @Column(
            nullable = false,
            precision = PersistenceConventions.MONEY_PRECISION,
            scale = PersistenceConventions.MONEY_SCALE)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    /**
     * Catalog reads that render the category must fetch it explicitly (see the
     * repository entity graphs). The association stays lazy so list queries do
     * not trigger a per-row category select.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "category_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_products_categories"))
    private Category category;

    @Column(nullable = false)
    private boolean active;

    protected Product() {
    }

    public static Product create(
            String sku,
            String name,
            String slug,
            String description,
            BigDecimal price,
            CurrencyCode currency,
            int stockQuantity,
            Category category) {
        Product product = new Product();
        product.sku = normalizeSku(sku);
        product.name = requireText(name, "name");
        product.slug = normalizeSlug(slug);
        product.description = normalizeDescription(description);
        product.price = normalizePrice(price);
        product.currency = Objects.requireNonNullElse(currency, PersistenceConventions.DEFAULT_CURRENCY);
        product.stockQuantity = requireNonNegativeStock(stockQuantity);
        product.category = Objects.requireNonNull(category, "category is required");
        product.active = true;
        return product;
    }

    public void rename(String name, String slug) {
        this.name = requireText(name, "name");
        this.slug = normalizeSlug(slug);
    }

    public void changeDescription(String description) {
        this.description = normalizeDescription(description);
    }

    public void changePrice(BigDecimal price, CurrencyCode currency) {
        this.price = normalizePrice(price);
        this.currency = Objects.requireNonNullElse(currency, PersistenceConventions.DEFAULT_CURRENCY);
    }

    public void reassignCategory(Category category) {
        this.category = Objects.requireNonNull(category, "category is required");
    }

    public void increaseStock(int quantity) {
        this.stockQuantity = Math.addExact(stockQuantity, requirePositive(quantity));
    }

    public void decreaseStock(int quantity) {
        this.stockQuantity = requireNonNegativeStock(stockQuantity - requirePositive(quantity));
    }

    public void changeStock(int stockQuantity) {
        this.stockQuantity = requireNonNegativeStock(stockQuantity);
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    /**
     * Inactive products and products without stock must never be purchasable.
     */
    public boolean isPurchasable() {
        return active && stockQuantity > 0;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return "Product[id=" + getId() + ", sku=" + sku + ", active=" + active + "]";
    }

    private static String normalizeSku(String sku) {
        String normalized = requireText(sku, "sku").toUpperCase(Locale.ROOT);
        if (!SKU_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("sku must contain only upper-case letters, digits, and hyphens");
        }
        return normalized;
    }

    private static String normalizeSlug(String slug) {
        String normalized = requireText(slug, "slug").toLowerCase(Locale.ROOT);
        if (!SLUG_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("slug must be URL-safe kebab-case");
        }
        return normalized;
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    /**
     * Rejects amounts the money column cannot hold exactly instead of letting
     * PostgreSQL round them away.
     */
    private static BigDecimal normalizePrice(BigDecimal price) {
        Objects.requireNonNull(price, "price is required");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        BigDecimal stripped = price.stripTrailingZeros();
        if (stripped.scale() > PersistenceConventions.MONEY_SCALE) {
            throw new IllegalArgumentException(
                    "price must not have more than " + PersistenceConventions.MONEY_SCALE + " decimal places");
        }
        return price.setScale(PersistenceConventions.MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static int requireNonNegativeStock(int stockQuantity) {
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("stockQuantity must not be negative");
        }
        return stockQuantity;
    }

    private static int requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        return quantity;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
