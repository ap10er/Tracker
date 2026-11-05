package com.example.ozon_tracker.dto;

import java.math.BigDecimal;

/**
 * Одна позиция корзины, извлечённая со страницы Ozon (результат скрейпинга).
 *
 * @param sku      артикул товара на Ozon — ключ сопоставления с БД
 * @param name     название товара
 * @param url      ссылка на карточку товара
 * @param price    актуальная цена за штуку (null — цену прочитать не удалось)
 * @param quantity количество в корзине
 * @param inStock  доступен ли товар к заказу
 */
public record OzonCartItemDto(
        String sku,
        String name,
        String url,
        BigDecimal price,
        int quantity,
        boolean inStock
) {

    public static OzonCartItemDto of(String sku, String name, String url, BigDecimal price, int quantity) {
        return new OzonCartItemDto(sku, name, url, price, Math.max(quantity, 1), true);
    }

    /** Позиция без цены: использована при сбое парсинга или когда товар недоступен. */
    public static OzonCartItemDto unavailable(String sku, String name, String url) {
        return new OzonCartItemDto(sku, name, url, null, 1, false);
    }
}
