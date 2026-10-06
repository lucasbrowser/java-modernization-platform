package com.github.lucasoliveira.platform.order.entity;

import com.github.lucasoliveira.platform.product.entity.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    Order order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    Product product;
    @Column(nullable = false)
    Integer quantity;
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    BigDecimal unitPrice;
    public UUID getId() {
        return id;
    }
    public Order getOrder() {
        return order;
    }
    public void setOrder(Order v) {
        order = v;
    }
    public Product getProduct() {
        return product;
    }
    public void setProduct(Product v) {
        product = v;
    }
    public Integer getQuantity() {
        return quantity;
    }
    public void setQuantity(Integer v) {
        quantity = v;
    }
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
    public void setUnitPrice(BigDecimal v) {
        unitPrice = v;
    }
}
