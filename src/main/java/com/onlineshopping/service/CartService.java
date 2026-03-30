package com.onlineshopping.service;

import com.onlineshopping.dto.CartItemRequest;
import com.onlineshopping.dto.CartItemResponse;
import com.onlineshopping.dto.CartResponse;
import com.onlineshopping.exception.BadRequestException;
import com.onlineshopping.exception.ResourceNotFoundException;
import com.onlineshopping.model.CartItem;
import com.onlineshopping.model.Product;
import com.onlineshopping.model.User;
import com.onlineshopping.enums.ProductStatus;
import com.onlineshopping.repository.CartItemRepository;
import com.onlineshopping.repository.ProductRepository;
import com.onlineshopping.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public CartService(CartItemRepository cartItemRepository,
                       ProductRepository productRepository,
                       UserRepository userRepository) {
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    public CartResponse getCart() {
        User user = getCurrentUser();
        List<CartItem> items = cartItemRepository.findByUserId(user.getId());
        return toCartResponse(items);
    }

    public CartResponse addToCart(CartItemRequest request) {
        User user = getCurrentUser();

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        // Business rule: can only add ON_SALE products
        if (product.getStatus() != ProductStatus.ON_SALE) {
            throw new BadRequestException("Product is not available for purchase");
        }

        // Business rule: check stock
        if (product.getStock() < request.getQuantity()) {
            throw new BadRequestException("Insufficient stock. Available: " + product.getStock());
        }

        // If product already in cart, update quantity
        CartItem cartItem = cartItemRepository.findByUserIdAndProductId(user.getId(), product.getId())
                .orElse(null);

        if (cartItem != null) {
            int newQuantity = cartItem.getQuantity() + request.getQuantity();
            if (newQuantity > product.getStock()) {
                throw new BadRequestException("Insufficient stock. Available: " + product.getStock());
            }
            cartItem.setQuantity(newQuantity);
        } else {
            cartItem = new CartItem();
            cartItem.setUser(user);
            cartItem.setProduct(product);
            cartItem.setQuantity(request.getQuantity());
        }

        cartItemRepository.save(cartItem);

        List<CartItem> allItems = cartItemRepository.findByUserId(user.getId());
        return toCartResponse(allItems);
    }

    public CartResponse updateQuantity(Long productId, int quantity) {
        User user = getCurrentUser();

        CartItem cartItem = cartItemRepository.findByUserIdAndProductId(user.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not in cart"));

        if (quantity <= 0) {
            cartItemRepository.delete(cartItem);
        } else {
            if (quantity > cartItem.getProduct().getStock()) {
                throw new BadRequestException("Insufficient stock. Available: " + cartItem.getProduct().getStock());
            }
            cartItem.setQuantity(quantity);
            cartItemRepository.save(cartItem);
        }

        List<CartItem> allItems = cartItemRepository.findByUserId(user.getId());
        return toCartResponse(allItems);
    }

    public void removeFromCart(Long productId) {
        User user = getCurrentUser();

        CartItem cartItem = cartItemRepository.findByUserIdAndProductId(user.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not in cart"));

        cartItemRepository.delete(cartItem);
    }

    @Transactional
    public void clearCart() {
        User user = getCurrentUser();
        cartItemRepository.deleteByUserId(user.getId());
    }

    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private CartItemResponse toCartItemResponse(CartItem cartItem) {
        CartItemResponse response = new CartItemResponse();
        response.setId(cartItem.getId());
        response.setProductId(cartItem.getProduct().getId());
        response.setProductName(cartItem.getProduct().getName());
        response.setProductPrice(cartItem.getProduct().getPrice());  // 当前价格！
        response.setQuantity(cartItem.getQuantity());
        response.setSubtotal(cartItem.getProduct().getPrice() * cartItem.getQuantity());
        response.setImageUrl(cartItem.getProduct().getImageUrl());
        return response;
    }

    private CartResponse toCartResponse(List<CartItem> items) {
        CartResponse response = new CartResponse();
        List<CartItemResponse> itemResponses = items.stream()
                .map(this::toCartItemResponse)
                .toList();
        response.setItems(itemResponses);
        response.setTotalItems(itemResponses.stream().mapToInt(CartItemResponse::getQuantity).sum());
        response.setTotalPrice(itemResponses.stream().mapToLong(CartItemResponse::getSubtotal).sum());
        return response;
    }
}
