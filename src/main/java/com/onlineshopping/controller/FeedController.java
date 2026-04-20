package com.onlineshopping.controller;

import com.onlineshopping.dto.FeedResponse;
import com.onlineshopping.service.FeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feed")
@Tag(name = "Feed", description = "Aggregate BFF endpoints for the home page")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    @Operation(summary = "Get home-page feed",
               description = "Returns featured products, new arrivals, and all categories in a single call")
    public FeedResponse getFeed() {
        return feedService.getFeed();
    }
}
