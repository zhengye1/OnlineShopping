package com.onlineshopping.repository;

import com.onlineshopping.enums.OrderStatus;
import com.onlineshopping.model.Order;
import com.onlineshopping.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserId(Long userId);
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Order> findByUserIdAndOrderStatus(Long userId, OrderStatus status);
}
