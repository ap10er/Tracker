package com.example.ozon_tracker.telegram;

import com.example.ozon_tracker.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Отправка сообщений в Telegram с соблюдением минимального интервала (защита от flood-limit).
 */
@Service
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final TelegramClient client;
    private final AppProperties props;
    private long lastSendEpochMs = 0L;

    public TelegramNotifier(TelegramClient client, AppProperties props) {
        this.client = client;
        this.props = props;
    }

    public boolean isConfigured() {
        String chatId = props.telegram().chatId();
        return chatId != null && !chatId.isBlank();
    }

    /** Отправляет одно сообщение; возвращает true при успехе. */
    public synchronized boolean send(String text) {
        if (!isConfigured()) {
            log.warn("telegram.chat-id не задан — сообщение НЕ отправлено:\n{}", text);
            return false;
        }
        waitForInterval();
        SendMessage message = new SendMessage();
        message.setChatId(props.telegram().chatId());
        message.setText(text);
        message.setParseMode(null); // обычный текст: в названиях товаров могут встречаться «странные» символы
        message.setDisableWebPagePreview(true);
        try {
            client.execute(message);
            lastSendEpochMs = System.currentTimeMillis();
            return true;
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение в Telegram: {}", e.getMessage());
            return false;
        }
    }

    private void waitForInterval() {
        int minSeconds = props.telegram().minIntervalSeconds();
        long earliestNext = lastSendEpochMs + minSeconds * 1000L;
        long wait = earliestNext - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
