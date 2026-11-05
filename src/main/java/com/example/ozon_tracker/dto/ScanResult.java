package com.example.ozon_tracker.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Результат одного прогона: что просканировали, что изменилось, что отправить в Telegram.
 */
public record ScanResult(
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        int scannedItems,
        int newItems,
        List<PriceComparison> comparisons,
        List<RemovedItem> removedItems,
        List<OutOfStockItem> outOfStockItems,
        BigDecimal cartTotal,
        String failureReason
) {

    public boolean isFailed() {
        return failureReason != null && !failureReason.isBlank();
    }

    public long countAtMinimum() {
        return comparisons.stream().filter(PriceComparison::isAtMinimum).count();
    }

    /** Позиция, которой больше нет в корзине (удалена вручную или скрыта Ozon). */
    public record RemovedItem(String sku, String name, BigDecimal lastKnownPrice, LocalDateTime removedAt) {
    }

    /** Позиция, которая осталась в корзине, но товар закончился. */
    public record OutOfStockItem(String sku, String name, String url, BigDecimal lastKnownPrice) {
    }
}
