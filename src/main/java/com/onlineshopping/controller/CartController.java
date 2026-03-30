package com.onlineshopping.controller;

import com.onlineshopping.dto.CartItemRequest;
import com.onlineshopping.dto.CartResponse;
import com.onlineshopping.service.CartService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse getCart() {
        return this.cartService.getCart();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CartResponse addToCart(@Valid @RequestBody CartItemRequest request) {
        return this.cartService.addToCart(request);
    }

    @PutMapping("/{productId}")
    public CartResponse updateQuantity(@PathVariable Long productId,
                                       @RequestParam int quantity) {
        return this.cartService.updateQuantity(productId, quantity);
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromCart(@PathVariable Long productId) {
        this.cartService.removeFromCart(productId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCart() {
        this.cartService.clearCart();
    }
}
