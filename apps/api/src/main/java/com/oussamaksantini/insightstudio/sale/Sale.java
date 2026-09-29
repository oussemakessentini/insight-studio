package com.oussamaksantini.insightstudio.sale;

import com.oussamaksantini.insightstudio.product.Product;
import com.oussamaksantini.insightstudio.store.Store;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;

@Entity
@Table(
        name = "sales",
        uniqueConstraints = @UniqueConstraint(name = "uq_sales_store_receipt", columnNames = {"store_id", "receipt_number"}),
        indexes = {
                @Index(name = "idx_sales_store_sold_at", columnList = "store_id, sold_at"),
                @Index(name = "idx_sales_sold_at", columnList = "sold_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "store_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sales_store"))
    private Store store;

    @Column(name = "receipt_number", nullable = false, length = 40)
    private String receiptNumber;

    @Column(name = "sold_at", nullable = false)
    private Instant soldAt;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SaleItem> items = new ArrayList<>();

    public Sale(Store store, String receiptNumber, Instant soldAt) {
        this.store = store;
        this.receiptNumber = receiptNumber;
        this.soldAt = soldAt;
    }

    public SaleItem addItem(Product product, int quantity, BigDecimal unitPrice) {
        SaleItem item = new SaleItem(this, product, quantity, unitPrice);
        items.add(item);
        return item;
    }

    public List<SaleItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
