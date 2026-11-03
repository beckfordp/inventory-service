-- TD-24: stock counts can never go negative. quantity_reserved is changed only
-- by the reservation ledger (reserve/release/ship); a release that would drive
-- it below 0 now fails instead of silently drifting.
ALTER TABLE "inventory"
    ADD CONSTRAINT inventory_quantity_available_nonnegative CHECK (quantity_available >= 0),
    ADD CONSTRAINT inventory_quantity_reserved_nonnegative CHECK (quantity_reserved >= 0);
