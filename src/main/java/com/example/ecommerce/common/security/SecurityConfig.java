package com.example.ecommerce.common.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Stateless Bearer authentication. Every request carries its own token; no
     * session is created and nothing is stored server-side.
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter converter,
            SecurityProblemHandlers problemHandlers)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .headers(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandlers.unauthorized())
                        .accessDeniedHandler(problemHandlers.forbidden()))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll();
                    if (environment.matchesProfiles("dev")) {
                        auth.requestMatchers(
                                        "/v3/api-docs",
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**")
                                .permitAll();
                    }
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login")
                            .permitAll();
                    // The specification lists only the collection and the single
                    // resource. A broader /** matcher would make a future nested
                    // catalog path public by accident.
                    auth.requestMatchers(
                                    HttpMethod.GET,
                                    "/api/v1/products",
                                    "/api/v1/products/{id}",
                                    "/api/v1/categories",
                                    "/api/v1/categories/{id}")
                            .permitAll();
                    // Anything other than a read of the catalog is a write, and
                    // writes are administrative. Without this the requests would
                    // fall through to anyRequest() and any logged-in customer
                    // could create or delete products.
                    auth.requestMatchers(
                                    "/api/v1/products",
                                    "/api/v1/products/**",
                                    "/api/v1/categories",
                                    "/api/v1/categories/**")
                            .hasRole("ADMIN");
                    // Customers cancel through POST /orders/{id}/cancel. Status
                    // changes are administrative even if a handler is later mapped
                    // onto the customer resource.
                    auth.requestMatchers("/api/v1/orders/{id}/status", "/api/v1/orders/{id}/status/**")
                            .hasRole("ADMIN");
                    auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(problemHandlers.unauthorized())
                        .accessDeniedHandler(problemHandlers.forbidden())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    /**
     * Delegating encoder so stored hashes carry their algorithm prefix and can be
     * upgraded later without invalidating existing credentials. The current
     * default is BCrypt, which the specification allows.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
