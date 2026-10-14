package com.plastiflow.catalog;

import java.time.Instant;
import java.util.UUID;

public record ProductView(
    UUID id,
    long version,
    String sku,
    String name,
    ProductKind kind,
    Unit unit,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {}
