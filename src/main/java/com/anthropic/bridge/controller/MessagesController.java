package com.anthropic.bridge.controller;

import com.anthropic.bridge.model.GenAiBinding;
import com.anthropic.bridge.service.BindingService;
import com.anthropic.bridge.service.ProxyService;
import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Optional;

@RestController
public class MessagesController {

    private final ProxyService proxyService;
    private final BindingService bindingService;

    public MessagesController(ProxyService proxyService, BindingService bindingService) {
        this.proxyService = proxyService;
        this.bindingService = bindingService;
    }

    @PostMapping("/v1/messages")
    public Mono<ResponseEntity<?>> messages(
            @RequestBody JsonNode request,
            @RequestHeader(value = "x-api-key", required = false) String apiKey) {

        boolean stream = request.path("stream").asBoolean(false);
        String requestedModel = request.path("model").asText(null);
        Optional<GenAiBinding> binding = bindingService.findByApiKeyAndModel(apiKey, requestedModel);

        if (stream) {
            var flux = binding.isPresent()
                    ? proxyService.proxyStream(request, binding.get())
                    : proxyService.proxyStream(request);
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(flux));
        }

        var mono = binding.isPresent()
                ? proxyService.proxy(request, binding.get())
                : proxyService.proxy(request);
        return mono.map(body -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body((Object) body));
    }
}
