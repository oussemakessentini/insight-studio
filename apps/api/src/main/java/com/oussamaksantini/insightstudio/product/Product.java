package com.oussamaksantini.insightstudio.product;

import com.oussamaksantini.insightstudio.business.Business;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;

@Entity
@Table(
        name = "products",
        uniqueConstraints = @UniqueConstraint(name = "uq_products_business_sku", columnNames = {"business_id", "sku"}),
        check = @CheckConstraint(name = "ck_products_list_price", constraint = "list_price >= 0"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "business_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_products_business"))
    private Business business;

    @Setter
    @Column(name = "sku", nullable = false, length = 50)
    private String sku;

    @Setter
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Setter
    @Column(name = "category", nullable = false, length = 100)
    private String category;

    /** Current catalogue price. Historical revenue uses {@code SaleItem.unitPrice} instead. */
    @Setter
    @Column(name = "list_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal listPrice;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public Product(Business business, String sku, String name, String category, BigDecimal listPrice) {
        this.business = business;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.listPrice = listPrice;
    }
}
