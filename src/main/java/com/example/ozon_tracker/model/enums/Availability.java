package com.example.ozon_tracker.model.enums;

/**
 * Наличие товара. Ozon может убрать позицию из корзины или товар может закончиться на складе.
 */
public enum Availability {

    AVAILABLE("✅ в наличии"),
    OUT_OF_STOCK("❌ нет в наличии"),
    NOT_IN_CART("➖ убран из корзины"),
    UNKNOWN("❓ неизвестно");

    private final String label;

    Availability(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static Availability fromInStock(boolean inStock) {
        return inStock ? AVAILABLE : OUT_OF_STOCK;
    }
}
