package com.example.ozon_tracker.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    @Column(name = "ozon_item_id", unique = true, nullable = false, length = 50)
    private String ozonItemId;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "url", length = 1000)
    private String url;

    @Column(name = "current_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "historical_min_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal historicalMinPrice;

    @Column(name = "is_in_cart")
    private Boolean isInCart = true;

    @Column(name = "added_to_cart_at")
    private LocalDateTime addedToCartAt;

    @Column(name = "removed_from_cart_at")
    private LocalDateTime removedFromCartAt;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    @OneToMany(mappedBy = "cartItem", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    private List<PriceHistory> priceHistory = new ArrayList<>();

    @PrePersist
    @PreUpdate
    public void updateTimestamps() {
        lastUpdatedAt = LocalDateTime.now();
        if (isInCart && addedToCartAt == null) {
            addedToCartAt = LocalDateTime.now();
        }
    }
}