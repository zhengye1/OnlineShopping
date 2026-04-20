package com.onlineshopping.service;

import com.onlineshopping.dto.FeedResponse;
import com.onlineshopping.dto.PageResponse;
import com.onlineshopping.dto.ProductResponse;
import com.onlineshopping.model.Category;
import com.onlineshopping.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedServiceTest {

    @Mock ProductService productService;
    @Mock CategoryRepository categoryRepository;
    @InjectMocks FeedService feedService;

    @Test
    void getFeed_returns_featured_newArrivals_and_categories() {
        ProductResponse p1 = new ProductResponse();
        p1.setId(1L);
        ProductResponse p2 = new ProductResponse();
        p2.setId(2L);

        PageResponse<ProductResponse> featured = new PageResponse<>();
        featured.setContent(List.of(p1));

        PageResponse<ProductResponse> newArrivals = new PageResponse<>();
        newArrivals.setContent(List.of(p2));

        when(productService.getAllProducts(0, 8, "createdAt", "desc"))
                .thenReturn(featured);
        when(productService.getAllProducts(1, 8, "createdAt", "desc"))
                .thenReturn(newArrivals);

        Category cat = new Category();
        cat.setId(10L);
        cat.setName("Electronics");
        when(categoryRepository.findAll()).thenReturn(List.of(cat));

        FeedResponse result = feedService.getFeed();

        assertEquals(1, result.getFeaturedProducts().size());
        assertEquals(1L, result.getFeaturedProducts().get(0).getId());
        assertEquals(1, result.getNewArrivals().size());
        assertEquals(2L, result.getNewArrivals().get(0).getId());
        assertEquals(1, result.getCategories().size());
        assertEquals(10L, result.getCategories().get(0).getId());
        assertEquals("Electronics", result.getCategories().get(0).getName());
    }
}
