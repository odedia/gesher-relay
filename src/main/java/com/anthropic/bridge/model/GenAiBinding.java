package com.anthropic.bridge.model;

import java.util.List;

public record GenAiBinding(
        String serviceName,
        String apiBase,
        String apiKey,
        String modelName,
        List<String> capabilities,
        String wireFormat,
        int maxModelLen) {

    public String shortModelName() {
        String name = modelName.contains("/") ? modelName.substring(modelName.indexOf('/') + 1) : modelName;
        return name.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
    }
}
