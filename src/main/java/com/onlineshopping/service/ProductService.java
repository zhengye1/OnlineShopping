package com.onlineshopping.service;

import com.onlineshopping.document.ProductDocument;
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
import com.onlineshopping.repository.es.ProductSearchRepository;
import com.onlineshopping.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductSearchRepository productSearchRepository;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          UserRepository userRepository,
                          RedisTemplate<String, Object> redisTemplate,
                          ProductSearchRepository productSearchRepository,
                          ElasticsearchOperations elasticsearchOperations) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.productSearchRepository = productSearchRepository;
    }

    // Paginated listing — public, only ON_SALE products
    @SuppressWarnings("unchecked")
    public PageResponse<ProductResponse> getAllProducts(int page, int size, String sortBy, String direction) {
        String redisKey = "product:page" + page + ":size:"+size+":sorted:"+sortBy+":dir:"+direction;
        Object cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) return (PageResponse<ProductResponse>) cached;
        Sort sort = direction.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Product> productPage = productRepository.findByStatus(ProductStatus.ON_SALE, pageable);
        PageResponse<ProductResponse> response = toPageResponse(productPage);
        redisTemplate.opsForValue().set(redisKey, response, 5, TimeUnit.MINUTES);
        return response;
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
        String redisKey = "product:" + id;
        Object cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) return (ProductResponse) cached;
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        // save to redis and set ttl
        ProductResponse response = toResponse(product);
        redisTemplate.opsForValue().set(redisKey, response, 30, TimeUnit.MINUTES);
        return response;
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
        productSearchRepository.save(toDocument(product));

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
        productSearchRepository.save(toDocument(product));

        Product saved = productRepository.save(product);

        this.redisTemplate.delete("product:" + id);
        return toResponse(saved);
    }

    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        productRepository.delete(product);
        this.redisTemplate.delete("product:" + id);
        productSearchRepository.deleteById(product.getId());
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
                productPage.getContent().stream().map(this::toResponse).collect(Collectors.toList()),
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages(),
                productPage.isLast()
        );
    }

    private ProductDocument toDocument(Product product) {
        ProductDocument doc = new ProductDocument();
        doc.setId(product.getId());
        doc.setName(product.getName());
        doc.setDescription(product.getDescription());
        doc.setPrice(product.getPrice());
        doc.setCategoryName(product.getCategory().getName());
        doc.setSeller(product.getSeller().getUsername());
        return doc;
    }
}
