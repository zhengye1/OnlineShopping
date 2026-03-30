package com.onlineshopping.repository;

import com.onlineshopping.enums.ProductStatus;
import com.onlineshopping.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    Page<Product> findByCategoryIdAndStatus(Long categoryId, ProductStatus status, Pageable pageable);

    Page<Product> findBySellerIdAndStatus(Long sellerId, ProductStatus status, Pageable pageable);

    // Custom JPQL query for keyword search + optional filters
    @Query("SELECT p FROM Product p WHERE p.status = :status " +
           "AND (:keyword IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:categoryId IS NULL OR p.category.id = :categoryId) " +
           "AND (:minPrice IS NULL OR p.price >= :minPrice) " +
           "AND (:maxPrice IS NULL OR p.price <= :maxPrice)")
    Page<Product> searchProducts(
            @Param("status") ProductStatus status,
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            @Param("minPrice") Long minPrice,
            @Param("maxPrice") Long maxPrice,
            Pageable pageable);
}
