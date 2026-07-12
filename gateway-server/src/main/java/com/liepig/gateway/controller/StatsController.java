package com.liepig.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class StatsController {

    private final ConcurrentHashMap<String, AtomicLong> counter = new ConcurrentHashMap<>();

    /**
     * Called by a hypothetical interceptor; in a real app you'd wire into
     * the Gateway metrics. For learning we expose a simple counter.
     */
    public void record(String metric) {
        counter.computeIfAbsent(metric, k -> new AtomicLong()).incrementAndGet();
    }

    @GetMapping("/gateway/admin/stats")
    public Mono<Map<String, Object>> stats() {
        Map<String, Object> result = new ConcurrentHashMap<>();
        counter.forEach((k, v) -> result.put(k, v.get()));
        return Mono.just(result);
    }
}
