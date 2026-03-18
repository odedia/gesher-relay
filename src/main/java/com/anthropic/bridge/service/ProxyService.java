package com.anthropic.bridge.service;

import com.anthropic.bridge.config.BridgeProperties;
import com.anthropic.bridge.model.GenAiBinding;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    private final WebClient defaultClient;
    private final ConcurrentHashMap<String, WebClient> clientCache = new ConcurrentHashMap<>();
    private final RequestTranslator requestTranslator;
    private final ResponseTranslator responseTranslator;
    private final BridgeProperties properties;
    private final BindingService bindingService;
    private final ObjectMapper mapper;

    public ProxyService(BridgeProperties properties,
                        RequestTranslator requestTranslator,
                        ResponseTranslator responseTranslator,
                        BindingService bindingService,
                        ObjectMapper mapper) {
        this.properties = properties;
        this.requestTranslator = requestTranslator;
        this.responseTranslator = responseTranslator;
        this.bindingService = bindingService;
        this.mapper = mapper;

        this.defaultClient = buildClient(properties.baseUrl(), properties.apiKey());

        log.info("Anthropic Bridge initialized — default target: {}, model: {}",
                properties.baseUrl(), properties.model());
    }

    /** Proxy using static config (local dev) */
    public Mono<JsonNode> proxy(JsonNode anthropicRequest) {
        String requestedModel = anthropicRequest.path("model").asText("unknown");
        JsonNode openAiRequest = requestTranslator.toOpenAi(anthropicRequest, properties.model());
        log.debug("Non-streaming proxy: {} → {}", requestedModel, properties.model());
        return doProxy(defaultClient, openAiRequest, requestedModel)
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("Upstream error: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
                    return Mono.just(buildErrorResponse(e));
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("Bridge error", e);
                    return Mono.just(buildErrorResponse(e));
                });
    }

    public Flux<ServerSentEvent<String>> proxyStream(JsonNode anthropicRequest) {
        String requestedModel = anthropicRequest.path("model").asText("unknown");
        JsonNode openAiRequest = requestTranslator.toOpenAi(anthropicRequest, properties.model());
        log.debug("Streaming proxy: {} → {}", requestedModel, properties.model());
        return doProxyStream(defaultClient, openAiRequest, requestedModel)
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("Upstream streaming error: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
                    return Flux.just(buildErrorEvent(e));
                })
                .onErrorResume(e -> {
                    log.error("Streaming error", e);
                    return Flux.just(buildErrorEvent(e));
                });
    }

    /** Proxy using a dynamic GenAI binding (CF deployment) */
    public Mono<JsonNode> proxy(JsonNode anthropicRequest, GenAiBinding binding) {
        String requestedModel = anthropicRequest.path("model").asText("unknown");
        WebClient client = getOrCreateClient(binding);
        JsonNode openAiRequest = requestTranslator.toOpenAi(anthropicRequest, binding.modelName());
        log.debug("Non-streaming proxy: {} → {}", requestedModel, binding.modelName());
        return doProxy(client, openAiRequest, requestedModel)
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("Upstream error: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
                    return Mono.just(buildErrorResponse(e));
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("Bridge error", e);
                    return Mono.just(buildErrorResponse(e));
                });
    }

    public Flux<ServerSentEvent<String>> proxyStream(JsonNode anthropicRequest, GenAiBinding binding) {
        String requestedModel = anthropicRequest.path("model").asText("unknown");
        WebClient client = getOrCreateClient(binding);
        JsonNode openAiRequest = requestTranslator.toOpenAi(anthropicRequest, binding.modelName());
        log.debug("Streaming proxy: {} → {}", requestedModel, binding.modelName());
        return doProxyStream(client, openAiRequest, requestedModel)
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("Upstream streaming error: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
                    return Flux.just(buildErrorEvent(e));
                })
                .onErrorResume(e -> {
                    log.error("Streaming error", e);
                    return Flux.just(buildErrorEvent(e));
                });
    }

    private Mono<JsonNode> doProxy(WebClient client, JsonNode openAiRequest, String requestedModel) {
        return client.post()
                .uri("/v1/chat/completions")
                .bodyValue(openAiRequest)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> responseTranslator.toAnthropic(response, requestedModel));
    }

    private Flux<ServerSentEvent<String>> doProxyStream(WebClient client, JsonNode openAiRequest, String requestedModel) {
        ResponseTranslator.StreamingState state = responseTranslator.createStreamingState(requestedModel);

        return client.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(openAiRequest)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .concatMap(event -> {
                    String data = event.data();
                    if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
                        return Flux.fromIterable(state.finish());
                    }
                    try {
                        JsonNode chunk = mapper.readTree(data);
                        return Flux.fromIterable(state.processChunk(chunk));
                    } catch (Exception e) {
                        log.warn("Failed to parse SSE chunk: {}", data, e);
                        return Flux.empty();
                    }
                });
    }

    private WebClient getOrCreateClient(GenAiBinding binding) {
        return clientCache.computeIfAbsent(binding.apiBase(),
                base -> buildClient(base, binding.apiKey()));
    }

    private WebClient buildClient(String baseUrl, String apiKey) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMinutes(5));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    private JsonNode buildErrorResponse(WebClientResponseException e) {
        ObjectNode response = mapper.createObjectNode();
        response.put("type", "error");
        ObjectNode error = mapper.createObjectNode();
        int status = e.getStatusCode().value();
        error.put("type", status == 429 ? "rate_limit_error"
                : status == 401 ? "authentication_error" : "api_error");
        error.put("message", "Upstream error: " + e.getResponseBodyAsString());
        response.set("error", error);
        return response;
    }

    private JsonNode buildErrorResponse(Exception e) {
        ObjectNode response = mapper.createObjectNode();
        response.put("type", "error");
        ObjectNode error = mapper.createObjectNode();
        error.put("type", "api_error");
        error.put("message", "Bridge error: " + e.getMessage());
        response.set("error", error);
        return response;
    }

    private ServerSentEvent<String> buildErrorEvent(Throwable e) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "error");
        ObjectNode error = mapper.createObjectNode();
        error.put("type", "api_error");
        error.put("message", "Bridge streaming error: " + e.getMessage());
        event.set("error", error);
        return ServerSentEvent.<String>builder()
                .event("error")
                .data(event.toString())
                .build();
    }
}
