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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WildberriesCartScanner {

    private static final Logger log = LoggerFactory.getLogger(WildberriesCartScanner.class);

    private static final Pattern SKU_FROM_URL = Pattern.compile("/catalog/(\\d{5,})");

    private static final Pattern PRICE = Pattern.compile("(\\d{1,3}(?:[\\s\\u00A0]\\d{3})+[.,]\\d{1,2}|\\d+[.,]\\d{1,2}|\\d+)");

    private final WildberriesProperties props;
    private final CartItemRepository repository;

    public WildberriesCartScanner(WildberriesProperties props, CartItemRepository repository) {
        this.props = props;
        this.repository = repository;
    }

    public void scanAndSave() throws java.io.IOException {
        log.info("Конфигурация Wildberries: cartUrl={}, storageStatePath={}, headless={}",
                props.getCartUrl(), props.getStorageStatePath(), props.isHeadless());

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

                log.info("Открываем корзину Wildberries: {}", props.getCartUrl());
                page.navigate(props.getCartUrl());
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                // Wildberries — SPA: карточки корзины рендерятся JS после DOMContentLoaded.
                // Небольшая фиксированная пауза без проверок антибота и повторов.
                page.waitForTimeout(8000);
                String actualUrl = page.url();
                String title = page.title();
                log.info("Фактический URL: {}", actualUrl);
                log.info("Заголовок страницы: {}", title);

                if (!actualUrl.contains("/lk/basket") && !actualUrl.contains("basket")) {
                    log.error("Страница корзины не открылась: фактический URL={} (ожидалась корзина). "
                            + "БД не изменена. Проверьте файл сессии {}", actualUrl, storageState.toAbsolutePath());
                    throw new IllegalStateException("Wildberries не открыл корзину. БД не изменена.");
                }

                String html = page.content();
                try {
                    Path dump = Path.of("wildberries-cart-dump.html");
                    Files.writeString(dump, html);
                    log.info("HTML корзины сохранён в {}", dump.toAbsolutePath());
                } catch (Exception e) {
                    log.warn("Не удалось сохранить HTML корзины: {}", e.getMessage());
                }

                if (html.contains("captcha-support@rwb.ru")
                        || html.contains("support-countdown-text")
                        || html.contains("/__wbaas/challenges/antibot/")) {
                    throw new IllegalStateException("Wildberries вернул страницу проверки вместо корзины. "
                            + "БД и сохранённая сессия не изменены; отчёт не отправляется.");
                }

                List<CartItem> items = parseCart(page);
                log.info("Найдено товаров в корзине Wildberries: {}", items.size());

                // Защита: если ни одной карточки не найдено, считаем это сбоем сканирования
                // (сессия/рендер/разметка) и НЕ трогаем БД — иначе она опустеет.
                if (items.isEmpty()) {
                    log.error("Карточки товаров не найдены — сканирование считается неудачным, "
                            + "БД не изменена. Проверьте сессию и wildberries-cart-dump.html.");
                    throw new IllegalStateException("Не удалось прочитать товары корзины. БД не изменена.");
                }

                Path stateTarget = storageState.toAbsolutePath();
                Path stateTemporary = Files.createTempFile(stateTarget.getParent(), "wb-session-", ".tmp");
                try {
                    context.storageState(new BrowserContext.StorageStateOptions()
                            .setIndexedDB(true).setPath(stateTemporary));
                    try {
                        Files.move(stateTemporary, stateTarget,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(stateTemporary, stateTarget,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    log.info("Сессия Wildberries обновлена для следующего запуска");
                } finally {
                    Files.deleteIfExists(stateTemporary);
                }

                Set<String> scannedArticles = new HashSet<>();
                for (CartItem item : items) {
                    scannedArticles.add(item.getArticle());
                    Optional<CartItem> existing = repository.findById(item.getArticle());
                    if (existing.isPresent()) {
                        CartItem e = existing.get();
                        BigDecimal currentPrice = item.getPrice();
                        BigDecimal historicalMin = e.getMinPrice() != null ? e.getMinPrice() : currentPrice;
                        BigDecimal newMin = currentPrice.min(historicalMin);

                        e.setName(item.getName());
                        e.setPrice(currentPrice);
                        e.setMinPrice(newMin);
                        repository.save(e);
                        log.info("Обновлён: {} | {} | текущая={} | минимум={} | новый минимум={}",
                                e.getName(), e.getArticle(), currentPrice, historicalMin, newMin);
                    } else {
                        item.setMinPrice(item.getPrice());
                        repository.save(item);
                        log.info("Добавлен: {} | {} | текущая={} | минимум={}",
                                item.getName(), item.getArticle(), item.getPrice(), item.getMinPrice());
                    }
                }

                List<CartItem> stale = repository.findAll().stream()
                        .filter(dbItem -> !scannedArticles.contains(dbItem.getArticle()))
                        .toList();
                if (!stale.isEmpty()) {
                    repository.deleteAll(stale);
                    for (CartItem removed : stale) {
                        log.info("Удалён (нет в корзине): {} | {}", removed.getName(), removed.getArticle());
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

        String[] cardSelectors = {
                "[data-basket-item-id]",
                "[class*='listItemWrap']",
                "[class*='listItemGood']",
                ".basket__item",
                "[class*='basket__item']",
                "[class*='basket-item']",
                "[data-bag-item]",
                "[class*='basketItem']",
                "[class*='cartItem']",
                "[class*='productCard']",
                "[class*='goodsItem']",
                "[class*='itemWrap']",
                "a[href*='/catalog/']"
        };

        List<ElementHandle> cards = List.of();
        for (String selector : cardSelectors) {
            List<ElementHandle> found = page.querySelectorAll(selector);
            log.info("Селектор {}: найдено {}", selector, found.size());
            if (!found.isEmpty()) {
                cards = found;
                log.info("Используем селектор карточек: {} (найдено {})", selector, found.size());
                break;
            }
        }

        if (cards.isEmpty()) {
            log.warn("Карточки товаров не найдены.");
            return result;
        }

        for (ElementHandle card : cards) {
            try {
                ElementHandle link = card.querySelector("a[href*='/catalog/']");
                String href = link == null ? null : link.getAttribute("href");
                String article = extractArticle(href, card);
                if (article == null) {
                    continue;
                }

                String name = extractName(card, link);
                BigDecimal price = extractPrice(card);
                if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                    price = new BigDecimal("0.01");
                }
                result.add(new CartItem(name, article, price));
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
        ElementHandle priceEl = card.querySelector("[class*='listItemPriceWithoutWallet']");
        String text = firstTextNode(priceEl);
        BigDecimal parsed = parsePrice(text);
        if (parsed != null) return parsed;

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
            parsed = parsePrice(candidate);
            if (parsed != null) return parsed;
        }
        return null;
    }

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
