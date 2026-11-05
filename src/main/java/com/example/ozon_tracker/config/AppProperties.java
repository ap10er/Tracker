package com.example.ozon_tracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Все настройки приложения ({@code ozon.*}, {@code telegram.*}) в одном типизированном bean'е.
 * См. {@code src/main/resources/application.yml}.
 */
@ConfigurationProperties(prefix = "")
public record AppProperties(Ozon ozon, Telegram telegram) {

    public record Ozon(
            @DefaultValue("https://www.ozon.ru/cart") String cartUrl,
            @DefaultValue("./data/cookies.json") String cookiesFile,
            @DefaultValue("./data/browser-profile") String browserProfileDir,
            @DefaultValue("true") boolean headless,
            @DefaultValue("30") int timeoutSeconds,
            @DefaultValue("false") boolean dumpHtml,
            @DefaultValue("./data/debug") String debugDir
    ) {
        public Path cookies() {
            return Paths.get(cookiesFile).toAbsolutePath().normalize();
        }

        public Path profileDir() {
            return Paths.get(browserProfileDir).toAbsolutePath().normalize();
        }

        public Path debug() {
            return Paths.get(debugDir).toAbsolutePath().normalize();
        }
    }

    public record Telegram(
            String token,
            String chatId,
            @DefaultValue("60") int minIntervalSeconds
    ) {
    }
}
