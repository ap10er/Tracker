package com.ap10er.tracker.service;

import com.ap10er.tracker.config.TelegramProperties;
import com.ap10er.tracker.model.CartItem;
import com.ap10er.tracker.repository.CartItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class CartTelegramBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(CartTelegramBot.class);
    private static final int MESSAGE_LIMIT = 4096;

    private final TelegramClient telegramClient;
    private final CartItemRepository repository;
    private volatile String chatId;

    public CartTelegramBot(TelegramProperties props, CartItemRepository repository) {
        this.telegramClient = new OkHttpTelegramClient(props.getBotToken());
        this.repository = repository;
        String configured = props.getChatId();
        this.chatId = configured == null || configured.isBlank() ? null : configured.trim();
    }

    @Override
    public void consume(Update update) {
        if (update == null || !update.hasMessage()) {
            return;
        }
        if (!update.getMessage().hasText() && !update.getMessage().hasPhoto() && !update.getMessage().hasDocument()) {
            return;
        }
        Long incoming = update.getMessage().getChatId();
        if (incoming == null) {
            return;
        }
        String candidate = String.valueOf(incoming);
        if (!candidate.equals(chatId)) {
            chatId = candidate;
            log.info("Запомнен chatId получателя отчёта: {}", chatId);
        }
    }

    public void sendCartReport() {
        String target = chatId;
        if (target == null || target.isBlank()) {
            log.warn("Неизвестен chatId получателя. Напишите боту @{} любое сообщение "
                    + "или задайте telegram.chat-id в application.properties", "Ozon_trackbot");
            return;
        }
        for (String message : buildCartMessages()) {
            try {
                telegramClient.execute(SendMessage.builder()
                        .chatId(target)
                        .text(message)
                        .parseMode("HTML")
                        .disableWebPagePreview(true)
                        .build());
            } catch (TelegramApiException e) {
                log.error("Не удалось отправить сообщение получателю {}: {}", target, e.getMessage());
                return;
            }
        }
        log.info("Отчёт отправлен в Telegram (chatId={})", target);
    }

    /** Формирует один или несколько HTML-блоков с товарами. */
    private List<String> buildCartMessages() {
        List<CartItem> items = new ArrayList<>(repository.findAll());
        items.sort(Comparator.comparing(CartTelegramBot::isAtMinimum).reversed()
                .thenComparing(item -> item.getName() == null ? "" : item.getName()));

        if (items.isEmpty()) {
            return List.of("📭 <b>Корзина пуста</b>\n\nТоваров в базе нет.");
        }

        List<String> result = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        chunk.append("🛒 <b>Товары в корзине</b> — ").append(items.size()).append(" шт.\n\n");

        for (int i = 0; i < items.size(); i++) {
            String block = renderItem(items.get(i), i + 1);
            if (chunk.length() + block.length() > MESSAGE_LIMIT - 100) {
                result.add(chunk.toString());
                chunk = new StringBuilder("🛒 <b>Корзина (продолжение)</b>\n\n");
            }
            chunk.append(block);
        }
        if (chunk.length() > 0) {
            result.add(chunk.toString());
        }
        return result;
    }

    private static String renderItem(CartItem item, int index) {
        BigDecimal current = item.getPrice() == null ? BigDecimal.ZERO : item.getPrice();
        BigDecimal min = item.getMinPrice() == null ? current : item.getMinPrice();
        boolean atMin = isAtMinimum(item);

        String status = atMin ? "🔥 <b>МИНИМУМ</b>" : "📉";
        BigDecimal diff = current.subtract(min).setScale(2, RoundingMode.HALF_UP);

        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(index).append(". ").append(escape(item.getName())).append("</b>\n")
                .append("🔢 Артикул: <code>").append(escape(item.getArticle())).append("</code>\n")
                .append("💰 Текущая цена: <b>").append(current.toPlainString()).append("</b>\n")
                .append("⭐ Минимальная цена: <b>").append(min.toPlainString()).append("</b>  ").append(status).append('\n');
        if (!atMin) {
            sb.append("   ➕ Дороже минимума на ").append(diff.toPlainString()).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    private static boolean isAtMinimum(CartItem item) {
        if (item.getPrice() == null || item.getMinPrice() == null) {
            return false;
        }
        return item.getPrice().compareTo(item.getMinPrice()) <= 0;
    }

    private static String escape(String value) {
        if (value == null) {
            return "(без названия)";
        }
        return value.replace("&", "&")
                .replace("<", "<")
                .replace(">", ">");
    }
}
