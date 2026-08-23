package com.example.ecommerce.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductSpecificationsTest {

    @Test
    void escapeLikeNeutralizesWildcardCharacters() {
        assertThat(ProductSpecifications.escapeLike("100%_off\\deal"))
                .isEqualTo("100\\%\\_off\\\\deal");
    }
}
