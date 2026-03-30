package com.onlineshopping.dto;

import lombok.Data;

@Data
public class CartItemResponse {
    private Long id;
    private Long productId;
    private String productName;
    private long productPrice;
    private int quantity;
    private long subtotal;
    private String imageUrl;
}
