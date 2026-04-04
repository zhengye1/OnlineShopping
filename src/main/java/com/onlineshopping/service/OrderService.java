package com.onlineshopping.service;

import com.onlineshopping.dto.OrderItemResponse;
import com.onlineshopping.dto.OrderRequest;
import com.onlineshopping.dto.OrderResponse;
import com.onlineshopping.enums.ProductStatus;
import com.onlineshopping.exception.BadRequestException;
import com.onlineshopping.exception.ResourceNotFoundException;
import com.onlineshopping.model.*;
import com.onlineshopping.repository.CartItemRepository;
import com.onlineshopping.repository.OrderItemRepository;
import com.onlineshopping.repository.OrderRepository;
import com.onlineshopping.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.onlineshopping.enums.OrderStatus.*;

@Service
public class OrderService {
    private final UserRepository userRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final RedisService redisService;
    public OrderService(UserRepository userRepository,
                        CartItemRepository cartItemRepository,
                        OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        RedisService redisService){
        this.userRepository = userRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.redisService = redisService;
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

    @Transactional
    public OrderResponse checkout(OrderRequest request) {
        User user = getCurrentUser();
        // 1. 拎购物车所有items
        List<CartItem> items = this.cartItemRepository.findByUserId(user.getId());
        // 2. 验证购物车非空
        if (items.isEmpty()) throw new BadRequestException("Cart is empty");

        // 3. 创建Order
        Order order = new Order();
        order.setOrderItems(new ArrayList<>());  // 初始化list
        // 4. Loop每个cart item:
        //    - 验证product状态 (ON_SALE?)
        //    - 验证库存 (stock >= quantity?)
        //    - 创建OrderItem (snapshot当前价格)
        //    - 扣库存 (product.stock -= quantity)
        //    - 累加subtotal
        long subtotal = 0L;
        for (CartItem item: items){
            Product product = item.getProduct();
            if (!product.getStatus().equals(ProductStatus.ON_SALE)) throw new BadRequestException("Product is not on sale");

            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(product);
            orderItem.setProductName(product.getName());
            String lockKey = "lock:product:" + product.getId();
            boolean locked = redisService.tryLock(lockKey, 10);
            if (!locked) throw new BadRequestException("System busy, please try again");
            try {
                // 扣库存逻辑
                if (product.getStock() < item.getQuantity()) throw new BadRequestException("Not enough stock");
                product.setStock(product.getStock() - item.getQuantity());
            } finally {
                redisService.unlock(lockKey);  // 一定要释放锁
            }
            orderItem.setProductPrice(product.getPrice());
            orderItem.setOrder(order);
            orderItem.setQuantity(item.getQuantity());
            subtotal += orderItem.getProductPrice() * item.getQuantity();
            order.getOrderItems().add(orderItem);
        }
        // 5. Set order的totalPrice, tax等
        order.setUser(user);
        order.setShippingAddress(request.getShippingAddress());
        order.setOrderStatus(PENDING_PAYMENT);
        order.setTotalPrice(subtotal);

        // 6. Save order
        this.orderRepository.save(order);

        // 7. 清空购物车
        this.cartItemRepository.deleteByUserId(user.getId());
        // 8. Return response
        return toOrderResponse(order);
    }

    @Transactional
    public OrderResponse cancelOrder(Long orderId){
        User user =getCurrentUser();
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
