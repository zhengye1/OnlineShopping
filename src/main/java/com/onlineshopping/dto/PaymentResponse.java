package com.onlineshopping.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PaymentResponse {
    private Long id;
    private String paymentStatus;
    private LocalDateTime paymentTime;
    private String paymentMethod;
    private String transactionId;
    private Long amount;
    private Long orderId;
}
