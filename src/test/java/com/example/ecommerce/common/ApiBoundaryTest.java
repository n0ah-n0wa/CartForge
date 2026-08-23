package com.example.ecommerce.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the rules the specification places on API boundaries: DTOs are
 * immutable, and entities are never handed out through them.
 */
class ApiBoundaryTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    @Test
    void everyDtoIsAnImmutableRecord() throws Exception {
        List<Class<?>> dtos = dtoClasses();
        assertThat(dtos).isNotEmpty();

        for (Class<?> dto : dtos) {
            assertThat(dto.isRecord())
                    .as("%s must be an immutable record", dto.getName())
                    .isTrue();
        }
    }

    @Test
    void noDtoExposesAPersistenceEntity() throws Exception {
        List<Class<?>> dtos = dtoClasses();
        assertThat(dtos).isNotEmpty();

        for (Class<?> dto : dtos) {
            for (RecordComponent component : dto.getRecordComponents()) {
                assertThat(component.getType().isAnnotationPresent(Entity.class))
                        .as("%s.%s exposes the entity %s",
                                dto.getSimpleName(), component.getName(), component.getType().getName())
                        .isFalse();
            }
        }
    }

    /**
     * Section 93: ownership comes from the security context. An inbound payload
     * that carried an owner id would invite a handler to trust it.
     */
    @Test
    void noInboundPayloadAcceptsAnOwnerIdentifier() throws Exception {
        List<Class<?>> inbound = dtoClasses().stream()
                .filter(dto -> dto.getSimpleName().endsWith("Command") || dto.getSimpleName().endsWith("Request"))
                .toList();
        assertThat(inbound).isNotEmpty();

        for (Class<?> dto : inbound) {
            for (RecordComponent component : dto.getRecordComponents()) {
                assertThat(component.getName())
                        .as("%s must not let the caller nominate an owner", dto.getSimpleName())
                        .isNotIn("userId", "ownerId", "customerId");
            }
        }
    }

    @Test
    void everyFeatureDtoPackageIsCovered() throws Exception {
        assertThat(dtoClasses())
                .extracting(Class::getPackageName)
                .contains(
                        "com.example.ecommerce.auth.dto",
                        "com.example.ecommerce.user.dto",
                        "com.example.ecommerce.category.dto",
                        "com.example.ecommerce.product.dto",
                        "com.example.ecommerce.cart.dto",
                        "com.example.ecommerce.order.dto");
    }

    private static List<Class<?>> dtoClasses() throws IOException, ClassNotFoundException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(SOURCES)) {
            files = stream
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                    .filter(path -> SOURCES.relativize(path).getParent().endsWith("dto"))
                    .toList();
        }

        List<Class<?>> classes = new ArrayList<>();
        for (Path file : files) {
            String relative = SOURCES.relativize(file).toString();
            String className = relative
                    .substring(0, relative.length() - ".java".length())
                    .replace(File.separatorChar, '.');
            classes.add(Class.forName(className));
        }
        return classes;
    }
}
