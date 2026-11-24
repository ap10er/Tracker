# Tracker — Wildberries Cart Tracker

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Playwright](https://img.shields.io/badge/Playwright-1.55-45ba4b?logo=playwright&logoColor=white)](https://playwright.dev/)

Консольное Java + Spring Boot приложение, которое сканирует корзину Wildberries Беларуси (`https://www.wildberries.by/lk/basket`) через Playwright и сохраняет товары в PostgreSQL.

## Что делает

- Открывает корзину Wildberries в headless Chromium.
- Использует сохранённую сессию (`wildberries-storage-state.json`) — авторизация не требуется при каждом запуске.
- Собирает с товаров в корзине:
  - название,
  - артикул (артикул Wildberries из ссылки `/catalog/<id>`),
  - цену.
- Записывает/обновляет данные в таблицу `cart_items` в PostgreSQL.

## Требования

- Java 25+
- Maven (например, `./mvnw`)
- PostgreSQL
- Установленные браузеры Playwright (Chromium)

## Установка браузера Playwright

После первой сборки выполните:

```bash
./mvnw exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

(или `mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`)

## Как получить `wildberries-storage-state.json`

Ничего комментировать не нужно — режимы переключаются автоматически через `wildberries.headless`.

1. **Если старый файл сессии был от домена `.ru` — удалите `wildberries-storage-state.json`** (иначе cookies `.ru` будут мешать авторизации на `.by`).
2. В [`application.properties`](src/main/resources/application.properties) установите `wildberries.headless=false`.
3. Запустите приложение — откроется видимый браузер с корзиной Wildberries.
   При `headless=false` сканирование отключено, работает только [`SessionCaptureRunner`](src/main/java/com/ap10er/tracker/runner/SessionCaptureRunner.java). Если файл сессии уже есть, он загружается — так cookies сохраняются между запусками.
4. Вручную авторизуйтесь в открывшемся окне браузера (даётся до 120 секунд; можно нажать Enter в терминале, чтобы сохранить раньше).
5. Сессия автоматически сохранится в `wildberries-storage-state.json`.
6. Установите `wildberries.headless=true` и запустите приложение снова — теперь выполнится сканирование корзины.

## Настройка

Файл [`src/main/resources/application.properties`](src/main/resources/application.properties):

```properties
spring.datasource.url=jdbc:postgresql://localhost:5433/ozon_tracker
spring.datasource.username=postgres
spring.datasource.password=<ваш_пароль>

wildberries.cart-url=https://www.wildberries.by/lk/basket
wildberries.storage-state-path=./wildberries-storage-state.json
wildberries.headless=true
wildberries.page-timeout-ms=30000
```

> Не публикуйте реальные пароли и файл сессии в открытом репозитории. Перед коммитом замените пароль в `application.properties` на заглушку или вынесите секреты в переменные окружения.

> Если в `wildberries-storage-state.json` сохранены cookies домена `www.wildberries.ru`, они не подойдут для `.by`. После смены домена заново создайте сессию по инструкции ниже.

## Структура проекта

```text
src/main/java/com/ap10er/tracker/
├── TrackerApplication.java          # Точка входа Spring Boot
├── config/WildberriesProperties.java # Конфигурация сканера (@ConfigurationProperties)
├── model/CartItem.java               # JPA-сущность товара в корзине
├── repository/CartItemRepository.java # Spring Data JPA репозиторий
├── runner/CartScanRunner.java        # Запуск сканирования при старте (headless=true)
├── runner/SessionCaptureRunner.java  # Захват сессии в видимом браузере (headless=false)
└── service/WildberriesCartScanner.java # Playwright-сканер корзины
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

Приложение выполнит сканирование один раз при старте и завершится.

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

## Диагностика: товары не находятся

При каждом сканировании сканер сохраняет HTML загруженной страницы в файл `wildberries-cart-dump.html` в корне проекта и пишет в логи:

- фактический URL (после всех редиректов),
- заголовок страницы,
- длину HTML,
- количество элементов по каждому селектору.

Если в логах `Найдено товаров: 0`, проверьте по порядку:

1. **Фактический URL в логе** — должен быть `https://www.wildberries.by/lk/basket`. Если это страница логина/капчи — сессия недействительна.
2. **`wildberries-storage-state.json`** — должен содержать cookies домена `.by`. Старый файл от `.ru` нужно пересоздать: `wildberries.headless=false`, авторизоваться, сохранить сессию, вернуть `true`.
3. **`wildberries-cart-dump.html`** — откройте в редакторе, найдите реальный класс карточки товара (например, по названию товара или ссылке `/catalog/`) и добавьте этот селектор в массив `cardSelectors` в [`WildberriesCartScanner`](src/main/java/com/ap10er/tracker/service/WildberriesCartScanner.java).

Сейчас используются селекторы (по реальной разметке `wildberries.by`):

- карточка товара: `[class*='listItemGood']`, `[class*='listItemWrap']`
- название: `[class*='goodInfoName']`, `[class*='goodInfoTitle']`
- цена: `[class*='basketItemTextPrice']`, `[class*='price']`, `[class*='cost']`, `[class*='finalPrice']`, `[class*='wrapPrice']` или весь текст карточки (поддерживаются форматы `302,50 BYN`, `1 234 BYN`, `1 234 ƃ`)
- недоступные товары («Нет в наличии») и товары с нераспознанной ценой пропускаются; причина выводится в лог

Артикул извлекается из ссылки вида `/catalog/123456789` регулярным выражением `SKU_FROM_URL`.
