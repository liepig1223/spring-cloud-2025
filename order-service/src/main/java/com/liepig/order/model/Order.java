package com.liepig.order.model;

public class Order {
    private Long id;
    private Long userId;
    private String product;
    private Object user; // 来自 user-service 的用户信息

    public Order() {}

    public Order(Long id, Long userId, String product) {
        this.id = id;
        this.userId = userId;
        this.product = product;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public Object getUser() { return user; }
    public void setUser(Object user) { this.user = user; }
}
