package com.oussamaksantini.insightstudio.business;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "businesses",
        uniqueConstraints = @UniqueConstraint(name = "uq_businesses_slug", columnNames = "slug"),
        check = @CheckConstraint(name = "ck_businesses_currency", constraint = "currency ~ '^[A-Z]{3}$'"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Setter
    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    @Setter
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Setter
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public Business(String name, String slug, String currency, String timeZone) {
        this.name = name;
        this.slug = slug;
        this.currency = currency;
        this.timeZone = timeZone;
    }

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }
}
