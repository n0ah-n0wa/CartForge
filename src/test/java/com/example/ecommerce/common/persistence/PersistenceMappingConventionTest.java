package com.example.ecommerce.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.junit.jupiter.api.Test;

class PersistenceMappingConventionTest {

    @Test
    void baseEntityUsesIdentityKeysAndInstantTimestamps() throws NoSuchFieldException {
        assertThat(BaseEntity.class.getAnnotation(MappedSuperclass.class)).isNotNull();

        var id = BaseEntity.class.getDeclaredField("id");
        assertThat(id.getType()).isEqualTo(Long.class);
        assertThat(id.getAnnotation(Id.class)).isNotNull();
        assertThat(id.getAnnotation(GeneratedValue.class).strategy()).isEqualTo(GenerationType.IDENTITY);

        var createdAt = BaseEntity.class.getDeclaredField("createdAt");
        assertThat(createdAt.getType()).isEqualTo(Instant.class);
        assertThat(createdAt.getAnnotation(CreationTimestamp.class)).isNotNull();
        assertThat(createdAt.getAnnotation(Column.class).nullable()).isFalse();
        assertThat(createdAt.getAnnotation(Column.class).updatable()).isFalse();

        var updatedAt = BaseEntity.class.getDeclaredField("updatedAt");
        assertThat(updatedAt.getType()).isEqualTo(Instant.class);
        assertThat(updatedAt.getAnnotation(UpdateTimestamp.class)).isNotNull();
        assertThat(updatedAt.getAnnotation(Column.class).nullable()).isFalse();
    }

    @Test
    void versionedEntityAddsOptimisticLockColumn() throws NoSuchFieldException {
        assertThat(VersionedEntity.class.getAnnotation(MappedSuperclass.class)).isNotNull();
        assertThat(BaseEntity.class).isAssignableFrom(VersionedEntity.class);

        var version = VersionedEntity.class.getDeclaredField("version");
        assertThat(version.getType()).isEqualTo(Long.class);
        assertThat(version.getAnnotation(Version.class)).isNotNull();
        assertThat(version.getAnnotation(Column.class).nullable()).isFalse();
        assertThat(version.getAnnotation(Column.class).name())
                .isEqualTo(PersistenceConventions.VERSION_COLUMN);
    }
}
