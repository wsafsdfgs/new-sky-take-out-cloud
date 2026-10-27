package com.sky.mq;

import com.sky.config.RabbitMQConfiguration;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 订单超时消费者：收到"过期"消息后取消订单。
 *
 * <p>幂等：MQ 只保证「至少一次投递」，同一条消息可能来两次
 * （消费者处理完还没来得及 ack 就挂了，消息会重投）。
 * 所以这里先查订单状态，已经不是待支付就直接跳过，
 * 保证重复消费不会把已支付订单误取消。
 */
@Component
@Slf4j
public class OrderTimeoutConsumer {

    @Autowired
    private OrderMapper orderMapper;

    @RabbitListener(queues = RabbitMQConfiguration.CANCEL_QUEUE)
    public void handleOrderTimeout(String orderIdStr) {
        Long orderId = Long.valueOf(orderIdStr);
        Orders order = orderMapper.getById(orderId);

        // 订单可能被物理删除（少见），直接丢弃消息，不要无限重试
        if (order == null) {
            log.warn("订单不存在，丢弃超时消息: orderId={}", orderId);
            return;
        }

        // 幂等判断：已经支付/已取消的订单，重复消息直接跳过
        if (!Orders.PENDING_PAYMENT.equals(order.getStatus())) {
            log.info("订单已非待支付状态，跳过（幂等）: orderId={}, status={}", orderId, order.getStatus());
            return;
        }

        order.setStatus(Orders.CANCELLED);
        order.setCancelReason("超时未支付");
        order.setCancelTime(LocalDateTime.now());
        orderMapper.update(order);

        log.info("订单超时已自动取消: orderId={}", orderId);
    }
}
