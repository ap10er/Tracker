package com.ap10er.tracker.runner;

import com.ap10er.tracker.config.WildberriesProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Режим получения/обновления сессии Wildberries.
 * Активен только когда wildberries.headless=false.
 *
 * Если файл сессии уже есть — загружаем его, чтобы не терять cookies между запусками.
 * После авторизации перезаписываем файл обновлённой сессией.
 */
@Component
@ConditionalOnProperty(name = "wildberries.headless", havingValue = "false")
public class SessionCaptureRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionCaptureRunner.class);

    private final WildberriesProperties props;

    public SessionCaptureRunner(WildberriesProperties props) {
        this.props = props;
    }

    @Override
    public void run(String... args) throws Exception {
        Path output = Path.of(props.getStorageStatePath());
        log.info("=== Режим получения сессии Wildberries ===");
        log.info("Откройте браузер и авторизуйтесь на {}", props.getCartUrl());
        log.info("Сессия будет сохранена в: {}", output.toAbsolutePath());

        boolean hasExisting = Files.exists(output);
        if (hasExisting) {
            log.info("Загружаем существующую сессию из {}", output.toAbsolutePath());
        } else {
            log.info("Существующей сессии нет — начинаем с чистого браузера.");
        }
        log.info("Ожидание: до 120 секунд (или нажмите Enter в терминале для сохранения).");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(false)
            );
            try {
                Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                        .setLocale("ru-RU")
                        .setViewportSize(1440, 900);
                if (hasExisting) {
                    contextOptions.setStorageStatePath(output);
                }

                BrowserContext context = browser.newContext(contextOptions);
                try {
                    Page page = context.newPage();
                    page.navigate(props.getCartUrl());
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
                    log.info("Текущий URL после загрузки: {}", page.url());

                    Thread waiter = new Thread(() -> {
                        try {
                            System.in.read();
                        } catch (Exception ignored) {
                            // в IDE stdin может быть недоступен — просто ждём таймаут
                        }
                    });
                    waiter.setDaemon(true);
                    waiter.start();
                    waiter.join(120_000);

                    log.info("Текущий URL перед сохранением: {}", page.url());
                    context.storageState(new BrowserContext.StorageStateOptions().setPath(output));
                    log.info("Сессия сохранена: {}", output.toAbsolutePath());
                } finally {
                    context.close();
                }
            } finally {
                browser.close();
            }
        }
        log.info("=== Завершение режима получения сессии ===");
    }
}
