-- Таблица товаров в корзине
CREATE TABLE cart_items (
                            id BIGSERIAL PRIMARY KEY,
                            ozon_item_id VARCHAR(50) UNIQUE NOT NULL,
                            name VARCHAR(500) NOT NULL,
                            url VARCHAR(1000),
                            current_price DECIMAL(10, 2) NOT NULL,
                            historical_min_price DECIMAL(10, 2) NOT NULL,
                            is_in_cart BOOLEAN DEFAULT TRUE,
                            added_to_cart_at TIMESTAMP DEFAULT NOW(),
                            removed_from_cart_at TIMESTAMP,
                            last_updated_at TIMESTAMP DEFAULT NOW()
);

-- Таблица истории цен
CREATE TABLE price_history (
                               id BIGSERIAL PRIMARY KEY,
                               cart_item_id BIGINT NOT NULL REFERENCES cart_items(id) ON DELETE CASCADE,
                               price DECIMAL(10, 2) NOT NULL,
                               recorded_at TIMESTAMP DEFAULT NOW()
);

-- Индексы для ускорения поиска
CREATE INDEX idx_price_history_item_date ON price_history(cart_item_id, recorded_at);
CREATE INDEX idx_cart_items_ozon_id ON cart_items(ozon_item_id);
CREATE INDEX idx_cart_items_in_cart ON cart_items(is_in_cart);

-- Комментарий к таблицам
COMMENT ON TABLE cart_items IS 'Товары из корзины Ozon';
COMMENT ON TABLE price_history IS 'История изменения цен';