package com.onlineshopping.service;

import com.onlineshopping.dto.CheckoutCommand;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;

@RocketMQTransactionListener
public class OrderTransactionListener implements RocketMQLocalTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(OrderTransactionListener.class);

    private final OrderService orderService;
    public OrderTransactionListener(OrderService orderService){
        this.orderService = orderService;
    }
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        try {
            log.info("Executing local transaction...");
            orderService.doCheckout((CheckoutCommand) arg);
            log.info("Local transaction SUCCESS, returning COMMIT");
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("Local transaction FAILED, returning ROLLBACK", e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        log.info("Check local transaction called");
        return RocketMQLocalTransactionState.COMMIT;
    }
}
