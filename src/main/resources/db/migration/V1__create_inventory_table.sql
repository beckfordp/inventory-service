-- Table name is quoted throughout as a defensive habit: several plausible domain
-- names (e.g. "user", "group", "order") are reserved PostgreSQL keywords.
CREATE TABLE "inventory" (
    id UUID PRIMARY KEY,
    sku TEXT NOT NULL,
    quantity_available INT NOT NULL,
    quantity_reserved INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
