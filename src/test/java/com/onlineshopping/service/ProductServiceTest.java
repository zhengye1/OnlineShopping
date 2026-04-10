package com.onlineshopping.service;

import com.onlineshopping.dto.ProductRequest;
import com.onlineshopping.dto.ProductResponse;
import com.onlineshopping.enums.ProductStatus;
import com.onlineshopping.enums.Role;
import com.onlineshopping.exception.ResourceNotFoundException;
import com.onlineshopping.model.Category;
import com.onlineshopping.model.Product;
import com.onlineshopping.model.User;
import com.onlineshopping.repository.CategoryRepository;
import com.onlineshopping.repository.ProductRepository;
import com.onlineshopping.repository.UserRepository;
import com.onlineshopping.repository.es.ProductSearchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ProductServiceTest {
    @Mock
    ProductRepository productRepository;

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    RedisTemplate<String, Object> redisTemplate;

    @Mock
    ProductSearchRepository productSearchRepository;

    @Mock
    ElasticsearchOperations elasticsearchOperations;

    @Mock
    ValueOperations<String, Object> valueOperations;

    @InjectMocks
    ProductService productService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Authentication authentication;

    @Test
    public void getProductById_cacheHit_returnsFromCache(){
        ProductResponse mockResponse  = new ProductResponse();
        mockResponse.setId(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(mockResponse);
        assertEquals(mockResponse, productService.getProductById(1L));
        verify(productRepository, never()).findById(anyLong());
    }

    @Test
    public void getProductById_cacheMiss_returnsFromDbAndSetsCache(){
        Product mockProduct = new Product();
        mockProduct.setId(1L);
        mockProduct.setStatus(ProductStatus.ON_SALE);
        Category mockCategory = new Category();
        mockCategory.setName("Electronics");
        mockProduct.setCategory(mockCategory);
        User mockSeller = new User();
        mockSeller.setUsername("seller1");
        mockProduct.setSeller(mockSeller);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(productRepository.findById(anyLong())).thenReturn(Optional.of(mockProduct));
        ProductResponse result = productService.getProductById(1L);
        assertEquals(1L, result.getId());
        assertEquals("Electronics", result.getCategoryName());
        assertEquals("seller1", result.getSeller());
        verify(valueOperations, atLeastOnce()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    public void getProductsById_notFound_throwsException(){
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(productRepository.findById(anyLong())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> productService.getProductById(1L));
    }

    @Test
    public void createProduct_success_savesAndReturnsProduct(){
        when(authentication.getName()).thenReturn("seller1");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        Category mockCategory = new Category();
        mockCategory.setId(1L);
        mockCategory.setName("Electronics");
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("seller1");
        mockUser.setRole(Role.SELLER);

        Product mockProduct = new Product();
        mockProduct.setId(1L);
        mockProduct.setStatus(ProductStatus.ON_SALE);
        mockProduct.setCategory(mockCategory);
        mockProduct.setSeller(mockUser);

        ProductRequest request = new ProductRequest();
        request.setCategoryId(1L);

        when(categoryRepository.findById(anyLong())).thenReturn(Optional.of(mockCategory));
        when(userRepository.findByUsername("seller1")).thenReturn(Optional.of(mockUser));
        when(productRepository.save(any(Product.class))).thenReturn(mockProduct);
        ProductResponse mockResponse = productService.createProduct(request);
        assertEquals(1L, mockResponse.getId());
        assertEquals("Electronics", mockResponse.getCategoryName());
        assertEquals("seller1", mockResponse.getSeller());

    }
}

