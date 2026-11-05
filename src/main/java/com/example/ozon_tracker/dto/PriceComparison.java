package com.example.ozon_tracker.dto;

import com.example.ozon_tracker.model.enums.ItemStatus;

import java.math.BigDecimal;

/**
 * Итог сравнения актуальной цены позиции с историческим минимумом — «строка» отчёта.
 */
public record PriceComparison(
        String sku,
        String name,
        String url,
        BigDecimal currentPrice,
        BigDecimal historicalMinPrice,
        BigDecimal previousPrice,
        BigDecimal diffToMinAbs,
        BigDecimal diffToMinPercent,
        BigDecimal diffToPreviousAbs,
        Integer quantity,
        ItemStatus status,
        boolean inStock,
        boolean firstObservation
) {

    public boolean isAtMinimum() {
        return status == ItemStatus.AT_MINIMUM;
    }

    /** Экономия/переплата относительно минимума, умноженная на количество в корзине. */
    public BigDecimal totalDiffToMin() {
        if (diffToMinAbs == null || quantity == null) {
            return null;
        }
        return diffToMinAbs.multiply(BigDecimal.valueOf(quantity));
    }
}
