package com.example.ecommerce.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdsTest {

    @Test
    void generatesWhenMissing() {
        String first = CorrelationIds.resolve(null);
        String second = CorrelationIds.resolve("   ");
        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void acceptsSafeClientValues() {
        assertThat(CorrelationIds.resolve("req-123_ABC.def")).isEqualTo("req-123_ABC.def");
    }

    @Test
    void rejectsUnsafeClientValues() {
        assertThat(CorrelationIds.resolve("bad\nid")).isNotEqualTo("bad\nid");
        assertThat(CorrelationIds.resolve("has space")).doesNotContain(" ");
        assertThat(CorrelationIds.resolve("a".repeat(CorrelationIds.MAX_LENGTH + 1)))
                .hasSizeLessThanOrEqualTo(CorrelationIds.MAX_LENGTH);
    }

    @Test
    void currentReadsMdc() {
        MDC.put(CorrelationIds.MDC_KEY, "from-mdc");
        try {
            assertThat(CorrelationIds.current()).contains("from-mdc");
            assertThat(CorrelationIds.currentOrEmpty()).isEqualTo("from-mdc");
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
