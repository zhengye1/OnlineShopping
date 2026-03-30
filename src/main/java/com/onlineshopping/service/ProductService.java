package com.onlineshopping.service;

import com.onlineshopping.dto.PageResponse;
import com.onlineshopping.dto.ProductRequest;
import com.onlineshopping.dto.ProductResponse;
import com.onlineshopping.enums.ProductStatus;
import com.onlineshopping.exception.ResourceNotFoundException;
import com.onlineshopping.model.Category;
import com.onlineshopping.model.Product;
import com.onlineshopping.model.User;
import com.onlineshopping.repository.CategoryRepository;
import com.onlineshopping.repository.ProductRepository;
import com.onlineshopping.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          UserRepository userRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
    }

    // Paginated listing — public, only ON_SALE products
    public PageResponse<ProductResponse> getAllProducts(int page, int size, String sortBy, String direction) {
        Sort sort = direction.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Product> productPage = productRepository.findByStatus(ProductStatus.ON_SALE, pageable);
        return toPageResponse(productPage);
    }

    // Search with filters — public
    public PageResponse<ProductResponse> searchProducts(String keyword, Long categoryId,
                                                        Long minPrice, Long maxPrice,
                                                        int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<Product> productPage = productRepository.searchProducts(
                ProductStatus.ON_SALE, keyword, categoryId, minPrice, maxPrice, pageable);
        return toPageResponse(productPage);
    }

    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        return toResponse(product);
    }

    // Create product — automatically assign current user as seller
    public ProductResponse createProduct(ProductRequest request) {
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

        User seller = getCurrentUser();

        Product product = new Product();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setCategory(category);
        product.setImageUrl(request.getImageUrl());
        product.setStatus(ProductStatus.ON_SALE);
        product.setSeller(seller);

        Product saved = productRepository.save(product);
        return toResponse(saved);
    }

    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setCategory(category);
        product.setImageUrl(request.getImageUrl());

        Product saved = productRepository.save(product);
        return toResponse(saved);
    }

    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        productRepository.delete(product);
    }

    // Get current authenticated user from SecurityContext
    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    // Entity → DTO
    private ProductResponse toResponse(Product product) {
        ProductResponse response = new ProductResponse();
        response.setId(product.getId());
        response.setName(product.getName());
        response.setDescription(product.getDescription());
        response.setPrice(product.getPrice());
        response.setStock(product.getStock());
        response.setStatus(product.getStatus().name());
        response.setImageUrl(product.getImageUrl());
        response.setCreatedAt(product.getCreatedAt());

        if (product.getCategory() != null) {
            response.setCategoryId(product.getCategory().getId());
            response.setCategoryName(product.getCategory().getName());
        }

        if (product.getSeller() != null) {
            response.setSeller(product.getSeller().getUsername());
        }

        return response;
    }

    // Page<Entity> → PageResponse<DTO>
    private PageResponse<ProductResponse> toPageResponse(Page<Product> productPage) {
        return new PageResponse<>(
                productPage.getContent().stream().map(this::toResponse).toList(),
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages(),
                productPage.isLast()
        );
    }
}
