-- CartItem: позиция корзины + накопленная статистика цен
CREATE TABLE cart_items (
    id                    BIGSERIAL PRIMARY KEY,
    ozon_sku              VARCHAR(50)   NOT NULL UNIQUE,
    name                  VARCHAR(500)  NOT NULL,
    url                   VARCHAR(1000),
    current_price         NUMERIC(12,2),
    historical_min_price  NUMERIC(12,2) NOT NULL,
    quantity              INTEGER       NOT NULL DEFAULT 1,
    availability          VARCHAR(20)   NOT NULL DEFAULT 'UNKNOWN',
    is_in_cart            BOOLEAN       NOT NULL DEFAULT TRUE,
    added_to_cart_at      TIMESTAMP,
    removed_from_cart_at  TIMESTAMP,
    last_seen_at          TIMESTAMP,
    last_updated_at       TIMESTAMP
);

CREATE INDEX idx_cart_items_in_cart ON cart_items (is_in_cart);

-- PriceHistory: одна точка истории цены на каждый замер
CREATE TABLE price_history (
    id          BIGSERIAL PRIMARY KEY,
    cart_item_id BIGINT        NOT NULL REFERENCES cart_items (id) ON DELETE CASCADE,
    price       NUMERIC(12,2) NOT NULL,
    in_stock    BOOLEAN,
    recorded_at TIMESTAMP     NOT NULL
);

CREATE INDEX idx_price_history_item_time ON price_history (cart_item_id, recorded_at DESC);
