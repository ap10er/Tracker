package com.example.ozon_tracker.scraping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.Cookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Вариант авторизации «Б»: пользователь один раз заходит в браузер, экспортирует cookies Ozon
 * в JSON-файл (расширение «Get cookies.txt LOCALLY» / EditThisCookie), а приложение подставляет их в браузер.
 * <p>
 * Поддерживаются оба популярных формата:
 * <ul>
 *   <li>Playwright/Netscape-JSON: {@code [{"name":..,"value":..,"domain":".ozon.ru","path":"/",...}]}</li>
 *   <li>cookie-txt (Netscape): строки вида {@code domain\tTRUE\tpath\texpiry\tname\tvalue}</li>
 * </ul>
 */
@Component
public class CookieReader {

    private static final Logger log = LoggerFactory.getLogger(CookieReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Загружает cookies из файла и добавляет их в контекст браузера. Возвращает количество применённых. */
    public int applyTo(BrowserContext context, Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Не найден файл cookies: " + file
                    + ". Экспортируйте cookies ozon.ru из браузера и положите по этому пути.");
        }
        List<Cookie> cookies;
        try {
            String raw = Files.readString(file).trim();
            cookies = raw.startsWith("[") || raw.startsWith("{") ? parseJson(raw) : parseNetscape(raw);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать cookies из " + file + ": " + e.getMessage(), e);
        }
        if (cookies.isEmpty()) {
            throw new IllegalStateException("В файле " + file + " нет ни одной cookie для домена ozon.ru");
        }
        for (Cookie c : cookies) {
            context.addCookie(c);
        }
        log.info("Применено cookies: {} (источник: {})", cookies.size(), file);
        return cookies.size();
    }

    private List<Cookie> parseJson(String raw) {
        List<Cookie> result = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(raw);
            JsonNode array = root.isArray() ? root : root.path("cookies");
            for (JsonNode n : array) {
                String name = text(n, "name");
                String value = text(n, "value");
                if (name == null || value == null) {
                    continue;
                }
                String domain = text(n, "domain");
                if (domain == null || !domain.contains("ozon")) {
                    // пропускаем cookies посторонних доменов (google, yandex и т.п.)
                    continue;
                }
                Cookie cookie = new Cookie(name, value);
                cookie.domain = domain;
                String path = text(n, "path");
                cookie.path = path == null ? "/" : path;
                JsonNode expires = n.get("expirationDate") != null ? n.get("expirationDate") : n.get("expires");
                if (expires != null && expires.isNumber()) {
                    cookie.expires = expires.doubleValue();
                }
                cookie.secure = n.path("secure").asBoolean(true);
                cookie.httpOnly = n.path("httpOnly").asBoolean(false);
                String sameSite = text(n, "sameSite");
                if (sameSite != null) {
                    try {
                        cookie.sameSite = com.microsoft.playwright.options.SameSiteAttribute
                                .valueOf(sameSite.toUpperCase().replace("-", "_"));
                    } catch (IllegalArgumentException ignored) {
                        // незнакомое значение — оставляем как есть
                    }
                }
                result.add(cookie);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Некорректный JSON в файле cookies: " + e.getMessage(), e);
        }
        return result;
    }

    private List<Cookie> parseNetscape(String raw) {
        List<Cookie> result = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] parts = l.split("\t");
            if (parts.length < 6) {
                continue;
            }
            String domain = parts[0];
            if (!domain.contains("ozon")) {
                continue;
            }
            Cookie cookie = new Cookie(parts[4], parts[5]);
            cookie.domain = domain;
            cookie.path = parts[2].isEmpty() ? "/" : parts[2];
            cookie.secure = "TRUE".equalsIgnoreCase(parts[3 - 1]); // колонка isSecure (2-я в Netscape-формате)
            try {
                long exp = Long.parseLong(parts[3]); // expiration timestamp (4-я колонка)
                if (exp > 0) {
                    cookie.expires = exp;
                }
            } catch (NumberFormatException ignored) {
                // session cookie
            }
            result.add(cookie);
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
