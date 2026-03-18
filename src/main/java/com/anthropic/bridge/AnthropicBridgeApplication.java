package com.anthropic.bridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AnthropicBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnthropicBridgeApplication.class, args);
    }
}
