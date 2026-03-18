package com.anthropic.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bridge.target")
public record BridgeProperties(
        String baseUrl,
        String apiKey,
        String model,
        int maxTokens) {
}
