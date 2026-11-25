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

/** Telegram-бот: по команде /go показывает все товары из cart_items. */
@Component
public class CartTelegramBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(CartTelegramBot.class);

    /** Лимит Telegram на длину текста одного сообщения. */
    private static final int MESSAGE_LIMIT = 4096;

    private final TelegramClient telegramClient;
    private final CartItemRepository repository;

    public CartTelegramBot(TelegramProperties props, CartItemRepository repository) {
        this.telegramClient = new OkHttpTelegramClient(props.getBotToken());
        this.repository = repository;
    }

    @Override
    public void consume(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }
        String text = update.getMessage().getText().trim();
        if (!text.equals("/go") && !text.startsWith("/go@")) {
            return;
        }
        Long chatId = update.getMessage().getChatId();
        log.info("Команда /go от chatId={}", chatId);

        for (String message : buildCartMessages()) {
            try {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(message)
                        .parseMode("HTML")
                        .disableWebPagePreview(true)
                        .build());
            } catch (TelegramApiException e) {
                log.error("Не удалось отправить сообщение в чат {}: {}", chatId, e.getMessage());
                return;
            }
        }
    }

    /** Формирует один или несколько HTML-блоков с товарами. */
    private List<String> buildCartMessages() {
        List<CartItem> items = new ArrayList<>(repository.findAll());
        // Сначала товары на минимальной цене, затем по названию.
        items.sort(Comparator.comparing(CartTelegramBot::isAtMinimum).reversed()
                .thenComparing(item -> item.getName() == null ? "" : item.getName()));

        if (items.isEmpty()) {
            return List.of("📭 <b>Корзина пуста</b>\n\nТоваров пока нет. Запустите сканирование Wildberries.");
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

    /** Экранирует служебные символы HTML в пользовательском тексте. */
    private static String escape(String value) {
        if (value == null) {
            return "(без названия)";
        }
        // Двойные кавычки в тексте сообщения безопасны для HTML-разметки Telegram.
        return value.replace("&amp;", "&amp;")
                .replace("<lt;", "<lt;")
                .replace(">gt;", ">gt;");
    }
}
