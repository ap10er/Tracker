package com.example.ozon_tracker.telegram;

import com.example.ozon_tracker.config.AppProperties;
import com.example.ozon_tracker.dto.PriceComparison;
import com.example.ozon_tracker.dto.ScanResult;
import com.example.ozon_tracker.model.enums.ItemStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Формирование и отправка отчётов. По требованию пользователя в ежедневный отчёт попадают
 * ТОЛЬКО изменения: новые позиции, изменение цены, минимум, выход из наличия, исчезновение из корзины.
 */
@Service
public class ReportService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TelegramNotifier notifier;
    private final AppProperties props;

    public ReportService(TelegramNotifier notifier, AppProperties props) {
        this.notifier = notifier;
        this.props = props;
    }

    /** Алерт об ошибке скана (протухшие cookies / anti-bot). */
    public void sendFailureAlert(String reason) {
        notifier.send("""
                ⛔ OZON TRACKER: скан не выполнен

                Причина: %s

                Приложение остановлено. Проверьте данные входа (cookies/капчу) и запустите заново.
                """.formatted(reason));
    }

    /** Ежедневный отчёт-дайджест: только изменения с момента прошлого отправки отчёта. */
    public void sendDailyReport(ScanResult result) {
        String cur = props.ozon().currencySymbol();
        StringBuilder sb = new StringBuilder();
        sb.append("📊 OZON — ежедневный отчёт (изменения)\n");
        sb.append("Дата замера: ").append(result.startedAt().format(TIME)).append("\n\n");

        List<PriceComparison> changes = result.comparisons().stream()
                .filter(c -> c.getStatus() != ItemStatus.UNCHANGED)
                .toList();

        if (changes.isEmpty() && result.removedItems().isEmpty() && result.outOfStockItems().isEmpty()) {
            sb.append("За сутки изменений нет. Корзина стабильна ✅\n");
            sb.append("Позиций в корзине: ").append(result.scannedItems());
            notifier.send(sb.toString());
            return;
        }

        // 1) Товары, достигшие исторического минимума (строгое равенство)
        appendSection(sb, "🟢 НА ИСТОРИЧЕСКОМ МИНИМУМЕ:", changes.stream()
                .filter(PriceComparison::isAtMinimum).toList(), cur);

        // 2) Подешевели
        appendSection(sb, "🔵 ПОДЕШЕВЕЛО:", changes.stream()
                .filter(c -> c.getStatus() == ItemStatus.DECREASED).toList(), cur);

        // 3) Подорожало
        appendSection(sb, "🔴 ПОДОРОЖАЛО:", changes.stream()
                .filter(c -> c.getStatus() == ItemStatus.INCREASED).toList(), cur);

        // 4) Новые позиции
        appendSection(sb, "🆕 НОВЫЕ В КОРЗИНЕ:", changes.stream()
                .filter(c -> c.getStatus() == ItemStatus.NEW).toList(), cur);

        // 5) Закончились товары
        if (!result.outOfStockItems().isEmpty()) {
            sb.append("\n❌ ТОВАРЫ ЗАКОНЧИЛИСЬ:\n");
            result.outOfStockItems().forEach(o ->
                    sb.append("  • ").append(o.name()).append(" (SKU ").append(o.sku()).append(")\n"));
        }

        // 6) Исчезли из корзины
        if (!result.removedItems().isEmpty()) {
            sb.append("\n➖ УБРАНЫ ИЗ КОРЗИНЫ:\n");
            result.removedItems().forEach(r ->
                    sb.append("  • ").append(r.name()).append(" (SKU ").append(r.sku()).append(")\n"));
        }

        sb.append("\nИтого позиций в корзине: ").append(result.scannedItems());
        if (result.cartTotal() != null && result.cartTotal().signum() > 0) {
            sb.append(", стоимость доступных: ").append(format(result.cartTotal())).append(" ").append(cur);
        }
        notifier.send(sb.toString());
    }

    /** Срочное сообщение: товар в корзине закончился. */
    public void sendOutOfStockAlert(ScanResult.OutOfStockItem item) {
        notifier.send("""
                ❌ Товар в корзине закончился!

                %s
                SKU: %s
                Последняя известная цена: %s

                Ссылка: %s
                """.formatted(item.name(), item.sku(), format(item.lastKnownPrice()), item.url()));
    }

    private void appendSection(StringBuilder sb, String header, List<PriceComparison> items, String cur) {
        if (items.isEmpty()) {
            return;
        }
        sb.append('\n').append(header).append('\n');
        for (PriceComparison c : items) {
            sb.append("  • ").append(truncate(c.name(), 70)).append("\n");
            sb.append("      сейчас: ").append(format(c.currentPrice())).append(" ").append(cur);
            if (c.previousPrice() != null) {
                sb.append("  (было: ").append(format(c.previousPrice())).append(")");
            }
            if (c.historicalMinPrice() != null) {
                sb.append("  [мин: ").append(format(c.historicalMinPrice())).append("]");
            }
            if (c.diffToMinPercent() != null && c.diffToMinPercent().signum() > 0) {
                sb.append("  +").append(c.diffToMinPercent()).append("% к минимуму");
            }
            if (c.quantity() != null && c.quantity() > 1) {
                sb.append("  ×").append(c.quantity()).append(" шт");
            }
            sb.append('\n');
        }
    }

    private static String format(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
