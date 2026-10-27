package com.sky.mq;

import com.sky.config.RabbitMQConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 订单超时消息的生产者。
 *
 * <p>消息体只放订单 id，不放整个订单对象：消费者需要最新状态，
 * 自己按 id 回查数据库更可靠，也避免消息体积过大。
 */
@Component
@Slf4j
public class OrderTimeoutProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitMQConfiguration rabbitMQConfiguration;

    /**
     * 投递一条延迟消息，到期后自动转投到取消队列。
     *
     * <p>TTL 按消息设置（而不是建在队列上），改超时时长配置后不需要删队列。
     * 消息体只放订单 id，消费者回查数据库拿最新状态。
     */
    public void sendOrderTimeout(Long orderId) {
        String ttl = String.valueOf(rabbitMQConfiguration.timeoutMillis());
        rabbitTemplate.convertAndSend(
                RabbitMQConfiguration.DELAY_EXCHANGE,
                RabbitMQConfiguration.DELAY_ROUTING_KEY,
                String.valueOf(orderId),
                message -> {
                    message.getMessageProperties().setExpiration(ttl);
                    return message;
                });
        log.info("订单超时消息已投递: orderId={}, ttl={}ms", orderId, ttl);
    }
}
