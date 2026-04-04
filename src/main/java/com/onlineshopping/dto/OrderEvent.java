package com.onlineshopping.dto;

import lombok.Data;

@Data
public class OrderEvent {
    private Long orderId;
    private String orderStatus;
    private Long userId;
}
