-- ============================================================
-- Миграция: приведение cart_items к новой структуре
--   - article становится первичным ключом
--   - удаляются колонки id и scanned_at
--   - колонка min_price хранит минимальную цену за всё время
--
-- Порядок важен: PostgreSQL не позволяет добавить NOT NULL-колонку
-- в непустую таблицу без значения по умолчанию, поэтому:
--   1) удаляем старые колонки;
--   2) добавляем min_price как NULL;
--   3) заполняем её текущей ценой для существующих строк;
--   4) накладываем ограничение NOT NULL;
--   5) переносим первичный ключ на article.
-- ============================================================

ALTER TABLE cart_items DROP COLUMN IF EXISTS scanned_at;
ALTER TABLE cart_items DROP COLUMN IF EXISTS id;

ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS min_price NUMERIC(19, 2);

UPDATE cart_items SET min_price = price WHERE min_price IS NULL;

ALTER TABLE cart_items ALTER COLUMN min_price SET NOT NULL;

-- Если старый первичный ключ всё ещё существует — удаляем его.
-- Имя ограничения может отличаться, поэтому используем IF EXISTS.
ALTER TABLE cart_items DROP CONSTRAINT IF EXISTS cart_items_pkey;

ALTER TABLE cart_items ALTER COLUMN article SET NOT NULL;
ALTER TABLE cart_items ADD CONSTRAINT cart_items_pkey PRIMARY KEY (article);
