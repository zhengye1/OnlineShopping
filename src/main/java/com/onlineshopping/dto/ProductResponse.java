package com.onlineshopping.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProductResponse {
    private Long id;
    private String name;
    private String description;
    private long price;
    private int stock;
    private Long categoryId;
    private String categoryName;
    private String seller;
    private LocalDateTime createdAt;
    private String status;
    private String imageUrl;
}
