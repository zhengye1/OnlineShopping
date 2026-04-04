package com.onlineshopping.service;

import com.onlineshopping.dto.OrderEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderMessageProducer {
    private final RocketMQTemplate rocketMQTemplate;
    public OrderMessageProducer(RocketMQTemplate rocketMQTemplate){
        this.rocketMQTemplate = rocketMQTemplate;
    }

    public void sendOrderMessage(OrderEvent event){
        rocketMQTemplate.convertAndSend("order-topic", event);
    }
}
