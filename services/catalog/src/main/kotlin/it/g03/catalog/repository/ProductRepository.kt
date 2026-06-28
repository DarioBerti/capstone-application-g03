package it.g03.catalog.repository

import it.g03.catalog.entities.Product
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

// JpaRepository<Entity, IdType> provides CRUD + derived queries (IdType is String = productId);
// JpaSpecificationExecutor adds findAll(Specification, Pageable) so list reads are paginated and
// filterable via composable Specifications. Spring Data generates the implementation at runtime.
interface ProductRepository :
    JpaRepository<Product, String>,
    JpaSpecificationExecutor<Product>