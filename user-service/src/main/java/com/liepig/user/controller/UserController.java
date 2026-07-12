package com.liepig.user.controller;

import com.liepig.user.model.User;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class UserController {

    private final Map<Long, User> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    {
        store.put(1L, new User(1L, "Alice", "alice@example.com"));
        store.put(2L, new User(2L, "Bob", "bob@example.com"));
        idGen.set(3);
    }

    @GetMapping("/users/{id}")
    public User getById(@PathVariable Long id) {
        User user = store.get(id);
        if (user == null) {
            throw new RuntimeException("User not found: " + id);
        }
        return user;
    }

    @GetMapping("/users")
    public List<User> list() {
        return List.copyOf(store.values());
    }

    @PostMapping("/users")
    public User create(@RequestBody User user) {
        user.setId(idGen.getAndIncrement());
        store.put(user.getId(), user);
        return user;
    }

    @GetMapping("/users/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(3000);
        return "slow response from user-service";
    }
}
