
package com.ap10er.tracker.config;

import com.ap10er.tracker.service.CartTelegramBot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

@Configuration
public class TelegramBotConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotConfig.class);

    @Bean
    public TelegramBotsLongPollingApplication telegramBotsApplication(TelegramProperties props,
                                                                      CartTelegramBot consumer) {
        TelegramBotsLongPollingApplication application = new TelegramBotsLongPollingApplication();
        try {
            application.registerBot(props.getBotToken(), consumer);
            log.info("Telegram-бот @{} зарегистрирован и запущен (long polling)", props.getBotUsername());
        } catch (Exception e) {
            log.error("Не удалось запустить Telegram-бота: {}", e.getMessage(), e);
        }
        return application;
    }
}
