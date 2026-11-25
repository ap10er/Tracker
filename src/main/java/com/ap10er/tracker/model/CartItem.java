package com.ap10er.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "cart_items")
public class CartItem {

    /** Артикул Wildberries — первичный ключ. */
    @Id
    @Column(nullable = false)
    private String article;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    /** Минимальная цена, зафиксированная для товара за всё время наблюдений. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal minPrice;

    public CartItem() {
    }

    public CartItem(String name, String article, BigDecimal price) {
        this(name, article, price, price);
    }

    public CartItem(String name, String article, BigDecimal price, BigDecimal minPrice) {
        this.name = name;
        this.article = article;
        this.price = price;
        this.minPrice = minPrice;
    }

    public String getArticle() {
        return article;
    }

    public void setArticle(String article) {
        this.article = article;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(BigDecimal minPrice) {
        this.minPrice = minPrice;
    }
}
