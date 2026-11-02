CREATE TABLE orders (
    id              UUID PRIMARY KEY,
    account         TEXT        NOT NULL,
    symbol          TEXT        NOT NULL,
    side            TEXT        NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type            TEXT        NOT NULL CHECK (type IN ('LIMIT', 'MARKET')),
    quantity        BIGINT      NOT NULL CHECK (quantity > 0),
    limit_price     NUMERIC(18, 4),
    filled_quantity BIGINT      NOT NULL DEFAULT 0,
    status          TEXT        NOT NULL,
    version         INT         NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CHECK (filled_quantity >= 0 AND filled_quantity <= quantity),
    CHECK ((type = 'LIMIT') = (limit_price IS NOT NULL))
);

CREATE INDEX orders_account_status ON orders (account, status, created_at);

-- One row per state change, written in the same transaction as the change.
CREATE TABLE order_events (
    order_id UUID        NOT NULL REFERENCES orders (id),
    seq      INT         NOT NULL,
    type     TEXT        NOT NULL,
    detail   JSONB       NOT NULL,
    at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (order_id, seq)
);

-- The key is scoped to the account, so two clients cannot collide on a key.
-- request_hash detects a key reused with a different body.
CREATE TABLE idempotency_keys (
    account      TEXT        NOT NULL,
    key          TEXT        NOT NULL,
    request_hash TEXT        NOT NULL,
    order_id     UUID        NOT NULL REFERENCES orders (id),
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account, key)
);
