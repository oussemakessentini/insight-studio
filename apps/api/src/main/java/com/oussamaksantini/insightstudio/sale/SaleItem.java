package com.oussamaksantini.insightstudio.sale;

import com.oussamaksantini.insightstudio.product.Product;
import jakarta.persistence.CheckConstraint;
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
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "sale_items",
        uniqueConstraints = @UniqueConstraint(name = "uq_sale_items_sale_product", columnNames = {"sale_id", "product_id"}),
        indexes = @Index(name = "idx_sale_items_product_id", columnList = "product_id"),
        check = {
                @CheckConstraint(name = "ck_sale_items_quantity", constraint = "quantity > 0"),
                @CheckConstraint(name = "ck_sale_items_unit_price", constraint = "unit_price >= 0")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "sale_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sale_items_sale"))
    private Sale sale;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sale_items_product"))
    private Product product;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Price charged at the time of sale; independent of the product's current list price. */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    SaleItem(Sale sale, Product product, int quantity, BigDecimal unitPrice) {
        this.sale = sale;
        this.product = product;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
