package ru.ba6kir.tradelab.common.metadata;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Read-only installation metadata owned and populated by Flyway. */
@Entity
@Immutable
@Table(name = "application_metadata")
public class ApplicationMetadata {

    @Id
    private Short id;

    @Column(name = "application_name", nullable = false, length = 100)
    private String applicationName;

    @Column(name = "installed_at", nullable = false)
    private Instant installedAt;

    protected ApplicationMetadata() {
    }

    public Short getId() {
        return id;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public Instant getInstalledAt() {
        return installedAt;
    }
}
