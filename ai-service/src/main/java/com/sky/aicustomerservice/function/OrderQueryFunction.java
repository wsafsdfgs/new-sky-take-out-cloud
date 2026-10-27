package com.sky.aicustomerservice.function;

import com.sky.aicustomerservice.client.WaimaiUserClient;
import com.sky.aicustomerservice.dto.OrderDTO;
import com.sky.aicustomerservice.pojo.Request;
import com.sky.aicustomerservice.pojo.Response;
import com.sky.aicustomerservice.result.Result;
import feign.FeignException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;


/**
 * 订单查询功能类
 * 提供两个主要功能：
 * 1. 根据订单号查询订单详情
 * 2. 查询用户的所有订单列表
 *
 * 该类被 Spring AI 框架识别为工具，供大模型调用，用于获取订单相关信息。
 *
 * 远程调用方式：OpenFeign + Nacos 服务发现（服务名 sky-take-out），
 * 不再硬编码 http://localhost:8080，主项目扩容/换端口对 AI 服务透明。
 */
@Slf4j
@Component
public class OrderQueryFunction {

    @Resource
    private WaimaiUserClient waimaiUserClient;

    /**
     * 根据订单号查询订单详情的工具方法
     *
     * 工作流程：
     * 1. 从工具上下文中获取用户 token（不经过大模型，安全）
     * 2. 通过 Feign 调用主项目订单详情接口，token 放 authentication 头透传
     * 3. 主项目做鉴权 + 订单归属校验，返回 Result<OrderDTO>
     * 4. 订单状态码转成中文，连同金额/收货人/预计送达时间返回给大模型
     *
     * @param request 包含订单号的请求对象，由大模型根据用户输入自动填充
     * @param toolContext Spring AI 注入的上下文，包含用户 token 等信息
     * @return Response 包含订单详情的响应对象
     */
    @Tool(description = "实时根据订单号查询外卖订单详情，获取订单状态、金额、收货人、预计送达时间。务必调用本工具获取最新订单信息，勿凭历史或记忆作答")
    public Response queryOrder(Request request, ToolContext toolContext) {
        try {
            // 从工具上下文取用户token（不经过大模型，安全）
            String userToken = (String) toolContext.getContext().get("userToken");
            log.info("AI通过Feign查订单详情: number={}", request.getOrderNo());

            // 声明式调用：方法签名即接口契约，URL 由 Feign 根据服务名自动拼
            Result<OrderDTO> result = waimaiUserClient.innerDetailByNumber(request.getOrderNo(), userToken);

            // code=1成功，code=0失败
            if (result == null) {
                return new Response("订单查询失败或无权访问");
            }
            if (result.getCode() != 1 || result.getData() == null) {
                return new Response(result.getMsg() == null ? "订单不存在" : result.getMsg());
            }

            OrderDTO order = result.getData();
            // 订单状态码转文字（给大模型更好理解）
            String statusText = switch (order.getStatus()) {
                case 1 -> "待付款";
                case 2 -> "待接单";
                case 3 -> "已接单";
                case 4 -> "派送中";
                case 5 -> "已完成";
                case 6 -> "已取消";
                case 7 -> "退款";
                default -> "未知";
            };

            // 组装 Response 返回给大模型（大模型拿到这些字段，用自然语言回复用户）
            return new Response(
                    order.getNumber(),
                    statusText,
                    order.getAmount(),
                    order.getConsignee(),
                    order.getEstimatedDeliveryTime() == null
                            ? "未知"
                            : order.getEstimatedDeliveryTime());
        } catch (FeignException e) {
            // 主项目返回 4xx/5xx 时 Feign 抛 FeignException，这里按状态码分别处理
            log.error("主项目返回错误: status={}", e.status());
            if (e.status() == 403) {
                // 403：无权访问 或 订单不存在
                return new Response("订单不存在或无权访问该订单");
            }
            return new Response("订单查询失败，请稍后再试");
        } catch (Exception e) {
            log.error("AI查询订单失败", e);
            return new Response("订单查询失败，请稍后再试");
        }
    }

    /**
     * 查询用户所有订单列表的工具方法
     *
     * 与 queryOrder 的区别：此方法不需要订单号，直接返回当前用户的全部订单。
     * 同样通过 Feign 调用主项目，token 放 authentication 头透传。
     *
     * @param toolContext Spring AI 注入的上下文，包含用户 token 等信息
     * @return Response 包含订单列表信息的响应对象
     */
    @Tool(description = "实时查询当前用户的所有外卖订单列表，获取订单号、状态、金额。务必调用本工具获取最新订单数据")
    public Response queryMyOrders(ToolContext toolContext) {
        try {
            // 1. 从上下文取token（和queryOrder一样）
            String userToken = (String) toolContext.getContext().get("userToken");
            log.info("AI通过Feign查我的订单列表");

            // 2. 声明式调用主项目订单列表接口
            Result<Object> result = waimaiUserClient.innerMyList(userToken);

            // 3. 处理结果
            if (result == null || result.getCode() != 1 || result.getData() == null) {
                return new Response("查询订单列表失败");
            }
            // 把订单列表拼成一段文字返回给大模型
            return new Response("您的订单列表：\n" + result.getData());
        } catch (FeignException e) {
            log.error("查我的订单列表错误: status={}", e.status());
            return new Response("查询订单列表失败，请稍后再试");
        } catch (Exception e) {
            log.error("查我的订单列表失败", e);
            return new Response("查询订单列表失败，请稍后再试");
        }
    }
}