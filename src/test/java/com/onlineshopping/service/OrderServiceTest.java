package com.onlineshopping.service;

import com.onlineshopping.dto.OrderResponse;
import com.onlineshopping.enums.OrderStatus;
import com.onlineshopping.enums.Role;
import com.onlineshopping.exception.BadRequestException;
import com.onlineshopping.model.Order;
import com.onlineshopping.model.OrderItem;
import com.onlineshopping.model.Product;
import com.onlineshopping.model.User;
import com.onlineshopping.repository.CartItemRepository;
import com.onlineshopping.repository.OrderRepository;
import com.onlineshopping.repository.SagaExecutionRepository;
import com.onlineshopping.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static com.onlineshopping.enums.OrderStatus.CANCELLED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {
    @Mock
    UserRepository userRepository;

    @Mock
    CartItemRepository cartItemRepository;

    @Mock
    OrderRepository orderRepository;

    @Mock
    OrderMessageProducer orderMessageProducer;

    @Mock
    RedisService redisService;

    @Mock
    SagaExecutionRepository sagaExecutionRepository;

    @Mock
    MeterRegistry meterRegistry;

    @Mock
    Counter counter;

    @Mock
    RedisTemplate<String, Object> redisTemplate;

    @Mock
    SecurityContext securityContext;

    @Mock
    Authentication authentication;

    @InjectMocks
    OrderService orderService;

    @Test
    public void cancelOrder_success_restoresStock(){
        when(authentication.getName()).thenReturn("buyer1");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("buyer1");
        mockUser.setRole(Role.BUYER);
        Product mockProduct = new Product();
        mockProduct.setStock(90);  // 假設本來 100，買咗 10
        OrderItem mockOrderItem = new OrderItem();
        mockOrderItem.setProduct(mockProduct);
        mockOrderItem.setQuantity(10);

        when(userRepository.findByUsername("buyer1")).thenReturn(Optional.of(mockUser));
        Order mockOrder = new Order();
        mockOrder.setId(1L);
        mockOrder.setOrderStatus(OrderStatus.PENDING_PAYMENT);
        mockOrder.setUser(mockUser);
        mockOrder.setOrderItems(List.of(mockOrderItem));
        when(orderRepository.findById(anyLong())).thenReturn(Optional.of(mockOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(mockOrder);
        when(meterRegistry.counter(anyString())).thenReturn(counter);
        OrderResponse response = orderService.cancelOrder(1L);
        assertEquals(CANCELLED.name(), response.getOrderStatus());
        assertEquals(100, mockProduct.getStock());
    }

    @Test
    public void cancelOrder_wrongStatus_throwsException(){
        when(authentication.getName()).thenReturn("buyer1");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("buyer1");
        mockUser.setRole(Role.BUYER);
        when(userRepository.findByUsername("buyer1")).thenReturn(Optional.of(mockUser));
        Order mockOrder = new Order();
        mockOrder.setId(1L);
        mockOrder.setOrderStatus(OrderStatus.SHIPPED);
        mockOrder.setUser(mockUser);
        when(orderRepository.findById(anyLong())).thenReturn(Optional.of(mockOrder));
        assertThrows(BadRequestException.class, () -> orderService.cancelOrder(1L));

    }
}