package com.sky.aicustomerservice.client;

import com.sky.aicustomerservice.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 商家端远程调用客户端
 *
 * 商家端 token 通过 header「token」透传（主项目 admin 拦截器读取 admin-token-name=token），
 * 与用户端的 authentication 头不同，这是两套独立的鉴权体系。
 */
@FeignClient(name = "sky-take-out", contextId = "waimaiAdmin")
public interface WaimaiAdminClient {

    /**
     * 员工登录：账号密码换 admin token
     */
    @PostMapping("/admin/employee/login")
    Result<Map<String, Object>> employeeLogin(@RequestBody Map<String, String> body);

    /**
     * 今日营业数据（营业额、订单量、客单价等）
     */
    @GetMapping("/admin/workspace/businessData")
    Result<Object> businessData(@RequestHeader("token") String adminToken);

    /**
     * 订单管理概况（各状态订单数量）
     */
    @GetMapping("/admin/workspace/overviewOrders")
    Result<Object> overviewOrders(@RequestHeader("token") String adminToken);

    /**
     * 销量 Top10
     */
    @GetMapping("/admin/report/top10")
    Result<Object> top10(@RequestParam("begin") String begin,
                         @RequestParam("end") String end,
                         @RequestHeader("token") String adminToken);

    /**
     * 店铺营业状态
     */
    @GetMapping("/admin/shop/status")
    Result<Object> shopStatus(@RequestHeader("token") String adminToken);
}