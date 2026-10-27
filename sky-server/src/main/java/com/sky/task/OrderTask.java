package com.sky.task;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class OrderTask {
    @Autowired
    private OrderMapper orderMapper;

    //处理超时订单的方法
    //@Scheduled(cron = "0 * * * * ?")//每分钟触发一次
    /**
     * 已被 MQ 延迟消息取代（见 OrderTimeoutProducer / OrderTimeoutConsumer）：
     * 下单时投递一条延迟消息，到点由消费者取消，不再每分钟扫表。
     *
     * <p>方法保留但停用，作为兜底：万一 MQ 故障导致消息丢失，
     * 需要时把上面的 @Scheduled 注释放开即可恢复轮询。
     * 两边都开也不会重复取消——消费者和这里都只处理"待支付"状态。
     */
    public void processTimeoutOrder() {
        log.info("处理超时订单(兜底轮询):{}", LocalDateTime.now());

        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);

        //select * from orders where status = ? and order_time < ()
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, time);

        if (ordersList != null && ordersList.size() > 0) {
            for (Orders orders : ordersList) {
                orders.setStatus(Orders.CANCELLED);
                orders.setCancelReason("超时未支付");
                orders.setCancelTime(LocalDateTime.now());
                orderMapper.update(orders);
            }
        }
    }

    //处理一直处于派送中的订单
    @Scheduled(cron = "0 0 1 * * ?")//每天凌晨1点触发
    public void processDeliveryOrder() {
        log.info("处理一直处于派送中的订单:{}", LocalDateTime.now());

        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);

        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, time);

        if (ordersList != null && ordersList.size() > 0) {
            for (Orders orders : ordersList) {
                orders.setStatus(Orders.COMPLETED);
                orderMapper.update(orders);
            }
        }
    }
}
