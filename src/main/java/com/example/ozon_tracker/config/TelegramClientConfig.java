package com.example.ozon_tracker.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Telegram-клиент (REST-обёртка над Bot API). Логика telegrambots-longpolling здесь не нужна:
 * мы только отправляем сообщения, входящие апдейты обрабатывает отдельный @CommandProcessor (опционально).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class TelegramClientConfig {

    @Bean
    public TelegramClient telegramClient(AppProperties props) {
        String token = props.telegram().token();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Не задан telegram.token — создайте бота у @BotFather и пропишите токен в application.yml");
        }
        return new OkHttpTelegramClient(token);
    }
}
