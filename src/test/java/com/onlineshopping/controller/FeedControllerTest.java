package com.onlineshopping.controller;

import com.onlineshopping.dto.CategoryResponse;
import com.onlineshopping.dto.FeedResponse;
import com.onlineshopping.dto.ProductResponse;
import com.onlineshopping.repository.es.ProductSearchRepository;
import com.onlineshopping.service.FeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FeedService feedService;

    @MockBean
    private ProductSearchRepository productSearchRepository;

    @Test
    void getFeed_returns_200_with_aggregate_payload() throws Exception {
        ProductResponse p = new ProductResponse();
        p.setId(1L);
        p.setName("Phone");

        FeedResponse feed = new FeedResponse(
                List.of(p),
                List.of(p),
                List.of(new CategoryResponse(10L, "Electronics")));

        when(feedService.getFeed()).thenReturn(feed);

        mockMvc.perform(get("/api/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featuredProducts[0].id").value(1))
                .andExpect(jsonPath("$.featuredProducts[0].name").value("Phone"))
                .andExpect(jsonPath("$.newArrivals[0].id").value(1))
                .andExpect(jsonPath("$.categories[0].id").value(10))
                .andExpect(jsonPath("$.categories[0].name").value("Electronics"));
    }
}
