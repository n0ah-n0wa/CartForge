package com.example.ecommerce.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record ApplicationProperties(
        @NotNull @Valid Jwt jwt,
        @NotNull @Valid Cors cors,
        @NotNull @Valid Pagination pagination,
        @NotNull @Valid RateLimit rateLimit
) {

    public record Jwt(
            @NotBlank String secret,
            @Min(1) long expirationMs
    ) {
        @Override
        public String toString() {
            return "Jwt[secret=****, expirationMs=" + expirationMs + "]";
        }
    }

    public record Cors(
            @NotEmpty List<@NotBlank String> origins
    ) {
        public Cors {
            origins = origins == null ? List.of() : List.copyOf(origins);
        }

        @Override
        public List<String> origins() {
            return List.copyOf(origins);
        }
    }

    public record Pagination(
            @Min(1) int defaultPageSize,
            @Min(1) int maxPageSize
    ) {
    }

    public record RateLimit(@NotNull @Valid Auth auth) {

        public static RateLimit defaults() {
            return new RateLimit(Auth.defaults());
        }

        public record Auth(
                boolean enabled,
                @Min(1) int limit,
                @Min(1) int windowSeconds,
                List<String> trustedProxies
        ) {
            public Auth {
                trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
            }

            public static Auth defaults() {
                return new Auth(true, 20, 60, List.of());
            }

            @Override
            public List<String> trustedProxies() {
                return List.copyOf(trustedProxies);
            }
        }
    }

    @AssertTrue(message = "app.pagination.max-page-size must be greater than or equal to default-page-size")
    public boolean isPaginationRangeValid() {
        return pagination == null || pagination.maxPageSize() >= pagination.defaultPageSize();
    }
}
