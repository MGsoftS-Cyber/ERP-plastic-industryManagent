package com.plastiflow.catalog;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_product")
class Product {
  @Id private UUID id;
  @Version private long version;

  @Column(nullable = false, length = 40)
  private String sku;

  @Column(nullable = false, length = 160)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private ProductKind kind;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private Unit unit;

  @Column(nullable = false)
  private boolean active;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected Product() {}

  Product(CreateProduct request) {
    id = UUID.randomUUID();
    sku = request.sku();
    name = request.name().strip();
    kind = request.kind();
    unit = request.unit();
    active = true;
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  void update(UpdateProduct request) {
    // SKU, kind and base unit stay stable: later stock and document references rely on them.
    name = request.name().strip();
    active = request.active();
    updatedAt = Instant.now();
  }

  long version() {
    return version;
  }

  ProductView view() {
    return new ProductView(id, version, sku, name, kind, unit, active, createdAt, updatedAt);
  }
}
