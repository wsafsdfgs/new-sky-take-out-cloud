package com.sky.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单超时自动取消的 MQ 拓扑：用「TTL + 死信队列」实现延迟消息。
 *
 * 下单 → order.delay.exchange → order.delay.queue
 *                                   ↓ 消息在这里待够 TTL（默认 15 分钟）
 *                                   ↓ 过期后变成"死信"
 *        order.cancel.exchange ← 死信按 deadLetterRoutingKey 转投
 *                                   ↓
 *                          order.cancel.queue → 消费者取消订单
 *
 * 为什么不用定时任务扫表：轮询最快也要等一个扫描周期，且订单量大时是
 * 全表范围的条件查询。MQ 是"到点才投递"，延迟更短、数据库零轮询。
 */
@Configuration
public class RabbitMQConfiguration {

    public static final String DELAY_EXCHANGE = "order.delay.exchange";
    public static final String CANCEL_EXCHANGE = "order.cancel.exchange";
    public static final String DELAY_QUEUE = "order.delay.queue";
    public static final String CANCEL_QUEUE = "order.cancel.queue";
    public static final String DELAY_ROUTING_KEY = "order.delay";
    public static final String CANCEL_ROUTING_KEY = "order.cancel";

    /** 订单超时时长（分钟）。开发环境用 dev 配置覆盖成 1 分钟，方便演示 */
    @Value("${sky.order.timeout-minutes:15}")
    private long timeoutMinutes;

    /**
     * 延迟队列：TTL 不配在队列上，而是每条消息单独设置（见 {@link #timeoutMillis()}）。
     *
     * <p>原因：队列的 x-message-ttl 是「建队列时写死」的参数，一旦改了超时时长，
     * 同名队列参数不一致会直接报 406 PRECONDITION_FAILED，必须手工删队列才能恢复。
     * 把 TTL 挂到消息上，队列参数就固定了，改 {@code sky.order.timeout-minutes} 不用再删队列。
     *
     * <p>代价（面试可聊）：per-message TTL 只有消息到达队头时才判断是否过期，
     * 若同队列里混着长短不同的 TTL，长 TTL 会挡住后面短 TTL 的。本项目所有订单
     * 超时时长一致，所以没有这个影响；真要支持多档延迟，再考虑延迟插件或按档位分队列。
     */
    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .deadLetterExchange(CANCEL_EXCHANGE)
                .deadLetterRoutingKey(CANCEL_ROUTING_KEY)
                .build();
    }

    /** 当前配置的延迟时长（毫秒），供生产者设置消息级 TTL 使用 */
    public long timeoutMillis() {
        return timeoutMinutes * 60 * 1000L;
    }

    @Bean
    public Queue orderCancelQueue() {
        return QueueBuilder.durable(CANCEL_QUEUE).build();
    }

    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(DELAY_EXCHANGE);
    }

    @Bean
    public DirectExchange orderCancelExchange() {
        return new DirectExchange(CANCEL_EXCHANGE);
    }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue()).to(orderDelayExchange()).with(DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding orderCancelBinding() {
        return BindingBuilder.bind(orderCancelQueue()).to(orderCancelExchange()).with(CANCEL_ROUTING_KEY);
    }
}
