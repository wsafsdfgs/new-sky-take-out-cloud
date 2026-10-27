package com.sky.aicustomerservice.client;

import com.sky.aicustomerservice.dto.OrderDTO;
import com.sky.aicustomerservice.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * 用户端远程调用客户端
 *
 * 通过 Nacos 服务发现找到主项目（服务名 sky-take-out），由 OpenFeign 完成声明式调用。
 * 不再需要手工拼接 URL，主项目端口/实例变化对调用方透明。
 *
 * 注意：多个 @FeignClient 使用同一个 name 时必须指定不同的 contextId，
 * 否则 Spring 会报 "duplicate Feign client name" 并启动失败。
 */
@FeignClient(name = "sky-take-out", contextId = "waimaiUser")
public interface WaimaiUserClient {

    /**
     * 内部登录：用 userId 换取用户 token
     */
    @PostMapping("/user/user/innerLogin")
    Result<Map<String, Object>> innerLogin(@RequestBody Map<String, Object> body);

    /**
     * 根据订单号查询订单详情
     * 用户 token 通过 authentication 头透传给主项目
     */
    @GetMapping("/user/order/innerDetailByNumber/{number}")
    Result<OrderDTO> innerDetailByNumber(@PathVariable("number") String number,
                                         @RequestHeader("authentication") String userToken);

    /**
     * 查询当前用户的订单列表
     */
    @GetMapping("/user/order/innerMyList")
    Result<Object> innerMyList(@RequestHeader("authentication") String userToken);
}