package com.example.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.order.dto.CheckoutCommand;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    void missingHeaderMeansCheckoutIsNotIdempotent() {
        assertThat(IdempotencyKeys.parse(null)).isEmpty();
    }

    @Test
    void trimsAndAcceptsPrintableAsciiKeys() {
        assertThat(IdempotencyKeys.parse("  abc-123  ")).isEqualTo(Optional.of("abc-123"));
    }

    @Test
    void rejectsBlankTooLongOrNonPrintableKeys() {
        assertThatThrownBy(() -> IdempotencyKeys.parse(" "))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> IdempotencyKeys.parse("a".repeat(IdempotencyKeys.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> IdempotencyKeys.parse("bad key"))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> IdempotencyKeys.parse("line\nbreak"))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    void fingerprintsEquivalentCheckoutBodiesTheSameWay() {
        CheckoutCommand first = new CheckoutCommand("1 Main Street");
        CheckoutCommand second = new CheckoutCommand("1 Main Street");
        CheckoutCommand different = new CheckoutCommand("2 Main Street");

        assertThat(IdempotencyKeys.fingerprint(first)).isEqualTo(IdempotencyKeys.fingerprint(second));
        assertThat(IdempotencyKeys.fingerprint(first)).isNotEqualTo(IdempotencyKeys.fingerprint(different));
        assertThat(IdempotencyKeys.fingerprint(first)).hasSize(64);
    }
}
