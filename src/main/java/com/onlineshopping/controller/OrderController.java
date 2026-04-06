package com.onlineshopping.controller;

import com.onlineshopping.dto.OrderRequest;
import com.onlineshopping.dto.OrderResponse;
import com.onlineshopping.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;
    public OrderController(OrderService orderService){
        this.orderService = orderService;
    }
    @GetMapping
    public List<OrderResponse> getMyOrder(){
        return this.orderService.getMyOrder();
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id){
        return this.orderService.getOrderDetail(id);
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> checkout(@Valid @RequestBody OrderRequest orderRequest){
        this.orderService.checkout(orderRequest);
        return Map.of("message", "Order is being processed");
    }

    @PutMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id){
        return this.orderService.cancelOrder(id);
    }
}
