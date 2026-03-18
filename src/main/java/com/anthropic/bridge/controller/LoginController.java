package com.anthropic.bridge.controller;

import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.reactive.result.view.RedirectView;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Discovers the available OAuth2 provider and redirects to it.
 * Mirrors the pattern from the reference Tanzu app.
 */
@Controller
public class LoginController {

    private final ReactiveClientRegistrationRepository clientRegistrationRepository;

    public LoginController(ReactiveClientRegistrationRepository clientRegistrationRepository) {
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @GetMapping("/login")
    public Mono<RedirectView> login() {
        String[] candidates = {"p-identity", "uaa", "sso", "p-identity-sso", "cf", "okta"};

        if (clientRegistrationRepository instanceof InMemoryReactiveClientRegistrationRepository repo) {
            for (String id : candidates) {
                try {
                    var reg = repo.findByRegistrationId(id).block();
                    if (reg != null) {
                        return Mono.just(new RedirectView("/oauth2/authorization/" + reg.getRegistrationId()));
                    }
                } catch (Exception ignored) {}
            }
        }

        // No SSO provider found — go to splash
        return Mono.just(new RedirectView("/"));
    }
}
