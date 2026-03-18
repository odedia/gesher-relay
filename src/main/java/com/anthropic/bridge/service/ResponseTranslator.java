package com.anthropic.bridge.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Translates OpenAI Chat Completions API responses to Anthropic Messages API responses.
 * Handles both non-streaming and streaming (SSE) translation.
 */
@Service
public class ResponseTranslator {

    private static final Logger log = LoggerFactory.getLogger(ResponseTranslator.class);
    private final ObjectMapper mapper;

    public ResponseTranslator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // --- Non-streaming translation ---

    public JsonNode toAnthropic(JsonNode openAiResponse, String requestedModel) {
        ObjectNode response = mapper.createObjectNode();

        response.put("id", generateMessageId());
        response.put("type", "message");
        response.put("role", "assistant");
        response.put("model", requestedModel);

        ArrayNode content = mapper.createArrayNode();
        JsonNode choice = openAiResponse.path("choices").path(0);
        JsonNode message = choice.path("message");

        // Text content
        String textContent = message.path("content").asText(null);
        if (textContent != null && !textContent.isEmpty()) {
            ObjectNode textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", textContent);
            content.add(textBlock);
        }

        // Tool calls
        if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
            for (JsonNode toolCall : message.get("tool_calls")) {
                ObjectNode toolUseBlock = mapper.createObjectNode();
                toolUseBlock.put("type", "tool_use");
                toolUseBlock.put("id", toolCall.path("id").asText());
                toolUseBlock.put("name", toolCall.path("function").path("name").asText());
                try {
                    JsonNode input = mapper.readTree(
                            toolCall.path("function").path("arguments").asText("{}"));
                    toolUseBlock.set("input", input);
                } catch (Exception e) {
                    toolUseBlock.set("input", mapper.createObjectNode());
                }
                content.add(toolUseBlock);
            }
        }

        if (content.isEmpty()) {
            ObjectNode textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", "");
            content.add(textBlock);
        }

        response.set("content", content);

        // Stop reason
        String finishReason = choice.path("finish_reason").asText("stop");
        response.put("stop_reason", mapFinishReason(finishReason));
        response.putNull("stop_sequence");

        // Usage
        ObjectNode usage = mapper.createObjectNode();
        usage.put("input_tokens", openAiResponse.path("usage").path("prompt_tokens").asInt(0));
        usage.put("output_tokens", openAiResponse.path("usage").path("completion_tokens").asInt(0));
        response.set("usage", usage);

        return response;
    }

    // --- Streaming translation ---

    public StreamingState createStreamingState(String requestedModel) {
        return new StreamingState(requestedModel);
    }

    static String mapFinishReason(String finishReason) {
        if (finishReason == null) return "end_turn";
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            default -> "end_turn";
        };
    }

    private static String generateMessageId() {
        return "msg_bridge_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /**
     * Maintains state across streaming chunks and translates each OpenAI chunk
     * into one or more Anthropic SSE events.
     */
    public class StreamingState {
        private final String requestedModel;
        private final String messageId;
        private boolean messageStarted = false;
        private boolean finished = false;
        private int currentBlockIndex = -1;
        private String currentBlockType = null;
        private final Map<Integer, Integer> toolCallIndexToBlockIndex = new HashMap<>();
        private int inputTokens = 0;
        private int outputTokens = 0;

        StreamingState(String requestedModel) {
            this.requestedModel = requestedModel;
            this.messageId = generateMessageId();
        }

        public List<ServerSentEvent<String>> processChunk(JsonNode chunk) {
            List<ServerSentEvent<String>> events = new ArrayList<>();

            if (!messageStarted) {
                events.add(buildMessageStartEvent());
                messageStarted = true;
            }

            // Capture usage from any chunk
            if (chunk.has("usage") && !chunk.path("usage").isNull()) {
                inputTokens = chunk.path("usage").path("prompt_tokens").asInt(inputTokens);
                outputTokens = chunk.path("usage").path("completion_tokens").asInt(outputTokens);
            }

            JsonNode choices = chunk.path("choices");
            if (choices.isEmpty() || choices.path(0).isMissingNode()) {
                return events;
            }

            JsonNode choice = choices.path(0);
            JsonNode delta = choice.path("delta");
            String finishReason = choice.path("finish_reason").isNull()
                    ? null : choice.path("finish_reason").asText(null);

            // Text content delta
            if (delta.has("content") && !delta.path("content").isNull()) {
                String text = delta.path("content").asText("");
                if (!text.isEmpty()) {
                    if (!"text".equals(currentBlockType)) {
                        if (currentBlockIndex >= 0) {
                            events.add(buildContentBlockStopEvent(currentBlockIndex));
                        }
                        currentBlockIndex++;
                        currentBlockType = "text";
                        events.add(buildTextBlockStartEvent(currentBlockIndex));
                    }
                    events.add(buildTextDeltaEvent(currentBlockIndex, text));
                }
            }

            // Tool call deltas
            if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
                for (JsonNode toolCall : delta.get("tool_calls")) {
                    int tcIndex = toolCall.path("index").asInt(0);

                    if (toolCall.has("id")) {
                        // New tool call
                        if (currentBlockIndex >= 0) {
                            events.add(buildContentBlockStopEvent(currentBlockIndex));
                        }
                        currentBlockIndex++;
                        currentBlockType = "tool_use";
                        toolCallIndexToBlockIndex.put(tcIndex, currentBlockIndex);

                        String id = toolCall.path("id").asText();
                        String name = toolCall.path("function").path("name").asText("");
                        events.add(buildToolUseBlockStartEvent(currentBlockIndex, id, name));
                    }

                    String args = toolCall.path("function").path("arguments").asText(null);
                    if (args != null && !args.isEmpty()) {
                        int blockIdx = toolCallIndexToBlockIndex.getOrDefault(tcIndex, currentBlockIndex);
                        events.add(buildInputJsonDeltaEvent(blockIdx, args));
                    }
                }
            }

            // Finish
            if (finishReason != null) {
                if (currentBlockIndex >= 0) {
                    events.add(buildContentBlockStopEvent(currentBlockIndex));
                    currentBlockIndex = -1; // prevent double-close
                }
                events.add(buildMessageDeltaEvent(mapFinishReason(finishReason)));
                events.add(buildMessageStopEvent());
                finished = true;
            }

            return events;
        }

        public List<ServerSentEvent<String>> finish() {
            if (finished) return Collections.emptyList();

            List<ServerSentEvent<String>> events = new ArrayList<>();

            if (!messageStarted) {
                events.add(buildMessageStartEvent());
            }

            // Ensure at least one content block exists
            if (currentBlockIndex < 0) {
                events.add(buildTextBlockStartEvent(0));
                events.add(buildContentBlockStopEvent(0));
            } else {
                events.add(buildContentBlockStopEvent(currentBlockIndex));
            }

            events.add(buildMessageDeltaEvent("end_turn"));
            events.add(buildMessageStopEvent());
            finished = true;
            return events;
        }

        // --- SSE event builders ---

        private ServerSentEvent<String> buildMessageStartEvent() {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "message_start");
            ObjectNode message = mapper.createObjectNode();
            message.put("id", messageId);
            message.put("type", "message");
            message.put("role", "assistant");
            message.set("content", mapper.createArrayNode());
            message.put("model", requestedModel);
            message.putNull("stop_reason");
            message.putNull("stop_sequence");
            ObjectNode usage = mapper.createObjectNode();
            usage.put("input_tokens", inputTokens);
            usage.put("output_tokens", 0);
            message.set("usage", usage);
            event.set("message", message);
            return sse("message_start", event);
        }

        private ServerSentEvent<String> buildTextBlockStartEvent(int index) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "content_block_start");
            event.put("index", index);
            ObjectNode block = mapper.createObjectNode();
            block.put("type", "text");
            block.put("text", "");
            event.set("content_block", block);
            return sse("content_block_start", event);
        }

        private ServerSentEvent<String> buildToolUseBlockStartEvent(int index, String id, String name) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "content_block_start");
            event.put("index", index);
            ObjectNode block = mapper.createObjectNode();
            block.put("type", "tool_use");
            block.put("id", id);
            block.put("name", name);
            block.set("input", mapper.createObjectNode());
            event.set("content_block", block);
            return sse("content_block_start", event);
        }

        private ServerSentEvent<String> buildTextDeltaEvent(int index, String text) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "content_block_delta");
            event.put("index", index);
            ObjectNode delta = mapper.createObjectNode();
            delta.put("type", "text_delta");
            delta.put("text", text);
            event.set("delta", delta);
            return sse("content_block_delta", event);
        }

        private ServerSentEvent<String> buildInputJsonDeltaEvent(int index, String partialJson) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "content_block_delta");
            event.put("index", index);
            ObjectNode delta = mapper.createObjectNode();
            delta.put("type", "input_json_delta");
            delta.put("partial_json", partialJson);
            event.set("delta", delta);
            return sse("content_block_delta", event);
        }

        private ServerSentEvent<String> buildContentBlockStopEvent(int index) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "content_block_stop");
            event.put("index", index);
            return sse("content_block_stop", event);
        }

        private ServerSentEvent<String> buildMessageDeltaEvent(String stopReason) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "message_delta");
            ObjectNode delta = mapper.createObjectNode();
            delta.put("stop_reason", stopReason);
            delta.putNull("stop_sequence");
            event.set("delta", delta);
            ObjectNode usage = mapper.createObjectNode();
            usage.put("output_tokens", outputTokens);
            event.set("usage", usage);
            return sse("message_delta", event);
        }

        private ServerSentEvent<String> buildMessageStopEvent() {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "message_stop");
            return sse("message_stop", event);
        }

        private ServerSentEvent<String> sse(String eventType, ObjectNode data) {
            return ServerSentEvent.<String>builder()
                    .event(eventType)
                    .data(data.toString())
                    .build();
        }
    }
}
