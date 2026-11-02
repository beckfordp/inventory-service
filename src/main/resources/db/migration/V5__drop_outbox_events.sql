-- TD-22: inventory-service publishes no events; the outbox table has been
-- inert since TD-7.5 retired the stock events. The Debezium connector and
-- cleanup CronJob are removed separately.
DROP TABLE IF EXISTS "outbox_events";
