package com.onlineshopping.controller;

import com.onlineshopping.dto.PaymentCallbackRequest;
import com.onlineshopping.dto.PaymentResponse;
import com.onlineshopping.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService){
        this.paymentService = paymentService;
    }

    @PostMapping("/{orderId}")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse createPayment(@PathVariable Long orderId){
        return this.paymentService.createPayment(orderId);
    }

    @PostMapping("/callback")
    public PaymentResponse callback(@RequestBody PaymentCallbackRequest request){
        return this.paymentService.handlePaymentCallback(request);
    }
}
