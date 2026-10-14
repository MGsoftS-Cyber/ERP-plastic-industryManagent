package com.plastiflow.catalog;

import jakarta.validation.constraints.*;

public record UpdateProduct(
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 160) String name,
    @NotNull Boolean active) {}
