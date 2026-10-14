package com.plastiflow.catalog;

import jakarta.validation.constraints.*;

public record CreateProduct(
    @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9][A-Z0-9._-]*") String sku,
    @NotBlank @Size(max = 160) String name,
    @NotNull ProductKind kind,
    @NotNull Unit unit) {}
