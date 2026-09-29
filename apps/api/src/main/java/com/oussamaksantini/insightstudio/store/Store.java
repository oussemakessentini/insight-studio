package com.oussamaksantini.insightstudio.store;

import com.oussamaksantini.insightstudio.business.Business;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;

@Entity
@Table(
        name = "stores",
        uniqueConstraints = @UniqueConstraint(name = "uq_stores_business_code", columnNames = {"business_id", "code"}),
        indexes = @Index(name = "idx_stores_business_id", columnList = "business_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "business_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_stores_business"))
    private Business business;

    @Setter
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Setter
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Setter
    @Column(name = "city", length = 100)
    private String city;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public Store(Business business, String code, String name, String city) {
        this.business = business;
        this.code = code;
        this.name = name;
        this.city = city;
    }
}
