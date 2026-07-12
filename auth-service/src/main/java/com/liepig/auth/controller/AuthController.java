package com.liepig.auth.controller;

import com.liepig.auth.model.LoginRequest;
import com.liepig.auth.model.LoginResponse;
import com.liepig.auth.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class AuthController {

    // 模拟用户数据库
    private static final Map<String, String> USERS = Map.of(
            "admin", "123456",
            "user", "123456"
    );

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        String expectedPassword = USERS.get(request.getUsername());
        if (expectedPassword == null || !expectedPassword.equals(request.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid credentials"));
        }
        List<String> roles = "admin".equals(request.getUsername())
                ? List.of("admin", "user")
                : List.of("user");
        String token = jwtUtil.generateToken(
                request.getUsername().hashCode() & 0x7FFFFFFFL,
                request.getUsername(),
                roles);
        return ResponseEntity.ok(new LoginResponse(token, 3600));
    }

    @PostMapping("/auth/verify")
    public ResponseEntity<?> verify(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = extractToken(authHeader);
            Claims claims = jwtUtil.validateToken(token);
            if (jwtUtil.isBlacklisted(token)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("valid", false, "reason", "token blacklisted"));
            }
            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "userId", claims.getSubject(),
                    "username", claims.get("username", String.class),
                    "roles", claims.get("roles", List.class)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("valid", false, "reason", e.getMessage()));
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = extractToken(authHeader);
            jwtUtil.blacklist(token);
            return ResponseEntity.ok(Map.of("message", "logged out"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid Authorization header");
        }
        return authHeader.substring(7);
    }
}
