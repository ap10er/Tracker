package com.ap10er.tracker.service;

import com.ap10er.tracker.config.WildberriesProperties;
import com.ap10er.tracker.model.CartItem;
import com.ap10er.tracker.repository.CartItemRepository;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WildberriesCartScanner {

    private static final Logger log = LoggerFactory.getLogger(WildberriesCartScanner.class);

    /** Артикул в ссылке https://www.wildberries.by/catalog/150449696/detail.aspx?size=... */
    private static final Pattern SKU_FROM_URL = Pattern.compile("/catalog/(\\d{5,})");

    /** Цена вида "34,44" / "1 234,56" (только число, без валюты) */
    private static final Pattern PRICE = Pattern.compile("(\\d{1,3}(?:[\\s\\u00A0]\\d{3})+[.,]\\d{1,2}|\\d+[.,]\\d{1,2}|\\d+)");

    private final WildberriesProperties props;
    private final CartItemRepository repository;

    public WildberriesCartScanner(WildberriesProperties props, CartItemRepository repository) {
        this.props = props;
        this.repository = repository;
    }

    public void scanAndSave() {
        log.info("Конфигурация Wildberries: cartUrl={}, storageStatePath={}, headless={}, timeout={}",
                props.getCartUrl(), props.getStorageStatePath(), props.isHeadless(), props.getPageTimeoutMs());

        if (props.getStorageStatePath() == null || props.getStorageStatePath().isBlank()) {
            throw new IllegalStateException("Не задан wildberries.storage-state-path в application.properties");
        }
        if (props.getCartUrl() == null || props.getCartUrl().isBlank()) {
            throw new IllegalStateException("Не задан wildberries.cart-url в application.properties");
        }

        Path storageState = Path.of(props.getStorageStatePath());
        if (!Files.exists(storageState)) {
            throw new IllegalStateException(
                    "Не найден файл storageState: " + storageState.toAbsolutePath()
                            + ". Сначала сохраните сессию Wildberries (см. README)."
            );
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(props.isHeadless())
            );

            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setStorageStatePath(storageState)
                    .setLocale("ru-RU")
                    .setViewportSize(1440, 900);

            try (BrowserContext context = browser.newContext(contextOptions)) {
                Page page = context.newPage();
                page.setDefaultTimeout(props.getPageTimeoutMs());

                log.info("Открываем корзину Wildberries: {}", props.getCartUrl());
                page.navigate(props.getCartUrl());
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.waitForTimeout(3000);

                log.info("Фактический URL: {}", page.url());
                log.info("Заголовок страницы: {}", page.title());
                String html = page.content();
                log.info("Длина HTML страницы: {} символов", html.length());
                try {
                    Path dump = Path.of("wildberries-cart-dump.html");
                    Files.writeString(dump, html);
                    log.info("HTML корзины сохранён в {}", dump.toAbsolutePath());
                } catch (Exception e) {
                    log.warn("Не удалось сохранить HTML корзины: {}", e.getMessage());
                }

                List<CartItem> items = parseCart(page);
                log.info("Найдено товаров в корзине Wildberries: {}", items.size());

                for (CartItem item : items) {
                    Optional<CartItem> existing = repository.findByArticle(item.getArticle());
                    if (existing.isPresent()) {
                        CartItem e = existing.get();
                        e.setName(item.getName());
                        e.setPrice(item.getPrice());
                        e.setScannedAt(Instant.now());
                        repository.save(e);
                        log.info("Обновлён: {} | {} | {}", e.getName(), e.getArticle(), e.getPrice());
                    } else {
                        item.setScannedAt(Instant.now());
                        repository.save(item);
                        log.info("Добавлен: {} | {} | {}", item.getName(), item.getArticle(), item.getPrice());
                    }
                }
                log.info("Сканирование завершено, всего в БД: {}", repository.count());
            } finally {
                browser.close();
            }
        }
    }

    private List<CartItem> parseCart(Page page) {
        List<CartItem> result = new ArrayList<>();

        // Реальная разметка корзины wildberries.by (по HTML-дампу):
        // div.listItemWrap--...[data-basket-item-id] > div.listItemGood--...
        // Точный селектор data-basket-item-id должен идти первым:
        // class*='listItemGood' находит лишние вложенные элементы,
        // из-за чего цена/артикул могут быть взяты не от того товара.
        String[] cardSelectors = {
                "[data-basket-item-id]",
                "[class*='listItemWrap']",
                "[class*='listItemGood']",
                ".basket__item",
                "[class*='basket__item']",
                "[class*='basket-item']",
                "[data-bag-item]"
        };

        List<ElementHandle> cards = List.of();
        for (String selector : cardSelectors) {
            List<ElementHandle> found = page.querySelectorAll(selector);
            log.debug("Селектор {}: найдено {}", selector, found.size());
            if (!found.isEmpty()) {
                cards = found;
                log.info("Используем селектор карточек: {} (найдено {})", selector, found.size());
                break;
            }
        }

        if (cards.isEmpty()) {
            log.warn("Карточки товаров не найдены. Возможно, корзина пуста или сессия не авторизована.");
            log.warn("Проверьте wildberries-cart-dump.html.");
            return result;
        }

        for (ElementHandle card : cards) {
            try {
                ElementHandle link = card.querySelector("a[href*='/catalog/']");
                String href = link == null ? null : link.getAttribute("href");
                String article = extractArticle(href, card);
                if (article == null) {
                    log.debug("Не удалось извлечь артикул из: {}", href);
                    continue;
                }

                String name = extractName(card, link);
                BigDecimal price = extractPrice(card);
                if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                    // Недоступные товары могут не иметь цены — сохраняем их,
                    // чтобы количество товаров совпадало с сайтом.
                    log.warn("Цена не распознана для товара '{}' (артикул {}) — сохраняем с ценой 0.01", name, article);
                    price = new BigDecimal("0.01");
                }
                result.add(new CartItem(name, article, price, null));
            } catch (Exception e) {
                log.warn("Ошибка разбора карточки товара: {}", e.getMessage());
            }
        }
        return result;
    }

    private String extractArticle(String href, ElementHandle card) {
        if (href != null) {
            Matcher m = SKU_FROM_URL.matcher(href);
            if (m.find()) {
                return m.group(1);
            }
        }
        for (String attr : new String[]{"data-basket-item-id", "data-product-id", "data-sku", "data-id"}) {
            String v = card.getAttribute(attr);
            if (v != null && v.matches("\\d{5,}")) {
                return v;
            }
        }
        return null;
    }

    private String extractName(ElementHandle card, ElementHandle link) {
        // В разметке WB название лежит в span.goodInfoName--xxxx
        String[] nameSelectors = {
                "[class*='goodInfoName']",
                "[class*='goodInfoTitle']",
                "a[href*='/catalog/']"
        };
        for (String sel : nameSelectors) {
            ElementHandle el = card.querySelector(sel);
            if (el != null) {
                String text = el.innerText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        if (link != null) {
            String alt = link.getAttribute("title");
            if (alt != null && !alt.isBlank()) return alt.trim();
        }
        String imgAlt = card.getAttribute("alt");
        return imgAlt != null && !imgAlt.isBlank() ? imgAlt.trim() : "(без названия)";
    }

    private BigDecimal extractPrice(ElementHandle card) {
        // Актуальная цена в WB лежит в:
        // <h4 class="... listItemPriceWithoutWallet--...">
        //     <span>34,44&nbsp;<span style=...>BYN</span></span>
        // </h4>
        // БерёмТолько первый текстовый узел, чтобы в значение не попала валюта.
        // Старая цена лежит в соседнем span с классом priceOld--... и не должна использоваться.
        ElementHandle priceEl = card.querySelector("[class*='listItemPriceWithoutWallet']");
        String text = firstTextNode(priceEl);
        if (priceEl != null) {
            log.debug("Цена из listItemPriceWithoutWallet (первый текстовый узел): '{}'", text);
            BigDecimal parsed = parsePrice(text);
            if (parsed != null) return parsed;
        }

        // Резервный вариант: любой элемент цены, но тоже только первый текстовый узел.
        String[] priceSelectors = {
                "[class*='listItemPrice']:not([class*='priceOld'])",
                "[class*='finalPrice']",
                "[class*='wrapPrice']"
        };
        for (String sel : priceSelectors) {
            ElementHandle el = card.querySelector(sel);
            if (el == null) continue;
            String candidate = firstTextNode(el);
            if (candidate.isBlank() || candidate.contains("Нет в наличии")) continue;
            log.debug("Кандидат цены [{}]: '{}'", sel, candidate);
            BigDecimal parsed = parsePrice(candidate);
            if (parsed != null) return parsed;
        }

        return null;
    }

    /**
     * Возвращает текст первого текстового узла элемента.
     * В разметке WB цена и валюта — разные дочерние узлы spans, поэтому
     * innerText вернёт "34,44 BYN", а нам нужен только "34,44".
     */
    private String firstTextNode(ElementHandle el) {
        if (el == null) return "";
        Object value = el.evaluate(
                "node => { " +
                        "  const target = node.querySelector('span') || node; " +
                        "  for (const n of target.childNodes) { " +
                        "    if (n.nodeType === Node.TEXT_NODE) return (n.textContent || '').trim(); " +
                        "  } " +
                        "  return ''; " +
                        "}"
        );
        return value == null ? "" : value.toString().trim();
    }

    private BigDecimal parsePrice(String raw) {
        if (raw == null) return null;
        String normalized = raw.replace("\u00A0", " ").trim();
        Matcher m = PRICE.matcher(normalized);
        if (!m.find()) return null;
        String found = m.group(1).replace(" ", "").replace("\u00A0", "");
        // Разделяем целую и дробную части по последней точке/запятой
        int sep = Math.max(found.lastIndexOf('.'), found.lastIndexOf(','));
        String decimal;
        if (sep < 0) {
            decimal = found + ".00";
        } else {
            decimal = found.substring(0, sep) + "." + found.substring(sep + 1);
        }
        try {
            return new BigDecimal(decimal).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
