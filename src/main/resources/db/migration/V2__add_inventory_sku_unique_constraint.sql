-- SPDX-License-Identifier: MIT

ALTER TABLE "inventory" ADD CONSTRAINT inventory_sku_unique UNIQUE (sku);
