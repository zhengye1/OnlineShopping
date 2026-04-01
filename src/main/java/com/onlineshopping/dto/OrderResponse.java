package com.onlineshopping.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderResponse {
    private Long id;
    private String shippingAddress;
    private String trackingNumber;
    private String orderStatus;
    private List<OrderItemResponse> items;
    private Long subtotal;
    private Long tax;
    private Long total;
    private LocalDateTime createdAt;
}
