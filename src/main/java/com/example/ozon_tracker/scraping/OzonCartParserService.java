package com.example.ozon_tracker.scraping;

import com.example.ozon_tracker.dto.OzonCartItemDto;
import com.microsoft.playwright.JSHandle;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Извлечение позиций корзины из DOM страницы Ozon.
 * <p>
 * Ozon — SPA с «плавающими» CSS-классами, поэтому ищем карточки по стабильным признакам:
 * ссылка на товар {@code /product/<sku>} и соседний текст цены. Парсинг делаем одним JS-вызовом
 * (быстрее и устойчивее, чем N запросов локаторов). Селекторы собраны в константу JS — при сбое
 * верстки достаточно поправить её (и сохранённый HTML в ./data/debug покажет актуальную структуру).
 */
public final class OzonCartParserService {

    private static final Logger log = LoggerFactory.getLogger(OzonCartParserService.class);

    /**
     * JS: возвращает массив объектов {sku, name, url, priceText, qtyText, inStock}.
     * sku берём из href вида /product/123456789/ — это самый стабильный якорь.
     */
    private static final String EXTRACT_JS = """
            () => {
              const results = [];
              const seen = new Set();
              // Карточка товара в корзине: контейнер, внутри которого есть ссылка /product/<sku>
              const links = Array.from(document.querySelectorAll('a[href*="/product/"]'));
              for (const a of links) {
                const m = a.getAttribute('href').match(/\\/product\\/(\\d+)/);
                if (!m) continue;
                const sku = m[1];
                // Поднимаемся до контейнера позиции (ограниченно, чтобы не схватить всю страницу)
                let card = a;
                for (let i = 0; i < 8 && card.parentElement; i++) {
                  card = card.parentElement;
                  const widget = card.getAttribute && card.getAttribute('data-widget');
                  if (widget === 'cart' || widget === 'cartWebListItems') break;
                  if (card.querySelectorAll('a[href*="/product/"]').length > 1) {
                    card = card.parentElement; // перескочили — откатываемся
                    break;
                  }
                }
                if (seen.has(sku)) continue;
                seen.add(sku);

                const text = (card.innerText || '');
                // Цена: первое вхождение числа с разделителями, рядом с валютной меткой
                const priceMatch = text.match(/(\\d[\\d\\s\\xa0.,]*)\\s*(Br|BYN|₽|руб)/i);
                // Название: aria-label ссылки или первая строка текста карточки
                let name = a.getAttribute('aria-label') || '';
                if (!name) {
                  const h = card.querySelector('h2,h3,[class*="name"]');
                  name = h ? (h.innerText || '').trim() : '';
                }
                if (!name) name = text.split('\\n')[0] || ('Товар ' + sku);
                // Количество: "N шт" / "Количество: N"
                const qtyMatch = text.match(/(\\d+)\\s*шт/i) || text.match(/Количество[:\\s]+(\\d+)/i);
                // Отсутствие в наличии
                const outOfStock = /нет в наличии|распродан|недоступен для заказа|товар закончился/i.test(text);

                results.push({
                  sku: sku,
                  name: name.trim().slice(0, 400),
                  url: 'https://www.ozon.ru' + a.getAttribute('href'),
                  priceText: priceMatch ? priceMatch[1] : null,
                  qty: qtyMatch ? parseInt(qtyMatch[1], 10) : 1,
                  inStock: !outOfStock
                });
              }
              return results;
            }
            """;

    private OzonCartParserService() {
    }

    public static List<OzonCartItemDto> parse(Page page) {
        List<OzonCartItemDto> items = new ArrayList<>();
        try {
            JSHandle handle = page.evaluateHandle(EXTRACT_JS);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) handle.asObject();
            if (raw == null) {
                return items;
            }
            for (Map<String, Object> row : raw) {
                String sku = str(row.get("sku"));
                if (sku == null) {
                    continue;
                }
                String name = strOrDefault(row.get("name"), "Товар " + sku);
                String url = str(row.get("url"));
                BigDecimal price = parsePrice(str(row.get("priceText")));
                int qty = row.get("qty") instanceof Number n ? Math.max(n.intValue(), 1) : 1;
                boolean inStock = !Boolean.FALSE.equals(row.get("inStock"));

                if (price == null || !inStock) {
                    items.add(OzonCartItemDto.unavailable(sku, name, url));
                } else {
                    items.add(OzonCartItemDto.of(sku, name, url, price, qty));
                }
            }
        } catch (RuntimeException e) {
            log.error("Ошибка парсинга DOM корзины: {}", e.getMessage());
        }
        log.info("Распознано позиций в корзине: {}", items.size());
        return items;
    }

    /** "1 234,56 Br" -> 1234.56 */
    static BigDecimal parsePrice(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replace("\u00a0", " ").replace(" ", "").replace("\u2009", "");
        // Если есть запятая — десятичный разделитель; иначе только целое
        if (cleaned.contains(",")) {
            cleaned = cleaned.replace(".", "").replace(",", ".");
        } else {
            cleaned = cleaned.replaceAll("\\.(?=\\d{3})", ""); // точки как разделители тысяч
        }
        cleaned = cleaned.replaceAll("[^0-9.\\-]", "");
        try {
            BigDecimal value = new BigDecimal(cleaned);
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException e) {
            log.debug("Не удалось распарсить цену из '{}'", raw);
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strOrDefault(Object o, String def) {
        String s = str(o);
        return s == null || s.isBlank() ? def : s;
    }
}
