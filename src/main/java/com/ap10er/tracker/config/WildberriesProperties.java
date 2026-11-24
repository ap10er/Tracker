package com.ap10er.tracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wildberries")
public class WildberriesProperties {

    private String cartUrl = "https://www.wildberries.by/lk/basket";
    private String storageStatePath = "./wildberries-storage-state.json";
    private boolean headless = true;
    private long pageTimeoutMs = 30000;

    public String getCartUrl() {
        return cartUrl;
    }

    public void setCartUrl(String cartUrl) {
        this.cartUrl = cartUrl;
    }

    public String getStorageStatePath() {
        return storageStatePath;
    }

    public void setStorageStatePath(String storageStatePath) {
        this.storageStatePath = storageStatePath;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public long getPageTimeoutMs() {
        return pageTimeoutMs;
    }

    public void setPageTimeoutMs(long pageTimeoutMs) {
        this.pageTimeoutMs = pageTimeoutMs;
    }
}
