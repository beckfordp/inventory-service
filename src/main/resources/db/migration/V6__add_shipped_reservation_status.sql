-- SPDX-License-Identifier: MIT
-- US-21.4: a dispatched order consumes its reservation; it is neither active
-- (releasable) nor released (stock returned).
ALTER TABLE "order_reservations"
    DROP CONSTRAINT IF EXISTS order_reservations_status_check;

ALTER TABLE "order_reservations"
    ADD CONSTRAINT order_reservations_status_check
    CHECK (status IN ('active', 'released', 'shipped'));

-- Manual-reconciliation flags for the cancel-vs-dispatch race.
CREATE TABLE "inventory_reconciliation" (
    order_id UUID PRIMARY KEY,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
