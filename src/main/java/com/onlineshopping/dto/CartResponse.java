package com.onlineshopping.dto;

import lombok.Data;

import java.util.List;

@Data
public class CartResponse {
    private List<CartItemResponse> items;
    private int totalItems;
    private long totalPrice;
}
