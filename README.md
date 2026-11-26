# Wildberries Cart Tracker

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Flyway](https://img.shields.io/badge/Flyway-CC0200?logo=flyway&logoColor=white)](https://flywaydb.org/)
[![Playwright](https://img.shields.io/badge/Playwright-1.55-45ba4b?logo=playwright&logoColor=white)](https://playwright.dev/)
[![Telegram](https://img.shields.io/badge/Telegram-Bot%20API-26A5E4?logo=telegram&logoColor=white)](https://core.telegram.org/bots/api)

Консольное Java + Spring Boot приложение, которое сканирует корзину Wildberries Беларуси (`https://www.wildberries.by/lk/basket`) через Playwright, сохраняет товары в PostgreSQL, отслеживает **исторический минимум цены** по каждому артикулу и отправляет отчёт в **Telegram**.

## Что делает

- Открывает корзину Wildberries в headless Chromium.
- Использует сохранённую сессию (`wildberries-storage-state.json`) — авторизация не требуется при каждом запуске; после успешного сканирования сессия автоматически обновляется.
- Собирает с товаров в корзине:
  - название,
  - артикул (из ссылки `/catalog/<id>` или из атрибутов `data-basket-item-id`, `data-product-id`, `data-sku`, `data-id`),
  - текущую цену.
- Записывает/обновляет данные в таблицу `cart_items` в PostgreSQL:
  - новые товары добавляются,
  - у существующих обновляется текущая цена и пересчитывается `min_price` (минимум за всё время наблюдений),
  - товары, которых больше нет в корзине, удаляются из БД.
- Отправляет в Telegram HTML-отчёт со списком товаров: текущая цена, минимальная цена, отметка `🔥 МИНИМУМ` для товаров по минимальной цене и разница с минимумом для остальных.
- Завершается автоматически после сканирования и отправки отчёта.

## Требования

- Java 25+
- Maven (например, `./mvnw`)
- PostgreSQL
- Установленные браузеры Playwright (Chromium)
- Telegram-бот (токен от [@BotFather](https://t.me/BotFather))

## Установка браузера Playwright

После первой сборки выполните:

```bash
./mvnw exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

(или `mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`)

## Секреты: файл `psw.env`

Пароль БД и токен Telegram-бота не хранятся в репозитории. Они читаются из файла `psw.env`:

- [`TrackerApplication`](src/main/java/com/ap10er/tracker/TrackerApplication.java) загружает `psw.env` из `src/main/resources` до старта Spring-контекста;
- [`EnvFileEnvironmentPostProcessor`](src/main/java/com/ap10er/tracker/config/EnvFileEnvironmentPostProcessor.java) (регистрируется через [`spring.factories`](src/main/resources/META-INF/spring.factories)) дополнительно подхватывает `psw.env` из рабочей директории (`user.dir`).

Создайте файл `psw.env` в корне проекта (или в `src/main/resources`) со значениями:

```properties
DB_USER=postgres
DB_PASSWORD=<ваш_пароль>
TELEGRAM_BOT_TOKEN=<токен_бота>
```

> Файл `psw.env` и `wildberries-storage-state.json` не должны попадать в репозиторий — добавьте их в `.gitignore`.

## Как получить `wildberries-storage-state.json`

Режимы переключаются автоматически через `wildberries.headless`:

1. **Если старый файл сессии был от домена `.ru` — удалите `wildberries-storage-state.json`** (иначе cookies `.ru` будут мешать авторизации на `.by`).
2. В [`application.properties`](src/main/resources/application.properties) установите `wildberries.headless=false`.
3. Запустите приложение — откроется видимый браузер с корзиной Wildberries.
   При `headless=false` сканирование отключено, работает только [`SessionCaptureRunner`](src/main/java/com/ap10er/tracker/runner/SessionCaptureRunner.java). Если файл сессии уже есть, он загружается — так cookies сохраняются между запусками.
4. Вручную авторизуйтесь в открывшемся окне браузера (даётся до 120 секунд; можно нажать Enter в терминале, чтобы сохранить раньше).
5. Сессия автоматически сохранится в `wildberries-storage-state.json`.
6. Установите `wildberries.headless=true` и запустите приложение снова — теперь выполнится сканирование корзины и отправка отчёта в Telegram.

## Настройка

Файл [`src/main/resources/application.properties`](src/main/resources/application.properties):

```properties
# ---------------- PostgreSQL ----------------
spring.datasource.url=jdbc:postgresql://localhost:5433/ozon_tracker
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}

# Схема управляется Flyway (миграции в db/migration)
spring.jpa.hibernate.ddl-auto=none
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=true

# ---------------- Wildberries ----------------
wildberries.cart-url=https://www.wildberries.by/lk/basket
wildberries.storage-state-path=./wildberries-storage-state.json
wildberries.headless=true

# ---------------- Telegram ----------------
telegram.bot-token=${TELEGRAM_BOT_TOKEN}
telegram.bot-username=Ozon_trackbot
telegram.chat-id=826919386
```

Параметры:

| Параметр | Описание |
|---|---|
| `spring.datasource.url` | JDBC-URL PostgreSQL (по умолчанию порт `5433`, база `ozon_tracker`) |
| `spring.datasource.username` / `password` | Берутся из `psw.env` (`DB_USER`, `DB_PASSWORD`) |
| `wildberries.cart-url` | URL корзины Wildberries |
| `wildberries.storage-state-path` | Путь к файлу сессии Playwright |
| `wildberries.headless` | `true` — сканирование; `false` — режим захвата сессии |
| `telegram.bot-token` | Токен бота (из `psw.env`, `TELEGRAM_BOT_TOKEN`) |
| `telegram.bot-username` | Имя бота (для логов) |
| `telegram.chat-id` | Числовой chatId получателя отчёта. Если пусто — бот запомнит chatId из первого сообщения, которое вы ему напишете |

> Не публикуйте реальные пароли, токен бота и файл сессии в открытом репозитории.

> Если в `wildberries-storage-state.json` сохранены cookies домена `www.wildberries.ru`, они не подойдут для `.by`. После смены домена заново создайте сессию по инструкции выше.

## Структура проекта

```text
src/main/java/com/ap10er/tracker/
├── TrackerApplication.java              # Точка входа Spring Boot, загрузка psw.env
├── config/
│   ├── EnvFileEnvironmentPostProcessor.java # Подхват psw.env из рабочей директории
│   ├── TelegramBotConfig.java           # Регистрация Telegram-бота (long polling)
│   ├── TelegramProperties.java          # @ConfigurationProperties(prefix = "telegram")
│   └── WildberriesProperties.java       # @ConfigurationProperties(prefix = "wildberries")
├── model/CartItem.java                  # JPA-сущность товара (PK — article, поля price, minPrice)
├── repository/CartItemRepository.java   # Spring Data JPA репозиторий
├── runner/
│   ├── CartScanRunner.java              # Сканирование + отчёт в Telegram (headless=true)
│   └── SessionCaptureRunner.java        # Захват сессии в видимом браузере (headless=false)
└── service/
    ├── CartTelegramBot.java             # Формирование и отправка HTML-отчёта в Telegram
    └── WildberriesCartScanner.java      # Playwright-сканер корзины, расчёт min_price

src/main/resources/
├── application.properties
├── META-INF/spring.factories            # Регистрация EnvironmentPostProcessor
└── db/
    ├── create-cart-items.sql            # Ручное создание таблицы cart_items
    └── migration/V1__add_min_price_to_cart_items.sql # Flyway-миграция
```

## Запуск

Ошибка `No goals have been specified for this build` означает, что Maven запущен без цели. Используйте одну из команд:

```bash
./mvnw spring-boot:run
```

или через системный Maven:

```bash
mvn spring-boot:run
```

> **Для Windows:** используйте `mvnw.cmd spring-boot:run` вместо `./mvnw spring-boot:run`.
> Переменная `JAVA_HOME` должна быть задана и указывать на установленную JDK 25+.

Альтернатива — собрать и запустить JAR:

```bash
./mvnw clean package
java -jar target/Tracker-0.0.1-SNAPSHOT.jar
```

### Запуск из IntelliJ IDEA

1. `Run` → `Edit Configurations...`
2. `+` → **Spring Boot**
3. Main class: `com.ap10er.tracker.TrackerApplication`
4. Нажмите `Apply` и `Run`

Приложение выполнит сканирование один раз при старте, отправит отчёт в Telegram и завершится.

## Подготовка базы данных

Приложение использует базу `ozon_tracker` (имя можно поменять в [`application.properties`](src/main/resources/application.properties)). Если база ещё не создана:

1. Подключитесь к PostgreSQL (например, к базе `postgres`) от имени `postgres`.
2. Выполните:
   ```sql
   CREATE DATABASE ozon_tracker;
   ```
3. Подключитесь к `ozon_tracker` и выполните [`create-cart-items.sql`](src/main/resources/db/create-cart-items.sql) — он создаст таблицу `cart_items`.

Из терминала это выглядит так:

```bash
psql -h localhost -p 5433 -U postgres -d postgres -c "CREATE DATABASE ozon_tracker;"
psql -h localhost -p 5433 -U postgres -d ozon_tracker -f src/main/resources/db/create-cart-items.sql
```

Скрипт таблицы использует `CREATE TABLE IF NOT EXISTS`, поэтому повторный запуск безопасен.

### Миграции Flyway

Схема управляется Flyway (`spring.jpa.hibernate.ddl-auto=none`). При старте приложения Flyway применяет недостающие миграции из [`db/migration`](src/main/resources/db/migration):

- [`V1__add_min_price_to_cart_items.sql`](src/main/resources/db/migration/V1__add_min_price_to_cart_items.sql) — приводит `cart_items` к актуальной структуре: `article` становится первичным ключом, удаляются `id` и `scanned_at`, добавляется `min_price` (минимум за всё время).

Итоговая структура таблицы `cart_items`:

| Колонка | Тип | Описание |
|---|---|---|
| `article` | `VARCHAR(255)` PK | Артикул Wildberries |
| `name` | `VARCHAR(255)` NOT NULL | Название товара |
| `price` | `NUMERIC(19,2)` NOT NULL | Текущая цена |
| `min_price` | `NUMERIC(19,2)` NOT NULL | Минимальная цена за всё время наблюдений |

## Отчёт в Telegram

- Бот работает через long polling ([`TelegramBotConfig`](src/main/java/com/ap10er/tracker/config/TelegramBotConfig.java)).
- Получатель определяется по `telegram.chat-id`; если он пуст — бот запоминает chatId из первого входящего сообщения.
- Отчёт формируется в [`CartTelegramBot`](src/main/java/com/ap10er/tracker/service/CartTelegramBot.java): товары сортируются так, что достигшие минимума идут первыми; длинные списки разбиваются на несколько сообщений (лимит Telegram — 4096 символов).
- Если корзина пуста, отправляется сообщение `📭 Корзина пуста`.

## Диагностика: товары не находятся

При каждом сканировании сканер сохраняет HTML загруженной страницы в файл `wildberries-cart-dump.html` в корне проекта и пишет в логи:

- фактический URL (после всех редиректов),
- заголовок страницы,
- длину HTML,
- количество элементов по каждому селектору.

Если в логах `Найдено товаров в корзине Wildberries: 0`, проверьте по порядку:

1. **Фактический URL в логе** — должен быть `https://www.wildberries.by/lk/basket`. Если это страница логина/капчи — сессия недействительна.
2. **`wildberries-storage-state.json`** — должен содержать cookies домена `.by`. Старый файл от `.ru` нужно пересоздать: `wildberries.headless=false`, авторизоваться, сохранить сессию, вернуть `true`.
3. **`wildberries-cart-dump.html`** — откройте в редакторе, найдите реальный класс карточки товара (например, по названию товара или ссылке `/catalog/`) и добавьте этот селектор в массив `cardSelectors` в [`WildberriesCartScanner`](src/main/java/com/ap10er/tracker/service/WildberriesCartScanner.java).

Сейчас используются селекторы (по реальной разметке `wildberries.by`):

- карточка товара: `[data-basket-item-id]`, `[class*='listItemWrap']`, `[class*='listItemGood']`, `.basket__item`, `[class*='basket-item']`, `[data-bag-item]`, `[class*='basketItem']`, `[class*='cartItem']`, `[class*='productCard']`, `[class*='goodsItem']`, `[class*='itemWrap']`, `a[href*='/catalog/']`
- название: `[class*='goodInfoName']`, `[class*='goodInfoTitle']`, ссылка `a[href*='/catalog/']`
- цена: `[class*='listItemPriceWithoutWallet']`, `[class*='listItemPrice']` (кроме `priceOld`), `[class*='finalPrice']`, `[class*='wrapPrice']` (поддерживаются форматы `302,50 BYN`, `1 234 BYN`, `1 234 ƃ`)

Артикул извлекается из ссылки вида `/catalog/123456789` регулярным выражением `SKU_FROM_URL`, а при его отсутствии — из атрибутов `data-basket-item-id`, `data-product-id`, `data-sku`, `data-id`.

### Защита от потери данных

- Если страница корзины не открылась или Wildberries вернул антибот-проверку, сканирование прерывается, **БД и сохранённая сессия не изменяются**, отчёт не отправляется.
- Если не найдено ни одной карточки товара, сканирование считается неудачным и **БД не изменяется** (иначе таблица опустела бы).
- Сессия обновляется атомарно (через временный файл), чтобы не повредить `wildberries-storage-state.json`.
