package com.ap10er.tracker;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TrackerApplication {

    public static void main(String[] args) {
        // Загружаем секреты из src/main/resources/psw.env до старта контекста Spring.
        // Переменные окружения и системные свойства имеют приоритет над значениями из файла.
        Dotenv dotenv = Dotenv.configure()
                .filename("psw.env")
                .directory("src/main/resources")
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(entry ->
                System.setProperty(entry.getKey(), entry.getValue())
        );

        SpringApplication.run(TrackerApplication.class, args);
    }
}
