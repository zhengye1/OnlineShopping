package com.onlineshopping.repository;

import com.onlineshopping.enums.ProductStatus;
import com.onlineshopping.enums.Role;
import com.onlineshopping.model.Category;
import com.onlineshopping.model.Product;
import com.onlineshopping.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("test_db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    ProductRepository productRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    UserRepository userRepository;

    @Test
    void saveAndFindById_returnsProduct() {
        Category category = new Category();
        category.setName("test category");
        categoryRepository.save(category);

        User seller = new User();
        seller.setUsername("seller");
        seller.setPassword("test123");
        seller.setRole(Role.SELLER);
        userRepository.save(seller);

        Product product = new Product();
        product.setCategory(category);
        product.setSeller(seller);
        product.setStatus(ProductStatus.ON_SALE);
        product.setStock(99);
        product.setName("Test Product");
        Product saved = productRepository.save(product);

        Optional<Product> p = productRepository.findById(saved.getId());
        assertTrue(p.isPresent());
        assertEquals("test category", p.get().getCategory().getName());
        assertEquals("seller", p.get().getSeller().getUsername());
        assertEquals(saved.getId(), p.get().getId());
        assertEquals("Test Product", p.get().getName());
    }
}
