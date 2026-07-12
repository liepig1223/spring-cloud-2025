package com.liepig.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class FallbackController {

    @GetMapping("/fallback/order-service")
    public Mono<Map<String, Object>> orderServiceFallback() {
        return Mono.just(Map.of(
                "code", 503,
                "msg", "订单服务繁忙，请稍后重试",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
