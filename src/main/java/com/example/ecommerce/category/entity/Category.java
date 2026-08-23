package com.example.ecommerce.category.entity;

import com.example.ecommerce.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.regex.Pattern;

@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 180)
    private String slug;

    @Column(length = 4000)
    private String description;

    @Column(nullable = false)
    private boolean active;

    protected Category() {
    }

    public static Category create(String name, String slug, String description) {
        Category category = new Category();
        category.name = requireText(name, "name");
        category.slug = normalizeSlug(slug);
        category.description = normalizeDescription(description);
        category.active = true;
        return category;
    }

    public void rename(String name, String slug) {
        this.name = requireText(name, "name");
        this.slug = normalizeSlug(slug);
    }

    public void changeDescription(String description) {
        this.description = normalizeDescription(description);
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
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

    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return "Category[id=" + getId() + ", slug=" + slug + ", active=" + active + "]";
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
