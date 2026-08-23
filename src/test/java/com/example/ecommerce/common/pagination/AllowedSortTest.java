package com.example.ecommerce.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class AllowedSortTest {

    private final AllowedSort allowed = AllowedSort.builder()
            .allow("name", "name")
            .allow("price", "price")
            .defaultSort(Sort.by(Sort.Direction.ASC, "name"))
            .tieBreaker("id")
            .build();

    @Test
    void defaultsToConfiguredSortWithStableTieBreaker() {
        Sort sort = allowed.resolve(null);

        assertThat(sort.stream().map(Sort.Order::getProperty).toList()).containsExactly("name", "id");
        assertThat(sort.getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void mapsAllowlistedFieldsAndDirections() {
        Sort sort = allowed.resolve(List.of("price,desc", "name,asc"));

        assertThat(sort.stream().map(Sort.Order::getProperty).toList()).containsExactly("price", "name", "id");
        assertThat(sort.getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sort.getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void rejectsUnknownFieldsWithoutPassingThemToQueries() {
        assertThatThrownBy(() -> allowed.resolve(List.of("password")))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("password");
    }

    @Test
    void rejectsInvalidDirectionsAndMalformedValues() {
        assertThatThrownBy(() -> allowed.resolve(List.of("price,sideways")))
                .isInstanceOf(InvalidSortException.class);
        assertThatThrownBy(() -> allowed.resolve(List.of("price,asc,extra")))
                .isInstanceOf(InvalidSortException.class);
    }
}

class PageRequestsTest {

    @Test
    void capsPageSizeAndNormalizesNegativePage() {
        Pageable pageable = PageRequests.of(-3, 500, 20, 100, Sort.by("name"));

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(100);
    }

    @Test
    void usesDefaultSizeWhenMissingOrInvalid() {
        assertThat(PageRequests.of(0, null, 20, 100, Sort.unsorted()).getPageSize()).isEqualTo(20);
        assertThat(PageRequests.of(0, 0, 20, 100, Sort.unsorted()).getPageSize()).isEqualTo(20);
        assertThat(PageRequests.of(0, -1, 20, 100, Sort.unsorted()).getPageSize()).isEqualTo(20);
    }
}
