package com.onlineshopping.service;

import com.onlineshopping.dto.CartItemRequest;
import com.onlineshopping.dto.CartResponse;
import com.onlineshopping.enums.ProductStatus;
import com.onlineshopping.enums.Role;
import com.onlineshopping.exception.BadRequestException;
import com.onlineshopping.model.CartItem;
import com.onlineshopping.model.Category;
import com.onlineshopping.model.Product;
import com.onlineshopping.model.User;
import com.onlineshopping.repository.CartItemRepository;
import com.onlineshopping.repository.ProductRepository;
import com.onlineshopping.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class CartServiceTest {
    @Mock
    CartItemRepository cartItemRepository;

    @Mock
    ProductRepository productRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    SecurityContext securityContext;

    @Mock
    Authentication authentication;

    @InjectMocks
    CartService cartService;

    @Test
    public void addToCart_success_newItem(){
        when(authentication.getName()).thenReturn("buyer1");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("buyer1");
        mockUser.setRole(Role.BUYER);
        when(userRepository.findByUsername("buyer1")).thenReturn(Optional.of(mockUser));
        Product mockProduct = new Product();
        mockProduct.setId(1L);
        mockProduct.setStatus(ProductStatus.ON_SALE);
        Category mockCategory = new Category();
        mockCategory.setName("Electronics");
        mockProduct.setCategory(mockCategory);
        mockProduct.setStock(100);
        mockProduct.setPrice(999L);
        User mockSeller = new User();
        mockSeller.setUsername("seller1");
        mockProduct.setSeller(mockSeller);
        when(productRepository.findById(anyLong())).thenReturn(Optional.of(mockProduct));
        CartItemRequest request = new CartItemRequest();
        request.setProductId(1L);
        request.setQuantity(1);
        CartItem mockCartItem = new CartItem();
        mockCartItem.setUser(mockUser);
        mockCartItem.setQuantity(1);
        mockCartItem.setProduct(mockProduct);
        when(cartItemRepository.findByUserIdAndProductId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(cartItemRepository.save(any(CartItem.class))).thenReturn(mockCartItem);
        // addToCart 最後 call findByUserId 嚟 build response
        when(cartItemRepository.findByUserId(anyLong())).thenReturn(List.of(mockCartItem));
        CartResponse cartResponse = cartService.addToCart(request);
        assertEquals(1, cartResponse.getTotalItems());
        assertEquals(1, cartResponse.getItems().size());
        assertEquals(999L, cartResponse.getItems().getFirst().getProductPrice());
        assertEquals(999L, cartResponse.getItems().getFirst().getSubtotal()); // 999 * 1
    }

    @Test
    public void addToCart_productNotOnSale_throwsException(){
        when(authentication.getName()).thenReturn("buyer1");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        Product mockProduct = new Product();
        mockProduct.setId(1L);
        mockProduct.setStatus(ProductStatus.OFF_SHELF);
        Category mockCategory = new Category();
        mockCategory.setName("Electronics");
        mockProduct.setCategory(mockCategory);
        mockProduct.setStock(100);
        mockProduct.setPrice(999L);
        User mockSeller = new User();
        mockSeller.setUsername("seller1");
        mockProduct.setSeller(mockSeller);
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("buyer1");
        mockUser.setRole(Role.BUYER);
        when(userRepository.findByUsername("buyer1")).thenReturn(Optional.of(mockUser));
        when(productRepository.findById(anyLong())).thenReturn(Optional.of(mockProduct));
        CartItemRequest request = new CartItemRequest();
        request.setProductId(1L);
        request.setQuantity(1);
        assertThrows(BadRequestException.class, () -> cartService.addToCart(request));
    }
}
