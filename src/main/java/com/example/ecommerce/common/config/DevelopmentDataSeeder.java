package com.example.ecommerce.common.config;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic development catalog and users. Never active outside {@code dev},
 * and only when {@code app.seed.enabled=true}.
 */
@Component
@Profile("dev")
@Order(100)
public class DevelopmentDataSeeder implements ApplicationRunner {

    public static final String ADMIN_EMAIL = "admin@cartforge.local";
    public static final String CUSTOMER_EMAIL = "customer@cartforge.local";
    /** Documented development-only password (12+ chars). */
    public static final String DEV_PASSWORD = "CartForge-Dev-Only-1";

    private static final Logger log = LoggerFactory.getLogger(DevelopmentDataSeeder.class);

    private final SeedProperties seedProperties;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;

    public DevelopmentDataSeeder(
            SeedProperties seedProperties,
            UserRepository userRepository,
            CategoryRepository categoryRepository,
            ProductRepository productRepository,
            PasswordEncoder passwordEncoder) {
        this.seedProperties = seedProperties;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedProperties.enabled()) {
            return;
        }
        if (userRepository.count() > 0 || productRepository.count() > 0) {
            log.info("event=seed_skipped reason=existing_data");
            return;
        }

        String hash = passwordEncoder.encode(DEV_PASSWORD);
        userRepository.save(User.create(ADMIN_EMAIL, hash, "Admin", "User", UserRole.ADMIN));
        userRepository.save(User.registerCustomer(CUSTOMER_EMAIL, hash, "Demo", "Customer"));

        Category books = categoryRepository.save(Category.create("Books", "books", "Printed books"));
        Category gadgets = categoryRepository.save(Category.create("Gadgets", "gadgets", "Electronics"));
        Category home = categoryRepository.save(Category.create("Home", "home", "Home goods"));

        for (int i = 1; i <= 15; i++) {
            Category category = i <= 5 ? books : i <= 10 ? gadgets : home;
            String sku = "SEED-%02d".formatted(i);
            productRepository.save(Product.create(
                    sku,
                    "Seed Product %02d".formatted(i),
                    "seed-product-%02d".formatted(i),
                    "Deterministic development product %d".formatted(i),
                    BigDecimal.valueOf(10 + i).setScale(2),
                    CurrencyCode.EUR,
                    50 + i,
                    category));
        }

        log.info(
                "event=seed_completed adminEmail={} customerEmail={} products={}",
                ADMIN_EMAIL,
                CUSTOMER_EMAIL,
                15);
    }
}
