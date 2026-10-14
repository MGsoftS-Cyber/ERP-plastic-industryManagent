CREATE TABLE catalog_product (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    sku VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    unit VARCHAR(12) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_catalog_product_sku UNIQUE (sku),
    CONSTRAINT ck_catalog_kind CHECK (kind IN ('RAW_MATERIAL', 'FINISHED_GOOD', 'PACKAGING', 'SPARE_PART')),
    CONSTRAINT ck_catalog_unit CHECK (unit IN ('KG', 'PIECE', 'METER', 'LITER')),
    CONSTRAINT ck_catalog_version CHECK (version >= 0)
);
CREATE INDEX ix_catalog_product_kind_active ON catalog_product (kind, active);
