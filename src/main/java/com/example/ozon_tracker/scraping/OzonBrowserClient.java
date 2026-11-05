package com.example.ozon_tracker.scraping;

import com.example.ozon_tracker.config.AppProperties;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Управляет Chromium через Playwright: persistent-профиль (чтобы после первого ручного прохождения
 * капчи сессия сохранялась) + подстановка экспортированных cookies (Вариант Б).
 * <p>
 * Режим по умолчанию — visible ({@code ozon.headless=false}), как запрошено пользователем.
 */
@Component
public class OzonBrowserClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OzonBrowserClient.class);

    /** Признаки anti-bot-страницы («Ой, кажется у вас проблемы…» / слайдер). */
    private static final List<String> BLOCK_MARKERS = List.of(
            "text=Ой",
            "text=похоже, у вас проблемы",
            "text=Продолжить быть человеком",
            "text=Подтвердите, что действия",
            "[data-bash-id]",
            "css=.slider"
    );

    private final AppProperties props;
    private final CookieReader cookieReader;
    private Playwright playwright;
    private com.microsoft.playwright.BrowserContext context;

    public OzonBrowserClient(AppProperties props, CookieReader cookieReader) {
        this.props = props;
        this.cookieReader = cookieReader;
    }

    /**
     * Открывает страницу корзины и возвращает снимок. Никаких исключений за anti-bot не бросает:
     * всё фиксируется в флагах {@link CartSnapshot}, решение о остановке принимает вызывающий.
     */
    public CartSnapshot openCart() {
        AppProperties.Ozon ozon = props.ozon();
        Path profileDir = ozon.profileDir();
        try {
            Files.createDirectories(profileDir);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать каталог профиля браузера: " + profileDir, e);
        }

        playwright = Playwright.create();
        context = playwright.chromium().launchPersistentContext(
                profileDir,
                new com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(ozon.headless())
                        .setSlowMo(ozon.headless() ? 0 : 50)
                        .setViewportSize(1366, 900)
                        .setLocale("ru-BY")
                        .setTimezoneId("Europe/Minsk")
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
        );
        context.setDefaultTimeout(ozon.timeoutSeconds() * 1000L);

        // Вариант Б: cookies из файла экспорта. Если профиль уже «прогрет» — это страховка.
        try {
            cookieReader.applyTo(context, ozon.cookies());
        } catch (RuntimeException e) {
            log.warn("Cookies не применены (используем сохранённый профиль браузера): {}", e.getMessage());
        }

        Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
        String url = ozon.cartUrl();
        log.info("Открываем корзину: {}", url);
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (TimeoutError e) {
            log.warn("Страница не успела полностью загрузиться (networkidle), продолжаем с тем что есть");
        }

        // Даем SPA Ozon отрендерить список
        try {
            page.waitForTimeout(2500);
        } catch (RuntimeException ignored) {
        }

        String finalUrl = page.url();

        // 1) Протухшая сессия: Ozon редиректит на вход или показывает пустую неавторизованную корзину
        if (finalUrl.contains("/login") || finalUrl.contains("/manual_login")) {
            return CartSnapshot.loginRequired(finalUrl);
        }

        // 2) Anti-bot: страница-заглушка «Ой!»
        if (isBlocked(page)) {
            dumpHtml(page, "blocked");
            return CartSnapshot.blocked(finalUrl);
        }

        // 3) Ждём появления хотя бы одного товара или маркера пустой корзины
        boolean cartRendered = waitForCartContent(page);
        if (!cartRendered && isBlocked(page)) {
            dumpHtml(page, "blocked");
            return CartSnapshot.blocked(finalUrl);
        }

        String htmlDumpPath = dumpHtml(page, "cart");
        List<com.example.ozon_tracker.dto.OzonCartItemDto> items = OzonCartParserService.parse(page);

        // Повторная проверка логина: иногда редирект происходит уже после рендера
        if (items.isEmpty() && (page.url().contains("/login"))) {
            return CartSnapshot.loginRequired(page.url());
        }

        return new CartSnapshot(items, false, false, page.url(), htmlDumpPath);
    }

    private boolean isBlocked(Page page) {
        for (String marker : BLOCK_MARKERS) {
            try {
                Locator l = page.locator(marker).first();
                if (l.count() > 0 && l.isVisible()) {
                    log.debug("Сработал anti-bot маркер: {}", marker);
                    return true;
                }
            } catch (RuntimeException ignored) {
                // некорректный селектор на этой версии страницы — пропускаем
            }
        }
        return false;
    }

    private boolean waitForCartContent(Page page) {
        try {
            page.waitForFunction(
                    "() => document.querySelectorAll('[data-widget=\"cart\"], [data-widget=\"cartWebListItems\"]')"
                            + ".length > 0 || document.body.innerText.includes('Корзина пуста')");
            return true;
        } catch (TimeoutError e) {
            log.warn("Контент корзины не появился за {} сек", props.ozon().timeoutSeconds());
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String dumpHtml(Page page, String tag) {
        if (!props.ozon().dumpHtml()) {
            return null;
        }
        try {
            Path dir = props.ozon().debug();
            Files.createDirectories(dir);
            Path file = dir.resolve(tag + "-" + LocalDate.now() + ".html");
            Files.writeString(file, page.content());
            log.debug("HTML сохранён: {}", file);
            return file.toString();
        } catch (IOException e) {
            log.warn("Не удалось сохранить HTML: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void close() {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            if (playwright != null) {
                playwright.close();
            }
        }
    }
}
