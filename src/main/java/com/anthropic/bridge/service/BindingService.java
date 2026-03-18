package com.anthropic.bridge.service;

import com.anthropic.bridge.model.GenAiBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class BindingService {

    private static final Logger log = LoggerFactory.getLogger(BindingService.class);
    private static final int DEFAULT_MAX_TOKENS = 8096;

    private static final String REDIS_KEY_PREFIX = "bridge:max-input-chars:";

    private final List<GenAiBinding> bindings;
    private final String appUri;
    private final ReactiveStringRedisTemplate redis;

    public BindingService(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        JsonMapper mapper = JsonMapper.builder().build();
        this.bindings = parseVcapServices(mapper);
        this.appUri = parseAppUri(mapper);
        log.info("Discovered {} GenAI binding(s), appUri={}", bindings.size(), appUri);
    }

    public List<GenAiBinding> getAllBindings() {
        return Collections.unmodifiableList(bindings);
    }

    public Optional<GenAiBinding> findByApiKey(String apiKey) {
        if (apiKey == null) return Optional.empty();
        return bindings.stream().filter(b -> b.apiKey().equals(apiKey)).findFirst();
    }

    /**
     * Find binding by API key + requested model name.
     * Matches against both the full model name and the short name.
     */
    public Optional<GenAiBinding> findByApiKeyAndModel(String apiKey, String requestedModel) {
        if (apiKey == null || requestedModel == null) return findByApiKey(apiKey);
        // Try exact match on short name or full name
        Optional<GenAiBinding> match = bindings.stream()
                .filter(b -> b.apiKey().equals(apiKey))
                .filter(b -> b.shortModelName().equals(requestedModel)
                        || b.modelName().equalsIgnoreCase(requestedModel))
                .findFirst();
        return match.isPresent() ? match : findByApiKey(apiKey);
    }

    public void setMaxInputChars(String serviceName, String modelName, int maxChars) {
        String key = REDIS_KEY_PREFIX + serviceName + ":" + modelName;
        if (maxChars <= 0) {
            redis.opsForValue().delete(key).subscribe();
            log.info("Removed input size limit for {}/{} — full passthrough", serviceName, modelName);
        } else {
            redis.opsForValue().set(key, String.valueOf(maxChars)).subscribe();
            log.info("Set input size limit for {}/{}: {} chars", serviceName, modelName, maxChars);
        }
    }

    public reactor.core.publisher.Mono<Integer> getMaxInputCharsReactive(String serviceName, String modelName) {
        return redis.opsForValue().get(REDIS_KEY_PREFIX + serviceName + ":" + modelName)
                .map(Integer::parseInt)
                .defaultIfEmpty(0)
                .onErrorReturn(0);
    }

    public String getAppUri() {
        return appUri;
    }

    private List<GenAiBinding> parseVcapServices(JsonMapper mapper) {
        String vcap = System.getenv("VCAP_SERVICES");
        if (vcap == null || vcap.isBlank()) return Collections.emptyList();

        try {
            JsonNode root = mapper.readTree(vcap);
            JsonNode genaiArray = root.get("genai");
            if (genaiArray == null || !genaiArray.isArray()) return Collections.emptyList();

            List<GenAiBinding> result = new ArrayList<>();
            for (JsonNode service : genaiArray) {
                JsonNode creds = service.get("credentials");
                String serviceName = service.path("name").asText("unknown");

                if (creds.has("model_name")) {
                    // Single-model format
                    String apiBase = creds.path("api_base").asText();
                    String apiKey = creds.path("api_key").asText();
                    String modelName = creds.path("model_name").asText();
                    int maxLen = probeMaxModelLen(mapper, apiBase, apiKey, modelName);
                    result.add(new GenAiBinding(serviceName, apiBase, apiKey, modelName,
                            jsonArrayToList(creds.path("model_capabilities")),
                            creds.path("wire_format").asText("openai"), maxLen));
                } else if (creds.has("endpoint")) {
                    // Multi-model format
                    JsonNode endpoint = creds.get("endpoint");
                    String apiBase = endpoint.path("api_base").asText();
                    String apiKey = endpoint.path("api_key").asText();
                    String configUrl = endpoint.path("config_url").asText();
                    result.addAll(discoverModels(mapper, serviceName, apiBase, apiKey, configUrl));
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse VCAP_SERVICES genai bindings", e);
            return Collections.emptyList();
        }
    }

    private List<GenAiBinding> discoverModels(JsonMapper mapper, String serviceName,
                                               String apiBase, String apiKey, String configUrl) {
        try {
            HttpResponse<String> resp = httpGet(configUrl, apiKey);
            if (resp.statusCode() != 200) {
                log.warn("Config URL {} returned {}", configUrl, resp.statusCode());
                return List.of(new GenAiBinding(serviceName, apiBase + "/openai", apiKey,
                        serviceName, List.of(), "openai", DEFAULT_MAX_TOKENS));
            }

            JsonNode config = mapper.readTree(resp.body());
            String wireFormat = config.path("wireFormat").asText("openai").toLowerCase();
            JsonNode models = config.get("advertisedModels");
            if (models == null || !models.isArray()) return Collections.emptyList();

            // Probe /v1/models once for max_model_len info
            Map<String, Integer> modelLimits = probeAllModelLens(mapper, apiBase + "/openai", apiKey);

            List<GenAiBinding> result = new ArrayList<>();
            for (JsonNode model : models) {
                String modelName = model.path("name").asText();
                List<String> caps = jsonArrayToList(model.path("capabilities"))
                        .stream().map(String::toLowerCase).toList();
                int maxLen = modelLimits.getOrDefault(modelName, DEFAULT_MAX_TOKENS);
                result.add(new GenAiBinding(serviceName,
                        apiBase + "/openai", apiKey, modelName, caps, wireFormat, maxLen));
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to discover models from {}", configUrl, e);
            return List.of(new GenAiBinding(serviceName, apiBase + "/openai", apiKey,
                    serviceName, List.of(), "openai", DEFAULT_MAX_TOKENS));
        }
    }

    /**
     * Probes GET {apiBase}/v1/models for max_model_len (vLLM extension).
     * Returns a map of modelName → maxModelLen.
     */
    private Map<String, Integer> probeAllModelLens(JsonMapper mapper, String apiBase, String apiKey) {
        Map<String, Integer> result = new HashMap<>();
        try {
            HttpResponse<String> resp = httpGet(apiBase + "/v1/models", apiKey);
            if (resp.statusCode() != 200) return result;

            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return result;

            for (JsonNode model : data) {
                String id = model.path("id").asText();
                if (model.has("max_model_len") && !model.get("max_model_len").isNull()) {
                    result.put(id, model.get("max_model_len").asInt());
                    log.info("Probed max_model_len for {}: {}", id, result.get(id));
                }
            }
        } catch (Exception e) {
            log.debug("Could not probe /v1/models for max_model_len: {}", e.getMessage());
        }
        return result;
    }

    /** Probes a single model's max_model_len for single-model bindings. */
    private int probeMaxModelLen(JsonMapper mapper, String apiBase, String apiKey, String modelName) {
        Map<String, Integer> limits = probeAllModelLens(mapper, apiBase, apiKey);
        return limits.getOrDefault(modelName, DEFAULT_MAX_TOKENS);
    }

    private String parseAppUri(JsonMapper mapper) {
        String vcapApp = System.getenv("VCAP_APPLICATION");
        if (vcapApp == null) return "http://localhost:8082";
        try {
            JsonNode app = mapper.readTree(vcapApp);
            JsonNode uris = app.get("application_uris");
            if (uris != null && uris.isArray() && !uris.isEmpty()) {
                return "https://" + uris.get(0).asText();
            }
        } catch (Exception e) { /* ignore */ }
        return "http://localhost:8082";
    }

    private HttpResponse<String> httpGet(String url, String apiKey) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private List<String> jsonArrayToList(JsonNode node) {
        if (node == null || !node.isArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) list.add(item.asText());
        return list;
    }
}
