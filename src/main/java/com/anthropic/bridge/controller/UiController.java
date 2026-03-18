package com.anthropic.bridge.controller;

import com.anthropic.bridge.model.GenAiBinding;
import com.anthropic.bridge.service.BindingService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class UiController {

    private final BindingService bindingService;
    private final boolean ssoEnabled;

    public UiController(BindingService bindingService) {
        this.bindingService = bindingService;
        String vcap = System.getenv("VCAP_SERVICES");
        this.ssoEnabled = (vcap != null && vcap.contains("p-identity"));
    }

    @GetMapping("/api/bindings")
    public Mono<List<Map<String, Object>>> bindings() {
        String appUri = bindingService.getAppUri();
        List<GenAiBinding> chatBindings = bindingService.getAllBindings().stream()
                .filter(b -> b.capabilities().contains("chat"))
                .toList();

        // Fetch all caps from Redis reactively, then assemble the response
        return reactor.core.publisher.Flux.fromIterable(chatBindings)
                .flatMapSequential(b -> bindingService.getMaxInputCharsReactive(b.serviceName(), b.modelName())
                        .map(limit -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("serviceName", b.serviceName());
                            m.put("modelName", b.modelName());
                            m.put("shortName", b.shortModelName());
                            m.put("capabilities", b.capabilities());
                            m.put("wireFormat", b.wireFormat());
                            m.put("maxInputChars", limit);
                            m.put("copySnippet", buildCopySnippet(b, appUri));
                            return m;
                        }))
                .collectList();
    }

    @GetMapping("/api/auth-status")
    public Mono<Map<String, Object>> authStatus(@AuthenticationPrincipal OAuth2User user) {
        String userName = "";
        boolean authenticated = false;
        if (user != null) {
            authenticated = true;
            userName = user.getAttribute("user_name");
            if (userName == null) userName = user.getAttribute("email");
            if (userName == null) userName = user.getName();
        }
        return Mono.just(Map.of(
                "ssoEnabled", ssoEnabled,
                "authenticated", authenticated,
                "user", userName != null ? userName : ""));
    }

    @PostMapping("/api/bindings/max-input-chars")
    public Mono<Map<String, Object>> setMaxInputChars(@RequestBody Map<String, String> body) {
        String serviceName = body.getOrDefault("serviceName", "");
        String modelName = body.getOrDefault("modelName", "");
        int value = Integer.parseInt(body.getOrDefault("maxInputChars", "0"));
        bindingService.setMaxInputChars(serviceName, modelName, value);
        return Mono.just(Map.of("serviceName", serviceName, "modelName", modelName, "maxInputChars", value));
    }

    @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ClassPathResource> dashboard() {
        return Mono.just(new ClassPathResource("static/dashboard.html"));
    }

    private String buildCopySnippet(GenAiBinding b, String appUri) {
        return String.join("\n",
                "export ANTHROPIC_BASE_URL=" + appUri,
                "export ANTHROPIC_API_KEY=" + b.apiKey(),
                "export ANTHROPIC_MODEL=" + b.shortModelName(),
                "claude");
    }
}
