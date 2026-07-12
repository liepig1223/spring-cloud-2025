package com.liepig.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Component
public class RequestHeaderGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String gatewayId = "gateway-" + UUID.randomUUID().toString().substring(0, 6);
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header("X-Gateway-Id", gatewayId)
                        .header("X-Gateway-Timestamp", Instant.now().toString()))
                .build();
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
