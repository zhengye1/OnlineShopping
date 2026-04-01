package com.onlineshopping.service;

import com.onlineshopping.dto.PaymentCallbackRequest;
import com.onlineshopping.dto.PaymentResponse;
import com.onlineshopping.enums.OrderStatus;
import com.onlineshopping.enums.PaymentStatus;
import com.onlineshopping.exception.BadRequestException;
import com.onlineshopping.model.Order;
import com.onlineshopping.model.Payment;
import com.onlineshopping.repository.OrderRepository;
import com.onlineshopping.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    public PaymentService(PaymentRepository paymentRepository,
                          OrderRepository orderRepository){
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
    }

    public PaymentResponse createPayment(Long orderId){
        // 1. find the order
        Order order = this.orderRepository.findById(orderId).
                orElseThrow(() -> new BadRequestException("Order not exists"));
        // 2. Create payment record
        if (order.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new BadRequestException("Order is not pending payment");
        }

        Payment payment = new Payment();
        payment.setOrder(order);
        long subtotal = order.getTotalPrice();
        long tax = BigDecimal.valueOf(subtotal)
                        .multiply(BigDecimal.valueOf(13))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                        .longValue();
        payment.setAmount(subtotal + tax);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        paymentRepository.save(payment);

        return toPaymentResponse(payment);
    }

    @Transactional
    public PaymentResponse handlePaymentCallback(PaymentCallbackRequest request) {
        // 1. 用transactionId搵payment record
        Payment payment = this.paymentRepository.findByOrderId(request.getOrderId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No payment found"));
        payment.setTransactionId(request.getTransactionId());
        // 2. Idempotency check — 已经SUCCESS就直接return
        if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) return toPaymentResponse(payment);
        // 3. 验证amount match
        if (!payment.getAmount().equals(request.getAmount())) throw new BadRequestException("Amount not match");
        // 4. 更新payment status + paymentTime
        payment.setPaymentStatus(PaymentStatus.SUCCESS);
        payment.setPaymentTime(LocalDateTime.now());
        paymentRepository.save(payment);
        // 5. 如果SUCCESS → 更新order status为PAID
        Order order = payment.getOrder();
        order.setOrderStatus(OrderStatus.PAID);
        this.orderRepository.save(order);

        // 6. Return response
        return toPaymentResponse(payment);
    }

    private PaymentResponse toPaymentResponse(Payment payment){
        PaymentResponse response = new PaymentResponse();
        response.setId(payment.getId());
        response.setPaymentStatus(payment.getPaymentStatus().name());
        response.setOrderId(payment.getOrder().getId());
        response.setAmount(payment.getAmount());
        response.setTransactionId(payment.getTransactionId());
        response.setPaymentTime(payment.getPaymentTime());
        response.setPaymentMethod(payment.getPaymentMethod());
        return response;
    }
}
