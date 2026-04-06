package com.onlineshopping.service;

import com.onlineshopping.dto.CheckoutCommand;
import com.onlineshopping.dto.OrderEvent;
import com.onlineshopping.dto.OrderRequest;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Service
public class OrderMessageProducer {
    private final RocketMQTemplate rocketMQTemplate;
    public OrderMessageProducer(RocketMQTemplate rocketMQTemplate){
        this.rocketMQTemplate = rocketMQTemplate;
    }

    public void sendOrderMessage(OrderEvent event, CheckoutCommand arg){
        Message<OrderEvent> message = MessageBuilder.withPayload(event).build();
        rocketMQTemplate.sendMessageInTransaction("order-topic", message, arg);
    }
}
