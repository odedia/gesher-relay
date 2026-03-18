package com.anthropic.bridge.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Translates Anthropic Messages API requests to OpenAI Chat Completions API requests.
 */
@Service
public class RequestTranslator {

    private static final Logger log = LoggerFactory.getLogger(RequestTranslator.class);
    private final ObjectMapper mapper;

    public RequestTranslator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode toOpenAi(JsonNode anthropic, String targetModel) {
        ObjectNode request = mapper.createObjectNode();

        request.put("model", targetModel);

        // Messages
        ArrayNode messages = mapper.createArrayNode();
        convertSystemPrompt(anthropic, messages);
        if (anthropic.has("messages")) {
            for (JsonNode msg : anthropic.get("messages")) {
                convertMessage(msg, messages);
            }
        }
        request.set("messages", messages);

        // Parameters
        if (anthropic.has("max_tokens")) {
            request.put("max_tokens", anthropic.get("max_tokens").asInt());
        }
        if (anthropic.has("temperature")) {
            request.put("temperature", anthropic.get("temperature").asDouble());
        }
        if (anthropic.has("top_p")) {
            request.put("top_p", anthropic.get("top_p").asDouble());
        }
        if (anthropic.has("stop_sequences")) {
            request.set("stop", anthropic.get("stop_sequences"));
        }

        // Streaming
        if (anthropic.path("stream").asBoolean(false)) {
            request.put("stream", true);
        }

        // Tools
        if (anthropic.has("tools") && anthropic.get("tools").size() > 0) {
            ArrayNode tools = mapper.createArrayNode();
            for (JsonNode tool : anthropic.get("tools")) {
                tools.add(convertTool(tool));
            }
            request.set("tools", tools);
        }

        // Tool choice
        if (anthropic.has("tool_choice")) {
            request.set("tool_choice", convertToolChoice(anthropic.get("tool_choice")));
        }

        log.debug("Translated request: {} messages, {} tools",
                messages.size(),
                request.has("tools") ? request.get("tools").size() : 0);

        return request;
    }

    private void convertSystemPrompt(JsonNode anthropic, ArrayNode messages) {
        if (!anthropic.has("system")) return;

        JsonNode system = anthropic.get("system");
        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");

        String text;
        if (system.isTextual()) {
            text = system.asText();
        } else if (system.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : system) {
                if ("text".equals(block.path("type").asText())) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(block.path("text").asText());
                }
            }
            text = sb.toString();
        } else {
            return;
        }

        text = stripInternalHeaders(text);
        sysMsg.put("content", text);
        messages.add(sysMsg);
    }

    private void convertMessage(JsonNode msg, ArrayNode messages) {
        String role = msg.path("role").asText();
        JsonNode content = msg.get("content");

        if ("user".equals(role)) {
            convertUserMessage(content, messages);
        } else if ("assistant".equals(role)) {
            convertAssistantMessage(content, messages);
        }
    }

    private void convertUserMessage(JsonNode content, ArrayNode messages) {
        if (content == null) return;

        if (content.isTextual()) {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", "user");
            msg.put("content", content.asText());
            messages.add(msg);
            return;
        }

        // Content is array — may contain text, image, and tool_result blocks
        ArrayNode userParts = mapper.createArrayNode();

        for (JsonNode block : content) {
            String type = block.path("type").asText();

            if ("tool_result".equals(type)) {
                ObjectNode toolMsg = mapper.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", block.path("tool_use_id").asText());
                toolMsg.put("content", extractTextContent(block.get("content")));
                messages.add(toolMsg);

            } else if ("text".equals(type)) {
                ObjectNode textPart = mapper.createObjectNode();
                textPart.put("type", "text");
                textPart.put("text", block.path("text").asText());
                userParts.add(textPart);

            } else if ("image".equals(type)) {
                JsonNode source = block.get("source");
                if (source != null && "base64".equals(source.path("type").asText())) {
                    ObjectNode imagePart = mapper.createObjectNode();
                    imagePart.put("type", "image_url");
                    ObjectNode imageUrl = mapper.createObjectNode();
                    imageUrl.put("url", "data:" + source.path("media_type").asText()
                            + ";base64," + source.path("data").asText());
                    imagePart.set("image_url", imageUrl);
                    userParts.add(imagePart);
                }
            }
            // Skip thinking, document, and other unsupported block types
        }

        if (!userParts.isEmpty()) {
            ObjectNode userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            if (userParts.size() == 1 && "text".equals(userParts.get(0).path("type").asText())) {
                userMsg.put("content", userParts.get(0).path("text").asText());
            } else {
                userMsg.set("content", userParts);
            }
            messages.add(userMsg);
        }
    }

    private void convertAssistantMessage(JsonNode content, ArrayNode messages) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "assistant");

        if (content == null) {
            msg.putNull("content");
            messages.add(msg);
            return;
        }

        if (content.isTextual()) {
            msg.put("content", content.asText());
            messages.add(msg);
            return;
        }

        // Content is array with text, tool_use, and possibly thinking blocks
        StringBuilder textContent = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();

        for (JsonNode block : content) {
            String type = block.path("type").asText();

            if ("text".equals(type)) {
                textContent.append(block.path("text").asText());
            } else if ("tool_use".equals(type)) {
                ObjectNode toolCall = mapper.createObjectNode();
                toolCall.put("id", block.path("id").asText());
                toolCall.put("type", "function");
                ObjectNode function = mapper.createObjectNode();
                function.put("name", block.path("name").asText());
                function.put("arguments", block.get("input").toString());
                toolCall.set("function", function);
                toolCalls.add(toolCall);
            }
            // Skip thinking blocks and other unsupported types
        }

        if (!textContent.isEmpty()) {
            msg.put("content", textContent.toString());
        } else {
            msg.putNull("content");
        }

        if (!toolCalls.isEmpty()) {
            msg.set("tool_calls", toolCalls);
        }

        messages.add(msg);
    }

    private JsonNode convertTool(JsonNode anthropicTool) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = mapper.createObjectNode();
        function.put("name", anthropicTool.path("name").asText());
        if (anthropicTool.has("description")) {
            function.put("description", anthropicTool.path("description").asText());
        }
        if (anthropicTool.has("input_schema")) {
            function.set("parameters", anthropicTool.get("input_schema"));
        }
        tool.set("function", function);
        return tool;
    }

    private JsonNode convertToolChoice(JsonNode anthropicChoice) {
        String type = anthropicChoice.path("type").asText();
        return switch (type) {
            case "auto" -> mapper.valueToTree("auto");
            case "any" -> mapper.valueToTree("required");
            case "none" -> mapper.valueToTree("none");
            case "tool" -> {
                ObjectNode choice = mapper.createObjectNode();
                choice.put("type", "function");
                ObjectNode function = mapper.createObjectNode();
                function.put("name", anthropicChoice.path("name").asText());
                choice.set("function", function);
                yield choice;
            }
            default -> mapper.valueToTree("auto");
        };
    }

    private String extractTextContent(JsonNode content) {
        if (content == null) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) {
                    sb.append(part.path("text").asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private String stripInternalHeaders(String text) {
        // Strip x-anthropic-billing-header lines from system prompt
        return text.replaceAll("(?m)^x-anthropic-billing-header:.*\\n?", "");
    }
}
