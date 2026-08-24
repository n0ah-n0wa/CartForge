package com.example.ecommerce.common.config;

import com.example.ecommerce.common.exception.ApiErrorResponse;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI ecommerceOpenApi() {
        Schema<?> errorSchema = new Schema<ApiErrorResponse>()
                .type("object")
                .description("Standard API error envelope. Never contains stack traces, SQL, or secrets.")
                .addProperty("timestamp", new Schema<>().type("string").format("date-time")
                        .example("2026-08-22T18:30:00.000Z"))
                .addProperty("status", new Schema<>().type("integer").example(409))
                .addProperty("code", new Schema<>().type("string").example("INSUFFICIENT_STOCK"))
                .addProperty("message", new Schema<>().type("string").example("Insufficient stock for product 42"))
                .addProperty("path", new Schema<>().type("string").example("/api/v1/orders"))
                .addProperty(
                        "correlationId",
                        new Schema<>().type("string").example("7c9e6679-7425-40de-944b-e07fc1f90ae7"));

        return new OpenAPI()
                .info(new Info()
                        .title("CartForge E-Commerce API")
                        .version("v1")
                        .description(
                                "Versioned REST API under /api/v1. JSON fields are camelCase; "
                                        + "timestamps are ISO-8601 Instant values in UTC; money uses "
                                        + "BigDecimal with an explicit currency enum. Authenticated "
                                        + "requests send Authorization: Bearer <JWT>."))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"))
                        .addSchemas("ApiErrorResponse", errorSchema));
    }
}
