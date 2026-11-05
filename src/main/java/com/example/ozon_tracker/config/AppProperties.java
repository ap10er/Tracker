package com.example.ozon_tracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * Все настройки приложения ({@code ozon.*}, {@code telegram.*}) в одном типизированном bean'е.
 * См. {@code src/main/resources/application.yml}.
 */
@ConfigurationProperties(prefix = "")
public record AppProperties(Ozon ozon, Telegram telegram) {

    public record Ozon(
            @DefaultValue("https://www.ozon.ru/cart/?c=17") String cartUrl,
            @DefaultValue("./data/cookies.json") String cookiesFile,
            @DefaultValue("./data/browser-profile") String browserProfileDir,
            @DefaultValue("false") boolean headless,
            @DefaultValue("45") int timeoutSeconds,
            @DefaultValue("BYN") String currencyCode,
            @DefaultValue("Br") String currencySymbol,
            @DefaultValue("true") boolean dumpHtml,
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
            @DefaultValue("3") int minIntervalSeconds,
            @DefaultValue("09:00") String dailyReportTime
    ) {
        public LocalTime dailyReportAt() {
            try {
                return LocalTime.parse(dailyReportTime);
            } catch (DateTimeParseException e) {
                return LocalTime.of(9, 0);
            }
        }
    }
}
