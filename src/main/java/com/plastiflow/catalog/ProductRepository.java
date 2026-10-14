package com.plastiflow.catalog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface ProductRepository
    extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {}
