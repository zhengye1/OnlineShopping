package com.onlineshopping.dto;

import lombok.Data;

@Data
public class PaymentCallbackRequest {
    private Long orderId;
    private String transactionId;
    private String paymentStatus;
    private Long amount;
}
