package com.example.ozon_tracker.scraping;

import com.example.ozon_tracker.dto.OzonCartItemDto;

import java.util.List;

/**
 * Результат одного «похода» в браузер: снимок страницы корзины.
 *
 * @param items   распознанные позиции (пусто — если корзина реально пуста или ничего не распарсилось)
 * @param blocked страница закрыта anti-bot-защитой (слайдер/капча «Ой!»)
 * @param loginRedirect Ozon редирекнул на /login — cookies протухли
 * @param finalUrl    итоговый URL после загрузки страницы (для диагностики)
 * @param htmlDumpPath сохранённый HTML (null если не сохраняли) — пригодится, когда селекторы сломаются
 */
public record CartSnapshot(
        List<OzonCartItemDto> items,
        boolean blocked,
        boolean loginRedirect,
        String finalUrl,
        String htmlDumpPath
) {

    public static CartSnapshot blocked(String url) {
        return new CartSnapshot(List.of(), true, false, url, null);
    }

    public static CartSnapshot loginRequired(String url) {
        return new CartSnapshot(List.of(), false, true, url, null);
    }

    public boolean needsAttention() {
        return blocked || loginRedirect;
    }
}
