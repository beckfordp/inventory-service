-- Reservation ledger (TD-7.1, ADR 0018): one row per reserved order line, so
-- POST /inventorys/order-reservations/{orderId}/release can return exactly
-- what an order holds. inventory.quantity_reserved stays the aggregate.
CREATE TABLE "order_reservations" (
    order_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    sku TEXT NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    status TEXT NOT NULL CHECK (status IN ('active', 'released')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at TIMESTAMPTZ,
    PRIMARY KEY (order_id, order_item_id)
);
