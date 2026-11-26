package com.ap10er.tracker.runner;

import com.ap10er.tracker.service.CartTelegramBot;
import com.ap10er.tracker.service.WildberriesCartScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wildberries.headless", havingValue = "true", matchIfMissing = true)
public class CartScanRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CartScanRunner.class);

    private final WildberriesCartScanner scanner;
    private final CartTelegramBot telegramBot;
    private final ConfigurableApplicationContext context;

    public CartScanRunner(WildberriesCartScanner scanner,
                          CartTelegramBot telegramBot,
                          ConfigurableApplicationContext context) {
        this.scanner = scanner;
        this.telegramBot = telegramBot;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        log.info("=== Старт сканирования корзины Wildberries ===");
        try {
            scanner.scanAndSave();
            telegramBot.sendCartReport();
        } catch (Exception e) {
            log.error("Сканирование или отправка отчёта завершились ошибкой: {}", e.getMessage(), e);
        } finally {
            log.info("Работа завершена, закрываем приложение");
            context.close();
            // Long polling Telegram-бота держит не-daemon поток, поэтому одного close() недостаточно.
            System.exit(0);
        }
    }
}
