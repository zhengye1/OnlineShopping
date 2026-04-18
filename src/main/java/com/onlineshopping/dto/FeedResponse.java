package com.onlineshopping.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedResponse {
    private List<ProductResponse> featuredProducts;
    private List<ProductResponse> newArrivals;
    private List<CategoryResponse> categories;
}
