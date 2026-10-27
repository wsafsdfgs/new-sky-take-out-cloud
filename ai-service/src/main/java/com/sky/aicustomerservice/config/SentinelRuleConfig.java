package com.sky.aicustomerservice.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.sky.aicustomerservice.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 限流：按 userId（来源 origin）维度限流。
 *
 * <p>为什么要有这个类：大模型按 token 计费，一个用户写脚本刷 /ai/generateStream
 * 就能把整月额度烧完。所以既要有全局保护，也要有单用户上限。
 *
 * <p>规则在代码里初始化，不依赖控制台：控制台下发的规则默认只存在内存，
 * 重启就丢；代码初始化则随应用启动生效，重启后行为可预期。
 */
@Configuration
public class SentinelRuleConfig implements RequestOriginParser {

    /** 资源名 = 请求路径（Spring MVC 适配器默认按 URL 生成资源名） */
    private static final String RES_AI_STREAM = "/ai/generateStream";

    /** 匿名来源：无 token 或 token 解析失败的请求统一归到这里 */
    private static final String ANONYMOUS = "anonymous";

    @Value("${sky.jwt.user-secret-key}")
    private String userSecretKey;

    /**
     * 从请求里解析「来源」。这里用 userId 而不是 IP：
     * 同一个出口 IP 后面可能站着整个宿舍/公司，按 IP 限流会误伤。
     */
    @Override
    public String parseOrigin(HttpServletRequest request) {
        String token = request.getParameter("token");
        if (token == null || token.isBlank()) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                token = authorization.substring(7);
            }
        }
        if (token == null || token.isBlank()) {
            return ANONYMOUS;
        }
        try {
            Claims claims = JwtUtil.parseJWT(userSecretKey, token);
            Object userId = claims.get("userId");
            return userId == null ? ANONYMOUS : String.valueOf(userId);
        } catch (Exception e) {
            return ANONYMOUS;
        }
    }

    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        // ① 全局兜底：整个对话流式接口 5 QPS，防止把服务本身打挂
        FlowRule global = new FlowRule();
        global.setResource(RES_AI_STREAM);
        global.setGrade(RuleConstant.FLOW_GRADE_QPS);
        global.setCount(5);
        global.setLimitApp("default");
        rules.add(global);

        // ② 单用户上限：userId=1 只给 1 QPS（演示用，也对应"单个账号高频刷接口"的真实场景）
        FlowRule singleUser = new FlowRule();
        singleUser.setResource(RES_AI_STREAM);
        singleUser.setGrade(RuleConstant.FLOW_GRADE_QPS);
        singleUser.setCount(1);
        singleUser.setLimitApp("1");
        rules.add(singleUser);

        FlowRuleManager.loadRules(rules);
    }
}
