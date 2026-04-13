package com.onlineshopping.service;

import com.onlineshopping.dto.*;
import com.onlineshopping.enums.ProductStatus;
import com.onlineshopping.enums.SagaStatus;
import com.onlineshopping.exception.BadRequestException;
import com.onlineshopping.exception.ResourceNotFoundException;
import com.onlineshopping.model.*;
import com.onlineshopping.repository.CartItemRepository;
import com.onlineshopping.repository.OrderItemRepository;
import com.onlineshopping.repository.OrderRepository;
import com.onlineshopping.repository.SagaExecutionRepository;
import com.onlineshopping.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.onlineshopping.enums.OrderStatus.*;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final UserRepository userRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderMessageProducer orderMessageProducer;
    private final RedisService redisService;
    private final SagaExecutionRepository sagaExecutionRepository;
    private final MeterRegistry meterRegistry;

    public OrderService(UserRepository userRepository,
                        CartItemRepository cartItemRepository,
                        OrderRepository orderRepository,
                        OrderMessageProducer orderMessageProducer,
                        RedisService redisService,
                        SagaExecutionRepository sagaExecutionRepository,
                        MeterRegistry meterRegistry){
        this.userRepository = userRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.orderMessageProducer = orderMessageProducer;
        this.redisService = redisService;
        this.sagaExecutionRepository = sagaExecutionRepository;
        this.meterRegistry = meterRegistry;
    }
    public List<OrderResponse> getMyOrder(){
        User user = getCurrentUser();
        List<Order> order = this.orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        return order.stream().map(this::toOrderResponse).toList();
    }

    public OrderResponse getOrderDetail(Long orderId){
        Order order = this.orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        User user = getCurrentUser();
        if (!order.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Order not found");
        }
        return toOrderResponse(order);
    }

    public void checkout(OrderRequest request) {
        CheckoutCommand command = new CheckoutCommand();
        command.setRequest(request);
        User user = getCurrentUser();
        command.setUserId(user.getId());
        OrderEvent event = new OrderEvent();
        event.setOrderStatus("PENDING_PAYMENT");
        event.setUserId(user.getId());
        orderMessageProducer.sendOrderMessage(event, command);

    }

    @Transactional
    public void doCheckout(CheckoutCommand command){
        // === Saga 狀態追蹤 (為將來拆微服務做準備) ===
        SagaExecution saga = new SagaExecution();
        saga.setSagaType("CHECKOUT");
        saga.setUserId(command.getUserId());
        saga.setStatus(SagaStatus.STARTED);
        sagaExecutionRepository.save(saga);

        try {
            User user = userRepository.findById(command.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));

            // Step 1: 驗證購物車
            SagaStepLog step1 = saga.addStep(1, "VALIDATE_CART");
            List<CartItem> items = this.cartItemRepository.findByUserId(command.getUserId());
            if (items.isEmpty()) throw new BadRequestException("Cart is empty");
            step1.markCompleted();
            log.info("Saga [{}] Step 1: VALIDATE_CART completed", saga.getId());

            // Step 2: 扣庫存
            SagaStepLog step2 = saga.addStep(2, "DEDUCT_STOCK");
            Order order = new Order();
            order.setOrderItems(new ArrayList<>());
            long subtotal = 0L;
            for (CartItem item : items) {
                Product product = item.getProduct();
                if (!product.getStatus().equals(ProductStatus.ON_SALE))
                    throw new BadRequestException("Product is not on sale");

                OrderItem orderItem = new OrderItem();
                orderItem.setProduct(product);
                orderItem.setProductName(product.getName());
                String lockKey = "lock:product:" + product.getId();
                boolean locked = redisService.tryLock(lockKey, 10);
                if (!locked) throw new BadRequestException("System busy, please try again");
                try {
                    if (product.getStock() < item.getQuantity())
                        throw new BadRequestException("Not enough stock");
                    product.setStock(product.getStock() - item.getQuantity());
                } finally {
                    redisService.unlock(lockKey);
                }
                orderItem.setProductPrice(product.getPrice());
                orderItem.setOrder(order);
                orderItem.setQuantity(item.getQuantity());
                subtotal += orderItem.getProductPrice() * item.getQuantity();
                order.getOrderItems().add(orderItem);
            }
            step2.markCompleted();
            log.info("Saga [{}] Step 2: DEDUCT_STOCK completed", saga.getId());

            // Step 3: 創建訂單
            SagaStepLog step3 = saga.addStep(3, "CREATE_ORDER");
            order.setUser(user);
            order.setShippingAddress(command.getRequest().getShippingAddress());
            order.setOrderStatus(PENDING_PAYMENT);
            order.setTotalPrice(subtotal);
            this.orderRepository.save(order);
            saga.setOrderId(order.getId());
            step3.markCompleted();
            log.info("Saga [{}] Step 3: CREATE_ORDER completed, orderId={}", saga.getId(), order.getId());

            // Step 4: 清空購物車
            SagaStepLog step4 = saga.addStep(4, "CLEAR_CART");
            this.cartItemRepository.deleteByUserId(user.getId());
            step4.markCompleted();
            log.info("Saga [{}] Step 4: CLEAR_CART completed", saga.getId());

            // Saga 完成
            saga.setStatus(SagaStatus.COMPLETED);
            saga.setCompletedAt(java.time.LocalDateTime.now());
            sagaExecutionRepository.save(saga);
            log.info("Saga [{}] CHECKOUT completed successfully, orderId={}", saga.getId(), order.getId());
            meterRegistry.counter("orders.checkout.count").increment();
        } catch (Exception e) {
            // Saga 失敗 — 記錄狀態
            // 注: 因為仲係 monolith + @Transactional，DB 會自動 rollback
            // 將來拆服務後，呢度會改成手動執行補償
            saga.setStatus(SagaStatus.FAILED);
            sagaExecutionRepository.save(saga);
            log.error("Saga [{}] CHECKOUT failed: {}", saga.getId(), e.getMessage());
            throw e; // 重新 throw，觸發 @Transactional rollback
        }
    }


    public boolean checkOrderExists(Long orderId) {
        return orderRepository.existsById(orderId);
    }

    @Transactional
    public OrderResponse cancelOrder(Long orderId){
        User user = getCurrentUser();
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!user.getId().equals(order.getUser().getId())) throw new BadRequestException("The order didn't belong to you");
        if (order.getOrderStatus() != PENDING_PAYMENT && order.getOrderStatus() != PAID) throw new BadRequestException("Not the right order status to cancel");
        List<OrderItem> items = order.getOrderItems();
        for (OrderItem item : items){
            item.getProduct().setStock(item.getProduct().getStock() + item.getQuantity());
        }
        order.setOrderStatus(CANCELLED);
        orderRepository.save(order);
        meterRegistry.counter("orders.cancel.count").increment();
        return toOrderResponse(order);
    }

    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private OrderItemResponse toOrderItemResponse(OrderItem orderItem){
        OrderItemResponse response = new OrderItemResponse();
        response.setId(orderItem.getId());
        response.setProductName(orderItem.getProduct().getName());
        response.setQuantity(orderItem.getQuantity());
        response.setProductId(orderItem.getProduct().getId());
        response.setProductPrice(orderItem.getProductPrice());
        response.setTotalPrice(orderItem.getProductPrice() * orderItem.getQuantity());
        return response;
    }
    private OrderResponse toOrderResponse(Order order){
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setOrderStatus(order.getOrderStatus().name());
        response.setShippingAddress(order.getShippingAddress());
        List<OrderItemResponse> itemResponse = order.getOrderItems().stream()
                        .map(this::toOrderItemResponse)
                                .toList();
        response.setItems(itemResponse);
        response.setTrackingNumber(order.getTrackingNumber());
        long subtotal = order.getTotalPrice();
        long tax = subtotal * 13 / 100;
        response.setSubtotal(subtotal);
        response.setTax(tax);
        response.setTotal(subtotal + tax);
        response.setCreatedAt(order.getCreatedAt());
        return response;

    }
}
