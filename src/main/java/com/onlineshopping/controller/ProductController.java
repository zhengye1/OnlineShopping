package com.onlineshopping.controller;

import com.onlineshopping.document.ProductDocument;
import com.onlineshopping.dto.PageResponse;
import com.onlineshopping.dto.ProductRequest;
import com.onlineshopping.dto.ProductResponse;
import com.onlineshopping.service.ProductSearchService;
import com.onlineshopping.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService productService;
    private final ProductSearchService productSearchService;

    public ProductController(ProductService productService,
                             ProductSearchService productSearchService) {
        this.productService = productService;
        this.productSearchService = productSearchService;
    }

    @GetMapping
    public PageResponse<ProductResponse> getAllProducts(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "10") int size,
                                                        @RequestParam(defaultValue = "createdAt") String sortBy,
                                                        @RequestParam(defaultValue = "desc") String direction) {
        return this.productService.getAllProducts(page, size, sortBy, direction);
    }

    @GetMapping("/{id}")
    public ProductResponse getProductById(@PathVariable Long id) {
        return this.productService.getProductById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@Valid @RequestBody ProductRequest productRequest) {
        return this.productService.createProduct(productRequest);
    }

    @PutMapping("/{id}")
    public ProductResponse updateProduct(@PathVariable Long id, @Valid @RequestBody ProductRequest productRequest) {
        return this.productService.updateProduct(id, productRequest);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProduct(@PathVariable Long id) {
        this.productService.deleteProduct(id);
    }

    @GetMapping("/search")
    public PageResponse<ProductResponse> search(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Long categoryId,
                                                @RequestParam(required = false) Long minPrice,
                                                @RequestParam(required = false) Long maxPrice,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size){
        return this.productService.searchProducts(keyword, categoryId, minPrice, maxPrice, page, size);
    }

    @GetMapping("/search/es")
    public List<ProductDocument> searchES(@RequestParam String keyword) {
        return productSearchService.searchProducts(keyword);
    }

}
