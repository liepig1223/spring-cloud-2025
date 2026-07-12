package com.liepig.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);
    private static final String SECRET = "spring-cloud-gateway-learning-2025-secret-key-32bytes!";
    private static final Set<String> WHITELIST_PATHS = Set.of(
            "/api/auth/auth/login", "/api/auth/auth/logout"
    );
    // Public paths that don't need auth
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/public"
    );

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip whitelisted paths and public paths
        if (WHITELIST_PATHS.contains(path)) {
            return chain.filter(exchange);
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return chain.filter(exchange);
            }
        }

        // Extract token
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);

        // Validate JWT signature + expiry
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return unauthorized(exchange, "Invalid token: " + e.getMessage());
        }

        // Check blacklist (Redis)
        return redisTemplate.hasKey("blacklist:token:" + token)
                .flatMap(isBlacklisted -> {
                    if (Boolean.TRUE.equals(isBlacklisted)) {
                        return unauthorized(exchange, "Token has been revoked");
                    }

                    // Relay user info to downstream
                    String userId = claims.getSubject();
                    String username = claims.get("username", String.class);
                    @SuppressWarnings("unchecked")
                    List<String> roles = claims.get("roles", List.class);

                    ServerWebExchange mutated = exchange.mutate()
                            .request(r -> r.header("X-User-Id", userId)
                                    .header("X-User-Name", username != null ? username : "")
                                    .header("X-User-Roles", String.join(",", roles != null ? roles : List.of())))
                            .build();

                    log.debug("Auth OK: userId={}, path={}", userId, path);
                    return chain.filter(mutated);
                });
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        log.warn("Auth FAIL: {}", message);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
        byte[] body = ("{\"code\":401,\"msg\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -100; // Before all other filters
    }
}
