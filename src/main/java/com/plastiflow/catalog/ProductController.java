package com.plastiflow.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
@Validated
class ProductController {
  private final ProductService service;

  ProductController(ProductService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('CATALOG_READER', 'CATALOG_MANAGER')")
  ProductPage list(
      @RequestParam(required = false) @Size(max = 100) String q,
      @RequestParam(required = false) ProductKind kind,
      @RequestParam(required = false) Boolean active,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    Page<ProductView> result = service.list(q, kind, active, page, size);
    return new ProductPage(
        result.getContent(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('CATALOG_READER', 'CATALOG_MANAGER')")
  ProductView get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping
  @PreAuthorize("hasRole('CATALOG_MANAGER')")
  ResponseEntity<ProductView> create(@RequestBody @Valid CreateProduct request) {
    ProductView created = service.create(request);
    return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('CATALOG_MANAGER')")
  ProductView update(@PathVariable UUID id, @RequestBody @Valid UpdateProduct request) {
    return service.update(id, request);
  }

  record ProductPage(
      List<ProductView> items, int page, int size, long totalItems, int totalPages) {}
}
