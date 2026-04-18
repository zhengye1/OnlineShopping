package com.onlineshopping.service;

import com.onlineshopping.dto.OrderEvent;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
@RocketMQMessageListener(topic = "order-topic", consumerGroup = "order-consumer-group")
public class OrderMessageConsumer implements RocketMQListener<OrderEvent> {

    private static final Logger log = LoggerFactory.getLogger(OrderMessageConsumer.class);

    @Override
    public void onMessage(OrderEvent event) {
        log.info("Received order message, order event: {}", event);
    }
}
