package com.example.ecommerce.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CommittedConfigurationSafetyTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void committedYamlDoesNotEmbedSecretDefaults() throws IOException {
        List<Path> yamlFiles = listYamlFiles();
        assertThat(yamlFiles).isNotEmpty();

        for (Path file : yamlFiles) {
            String text = Files.readString(file);
            assertThat(text)
                    .as("%s must not embed placeholder secret defaults", file.getFileName())
                    .doesNotContain(":change-me")
                    .doesNotContain("change-me-use-a-long-random-value");
            assertThat(text.lines().toList())
                    .as("%s must not hardcode a datasource password", file.getFileName())
                    .noneMatch(CommittedConfigurationSafetyTest::isHardcodedPasswordAssignment);
        }
    }

    @Test
    void productionYamlHasNoLocalhostFallbacks() throws IOException {
        String production = Files.readString(RESOURCES.resolve("application-prod.yml"));
        assertThat(production.lines().filter(line -> !line.trim().startsWith("#")))
                .noneMatch(line -> line.contains("localhost"));
        assertThat(production).doesNotContain("${DATABASE_URL:");
        assertThat(production).doesNotContain("${DATABASE_PASSWORD:");
        assertThat(production).doesNotContain("${JWT_SECRET:");
        assertThat(production).doesNotContain("${REDIS_URL:");
        assertThat(production).doesNotContain("${CORS_ORIGINS:");
    }

    private static List<Path> listYamlFiles() throws IOException {
        try (Stream<Path> stream = Files.list(RESOURCES)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .toList();
        }
    }

    private static boolean isHardcodedPasswordAssignment(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("password:")
                && !trimmed.contains("${")
                && !trimmed.equals("password:");
    }
}
