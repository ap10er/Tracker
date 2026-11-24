package com.ap10er.tracker.runner;

import com.ap10er.tracker.service.WildberriesCartScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wildberries.headless", havingValue = "true", matchIfMissing = true)
public class CartScanRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CartScanRunner.class);

    private final WildberriesCartScanner scanner;

    public CartScanRunner(WildberriesCartScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public void run(String... args) {
        log.info("=== Запуск сканирования корзины Wildberries ===");
        try {
            scanner.scanAndSave();
        } catch (Exception e) {
            log.error("Сканирование завершилось ошибкой: {}", e.getMessage(), e);
        } finally {
            log.info("=== Завершение работы ===");
        }
    }
}
