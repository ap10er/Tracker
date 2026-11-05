package com.example.ozon_tracker.model;

import com.example.ozon_tracker.model.enums.Availability;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Товар из корзины Ozon и накопленная по нему статистика цен.
 */
@Entity
@Table(name = "cart_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SKU товара на Ozon — естественный уникальный ключ позиции. */
    @Column(name = "ozon_sku", unique = true, nullable = false, length = 50)
    private String ozonSku;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "url", length = 1000)
    private String url;

    /** Цена, зафиксированная на последнем замере. */
    @Column(name = "current_price", precision = 12, scale = 2)
    private BigDecimal currentPrice;

    /** Минимальная цена за всё время наблюдений — именно с ней сравниваем актуальную. */
    @Column(name = "historical_min_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal historicalMinPrice;

    /** Количество штук в корзине. */
    @Column(name = "quantity")
    @Builder.Default
    private Integer quantity = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability", length = 20)
    @Builder.Default
    private Availability availability = Availability.UNKNOWN;

    /** Позиция присутствует в корзине (false — товар удалён или больше недоступен). */
    @Column(name = "is_in_cart")
    @Builder.Default
    private Boolean inCart = true;

    @Column(name = "added_to_cart_at")
    private LocalDateTime addedToCartAt;

    @Column(name = "removed_from_cart_at")
    private LocalDateTime removedFromCartAt;

    /** Когда позиция последний раз наблюдалась в корзине. */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    @OneToMany(mappedBy = "cartItem", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<PriceHistory> priceHistory = new ArrayList<>();

    @PrePersist
    @PreUpdate
    void touch() {
        LocalDateTime now = LocalDateTime.now();
        lastUpdatedAt = now;
        if (Boolean.TRUE.equals(inCart) && addedToCartAt == null) {
            addedToCartAt = now;
        }
    }
}
