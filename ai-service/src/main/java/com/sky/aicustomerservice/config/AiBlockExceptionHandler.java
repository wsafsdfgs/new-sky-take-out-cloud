package com.sky.aicustomerservice.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 被限流时的返回体。
 *
 * <p>默认实现返回 429 + 一行纯文本 "Blocked by Sentinel (flow limiting)"，
 * 前端（SSE / fetch）拿到会当成异常处理。这里统一返回和业务一致的 JSON 结构，
 * 让前端能直接 toast 提示，而不是白屏或报错。
 *
 * <p>注意：这里**必须用 getOutputStream() 写字节**，不能用 getWriter()。
 * SSE 接口（produces=text/event-stream）上 Spring 已经取过输出流，
 * 同一个响应里再拿 Writer 会抛 IllegalStateException。
 * 同理，如果响应已经提交（已经开始往外推数据），就什么都不能再写，直接返回。
 */
@Component
public class AiBlockExceptionHandler implements BlockExceptionHandler {

    private static final String BODY = "{\"code\":0,\"msg\":\"你问得太快了，歇一秒再试～\",\"data\":null}";

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       String resourceName, BlockException e) throws Exception {
        // 已经开始写响应（例如 SSE 推了半截）→ 无法再改状态码，静默放弃
        if (response.isCommitted()) {
            return;
        }
        try {
            // 清掉可能已写入的缓冲，避免和后续写操作打架
            response.reset();
        } catch (IllegalStateException ignored) {
            // 已经被取过 Writer / OutputStream 而无法 reset，忽略即可
        }

        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
