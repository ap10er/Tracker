package com.example.ozon_tracker.service;

import com.example.ozon_tracker.config.AppProperties;
import com.example.ozon_tracker.dto.ScanResult;
import com.example.ozon_tracker.scraping.CartSnapshot;
import com.example.ozon_tracker.scraping.CookieReader;
import com.example.ozon_tracker.scraping.OzonBrowserClient;
import com.example.ozon_tracker.telegram.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Оркестратор жизненного цикла: запуск -> скан корзины -> запись в БД и сравнение с историческим
 * минимумом -> отчёт в Telegram -> завершение процесса (приложение разовое: запускается вручную
 * из IntelliJ или планировщиком Windows, ежедневный отчёт формируется при каждом запуске).
 * <p>
 * Требование пользователя: cookies истекли -> ОСТАНОВКА приложения (после алерта в Telegram).
 */
@Component
public class ScanRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanRunner.class);

    private final AppProperties props;
    private final CookieReader cookieReader;
    private final PriceAnalysisService priceAnalysisService;
    private final ReportService reportService;

    public ScanRunner(AppProperties props,
                      CookieReader cookieReader,
                      PriceAnalysisService priceAnalysisService,
                      ReportService reportService) {
        this.props = props;
        this.cookieReader = cookieReader;
        this.priceAnalysisService = priceAnalysisService;
        this.reportService = reportService;
    }

    @Override
    public void run(String... args) {
        log.info("=== Ozon Tracker: старт (регион: Беларусь, валюта: {}) ===", props.ozon().currencyCode());

        CartSnapshot snapshot;
        try (OzonBrowserClient client = new OzonBrowserClient(props, cookieReader)) {
            snapshot = client.openCart();
        } catch (RuntimeException e) {
            log.error("Сбой браузера/скрапинга: {}", e.getMessage(), e);
            reportService.sendFailureAlert("Техническая ошибка: " + e.getMessage());
            System.exit(3);
            return;
        }

        if (snapshot.loginRedirect()) {
            log.error("Cookies истекли: Ozon редирекнул на страницу входа ({})", snapshot.finalUrl());
            reportService.sendFailureAlert("Cookies истекли — нужен повторный экспорт cookies из браузера "
                    + "(Вариант Б) и перезапуск приложения.");
            System.exit(2); // остановка по требованию пользователя
            return;
        }

        if (snapshot.blocked()) {
            log.error("Страница закрыта anti-bot защитой ({})", snapshot.finalUrl());
            reportService.sendFailureAlert("Ozon показал проверку «я не робот». В окне браузера (режим visible) "
                    + "пройдите капчу вручную и запустите приложение повторно.");
            System.exit(2);
            return;
        }

        ScanResult result = priceAnalysisService.processSnapshot(snapshot);
        log.info("Скан завершён: позиций={}, новых={}, на минимуме={}, закончились={}",
                result.scannedItems(), result.newItems(), result.countAtMinimum(),
                result.outOfStockItems().size());

        reportService.sendDailyReport(result);

        // Отдельные срочные сообщения: товар в корзине закончился
        for (ScanResult.OutOfStockItem o : result.outOfStockItems()) {
            reportService.sendOutOfStockAlert(o);
        }

        log.info("=== Готово, приложение завершается ===");
        System.exit(0);
    }
}
