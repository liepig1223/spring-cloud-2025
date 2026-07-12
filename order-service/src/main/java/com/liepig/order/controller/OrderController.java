package com.liepig.order.controller;

import com.liepig.order.feign.UserFeignClient;
import com.liepig.order.model.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class OrderController {

    private final Map<Long, Order> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    @Autowired
    private UserFeignClient userFeignClient;

    @GetMapping("/orders/{id}")
    public Order getById(@PathVariable Long id) {
        Order order = store.get(id);
        if (order == null) {
            throw new RuntimeException("Order not found: " + id);
        }
        // 通过 Feign 获取用户信息填充到订单
        try {
            order.setUser(userFeignClient.getUserById(order.getUserId()));
        } catch (Exception e) {
            order.setUser("user-service unavailable");
        }
        return order;
    }

    @PostMapping("/orders")
    public Order create(@RequestBody Order order) {
        order.setId(idGen.getAndIncrement());
        store.put(order.getId(), order);
        return order;
    }

    @GetMapping("/orders/error")
    public String error() {
        throw new RuntimeException("Simulated order-service internal error");
    }
}
