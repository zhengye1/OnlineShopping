package com.onlineshopping.dto;

import lombok.Data;

@Data
public class CheckoutCommand {
    OrderRequest request;
    Long userId;
}
