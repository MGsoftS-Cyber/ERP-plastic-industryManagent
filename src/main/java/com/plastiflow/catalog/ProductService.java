package com.plastiflow.catalog;

import com.plastiflow.shared.BusinessException;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class ProductService {
  private final ProductRepository products;
  private final CatalogAudit audit;

  ProductService(ProductRepository products, CatalogAudit audit) {
    this.products = products;
    this.audit = audit;
  }

  ProductView get(UUID id) {
    return find(id).view();
  }

  Page<ProductView> list(String query, ProductKind kind, Boolean active, int page, int size) {
    Specification<Product> filter =
        (root, ignored, builder) -> {
          var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
          if (query != null && !query.isBlank()) {
            String literal =
                query
                    .strip()
                    .toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            String pattern = "%" + literal + "%";
            predicates.add(
                builder.or(
                    builder.like(builder.lower(root.get("sku")), pattern, '\\'),
                    builder.like(builder.lower(root.get("name")), pattern, '\\')));
          }
          if (kind != null) predicates.add(builder.equal(root.get("kind"), kind));
          if (active != null) predicates.add(builder.equal(root.get("active"), active));
          return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    return products.findAll(filter, PageRequest.of(page, size, Sort.by("sku"))).map(Product::view);
  }

  @Transactional
  ProductView create(CreateProduct request) {
    // The unique database constraint also prevents races between simultaneous requests.
    var view = products.saveAndFlush(new Product(request)).view();
    audit.record(view.id(), "PRODUCT_CREATED", null, view);
    return view;
  }

  @Transactional
  ProductView update(UUID id, UpdateProduct request) {
    Product product = find(id);
    if (product.version() != request.version())
      throw new BusinessException(
          HttpStatus.CONFLICT, "STALE_VERSION", "Reload the product before updating it.");
    var before = product.view();
    product.update(request);
    products.flush(); // Hibernate's @Version additionally protects concurrent database updates.
    var after = product.view();
    audit.record(id, "PRODUCT_UPDATED", before, after);
    return after;
  }

  private Product find(UUID id) {
    return products
        .findById(id)
        .orElseThrow(
            () ->
                new BusinessException(
                    HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found."));
  }
}
