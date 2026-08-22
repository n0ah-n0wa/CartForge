package com.example.ecommerce.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

@MappedSuperclass
public abstract class VersionedEntity extends BaseEntity {

    @Version
    @Column(name = PersistenceConventions.VERSION_COLUMN, nullable = false)
    private Long version;

    protected VersionedEntity() {
    }

    public Long getVersion() {
        return version;
    }
}
