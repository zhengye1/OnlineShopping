package com.onlineshopping.service;

import com.onlineshopping.dto.CategoryResponse;
import com.onlineshopping.dto.FeedResponse;
import com.onlineshopping.dto.PageResponse;
import com.onlineshopping.dto.ProductResponse;
import com.onlineshopping.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FeedService {

    private static final int PAGE_SIZE = 8;

    private final ProductService productService;
    private final CategoryRepository categoryRepository;

    public FeedService(ProductService productService,
                       CategoryRepository categoryRepository) {
        this.productService = productService;
        this.categoryRepository = categoryRepository;
    }

    public FeedResponse getFeed() {
        PageResponse<ProductResponse> featured =
                productService.getAllProducts(0, PAGE_SIZE, "createdAt", "desc");
        PageResponse<ProductResponse> newArrivals =
                productService.getAllProducts(1, PAGE_SIZE, "createdAt", "desc");

        List<CategoryResponse> categories = categoryRepository.findAll().stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName()))
                .toList();

        return new FeedResponse(
                featured.getContent(),
                newArrivals.getContent(),
                categories);
    }
}
