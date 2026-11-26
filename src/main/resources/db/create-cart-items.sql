-- ============================================================
-- Подготовка PostgreSQL для Wildberries Cart Tracker
-- База: ozon_tracker (имя БД можно поменять в application.properties)
-- ============================================================

-- ШАГ 1 (только если базы ещё нет). Выполнить ОТДЕЛЬНО, подключившись к БД postgres:
-- CREATE DATABASE ozon_tracker;
--
-- Если база уже есть — ничего делать не нужно, переходите к шагу 2.

-- ШАГ 2. Подключитесь к базе ozon_tracker и выполните создание таблицы:

CREATE TABLE IF NOT EXISTS cart_items (
    article    VARCHAR(255)   PRIMARY KEY,
    name       VARCHAR(255)   NOT NULL,
    price      NUMERIC(19, 2) NOT NULL,
    min_price  NUMERIC(19, 2) NOT NULL
);

-- Миграция для уже существующей таблицы (если колонка min_price ещё есть,
-- а id / scanned_at нужно удалить):
ALTER TABLE cart_items DROP COLUMN IF EXISTS id;
ALTER TABLE cart_items DROP COLUMN IF EXISTS scanned_at;
ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS min_price NUMERIC(19, 2);
UPDATE cart_items SET min_price = price WHERE min_price IS NULL;
ALTER TABLE cart_items ALTER COLUMN min_price SET NOT NULL;

-- Если article ещё не первичный ключ, а у таблицы есть старый PK —
-- раскомментируйте и выполнитеistroку ниже (замените имя ограничения при необходимости):
-- ALTER TABLE cart_items DROP CONSTRAINT IF EXISTS cart_items_pkey;
-- ALTER TABLE cart_items ADD PRIMARY KEY (article);

-- Пояснение к типам:
--   article   -> @Id, String (артикул Wildberries, первичный ключ)
--   name      -> @Column(nullable = false), String -> VARCHAR(255)
--   price     -> @Column(precision = 19, scale = 2), BigDecimal -> NUMERIC(19,2) (текущая цена)
--   min_price -> @Column(nullable = false, precision = 19, scale = 2), BigDecimal -> NUMERIC(19,2) (минимум за всё время)
--
-- Если таблица существует с другой структурой, раскомментируйте строку ниже и выполните заново:
-- DROP TABLE IF EXISTS cart_items;
