package com.agenthub.ai.base.config;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.base.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * WebFlux JWT 认证过滤器
 *
 * <p>替代 Spring MVC 的 {@code HandlerInterceptor}，在 WebFlux 模式下拦截所有请求进行 JWT 校验。</p>
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>检查请求路径是否在白名单 {@link #EXCLUDE_PATHS} 中，若在则直接放行</li>
 *   <li>从请求头中提取 JWT token（支持 Bearer 前缀）</li>
 *   <li>解析 token 获取 userId（当前为硬编码测试值）</li>
 *   <li>将 userId 设置到线程上下文 {@link BaseContext}，请求完成后清除</li>
 * </ol>
 *
 * <p>注意：当前 JWT 校验为简化实现，硬编码 userId 为 {@code 1522022}，
 * 未真正验证 token 有效性。生产环境需接入实际 JWT 验证逻辑。</p>
 *
 * @see WebFilter
 * @see BaseContext
 */
@Configuration
@Slf4j
public class WebFluxConfig implements WebFilter {

    /** JWT 配置属性，用于获取 token header 名称 */
    @Autowired
    private JwtProperties jwtProperties;

    /** 不需要 JWT 认证的路径白名单 */
    private static final List<String> EXCLUDE_PATHS = List.of(
            ApplicationConstant.API_VERSION + "/user/login",
            ApplicationConstant.API_VERSION + "/user/register",
            ApplicationConstant.API_VERSION + "/chat2",      // 流式对话 + 模型列表
            ApplicationConstant.API_VERSION + "/stats",
            ApplicationConstant.API_VERSION + "/model/config",
            "/doc.html", "/webjars/", "/swagger-resources/", "/v3/api-docs/"
    );

    /**
     * WebFlux 过滤器主方法
     *
     * <p>对每个进入的请求执行 JWT 认证检查。白名单路径直接放行，
     * 其余路径需携带有效 token，否则返回 401。</p>
     *
     * @param exchange 请求/响应交换对象
     * @param chain    过滤器链
     * @return Mono<Void> 响应式结果
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 白名单路径直接放行，不进行 JWT 校验
        for (String exclude : EXCLUDE_PATHS) {
            if (path.startsWith(exclude) || path.equals(exclude)) {
                return chain.filter(exchange);
            }
        }

        // 从请求头获取 JWT token
        String token = exchange.getRequest().getHeaders()
                .getFirst(jwtProperties.getUserTokenName());

        // 去除 Bearer 前缀
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        try {
            log.info("jwt校验:{}", token);

            // TODO: 生产环境应替换为真实 JWT 解析逻辑，如 JwtUtil.parseToken(token)
            // 当前为测试用途，硬编码 userId
            Long userId = Long.valueOf("1522022");
            log.info("当前用户的id：{}", userId);

            // 将 userId 设置到 ThreadLocal 上下文，供后续业务逻辑使用
            BaseContext.setCurrentId(userId);

            // 放行请求，请求完成后清除 ThreadLocal 防止内存泄漏
            return chain.filter(exchange)
                    .doFinally(signalType -> BaseContext.removeCurrentId());
        } catch (ExpiredJwtException ex) {
            log.warn("JWT token 已过期: {}", ex.getMessage());
            exchange.getResponse().setRawStatusCode(401);
            return Mono.empty();
        } catch (Exception ex) {
            log.warn("JWT token 解析失败: {}", ex.getMessage());
            exchange.getResponse().setRawStatusCode(401);
            return Mono.empty();
        }
    }
}
