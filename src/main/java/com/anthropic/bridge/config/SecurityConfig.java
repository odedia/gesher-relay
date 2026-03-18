package com.anthropic.bridge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private final boolean ssoAvailable;

    public SecurityConfig() {
        this.ssoAvailable = detectSsoBinding();
    }

    @Bean
    ReactiveClientRegistrationRepository clientRegistrationRepository() {
        if (ssoAvailable) {
            ClientRegistration reg = buildSsoRegistration();
            if (reg != null) return new InMemoryReactiveClientRegistrationRepository(reg);
        }
        // Dummy registration so Spring Security doesn't fail on startup
        return new InMemoryReactiveClientRegistrationRepository(
                ClientRegistration.withRegistrationId("none")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .clientId("none")
                        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                        .authorizationUri("http://localhost/none")
                        .tokenUri("http://localhost/none")
                        .build());
    }

    /** API endpoints: no SSO, permit all */
    @Bean
    @Order(1)
    SecurityWebFilterChain apiSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher("/v1/**"))
                .authorizeExchange(e -> e.anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }

    /** Web UI security */
    @Bean
    @Order(2)
    SecurityWebFilterChain webSecurityFilterChain(ServerHttpSecurity http) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable);

        if (ssoAvailable) {
            log.info("SSO enabled — web UI requires authentication");
            http.authorizeExchange(e -> e
                            // Public paths
                            .pathMatchers(
                                    "/", "/index.html",
                                    "/login", "/login**",
                                    "/oauth2/**",
                                    "/login/oauth2/**",
                                    "/favicon.ico",
                                    "/actuator/health",
                                    "/auth/status", "/auth/provider",
                                    "/api/auth-status"
                            ).permitAll()
                            .anyExchange().authenticated())
                    .oauth2Login(oauth2 -> oauth2
                            .loginPage("/login")
                            .authenticationSuccessHandler(
                                    new RedirectServerAuthenticationSuccessHandler("/dashboard")))
                    .logout(logout -> logout
                            .logoutSuccessHandler(new org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler() {{
                                setLogoutSuccessUrl(java.net.URI.create("/"));
                            }}));
        } else {
            log.info("SSO not configured — web UI is open");
            http.authorizeExchange(e -> e.anyExchange().permitAll());
        }

        return http.build();
    }

    private static boolean detectSsoBinding() {
        String vcap = System.getenv("VCAP_SERVICES");
        return vcap != null && vcap.contains("p-identity");
    }

    private static ClientRegistration buildSsoRegistration() {
        String vcap = System.getenv("VCAP_SERVICES");
        if (vcap == null) return null;

        try {
            JsonMapper mapper = JsonMapper.builder().build();
            JsonNode root = mapper.readTree(vcap);
            JsonNode identityArray = root.get("p-identity");
            if (identityArray == null || !identityArray.isArray() || identityArray.isEmpty()) return null;

            JsonNode credentials = identityArray.get(0).get("credentials");
            String clientId = credentials.get("client_id").asText();
            String clientSecret = credentials.get("client_secret").asText();
            String authDomain = credentials.get("auth_domain").asText();

            log.info("Configuring SSO: authDomain={}", authDomain);

            return ClientRegistration.withRegistrationId("sso")
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri(authDomain + "/oauth/authorize")
                    .tokenUri(authDomain + "/oauth/token")
                    .userInfoUri(authDomain + "/userinfo")
                    .jwkSetUri(authDomain + "/token_keys")
                    .userNameAttributeName("sub")
                    .clientName("SSO")
                    .build();
        } catch (Exception e) {
            log.error("Failed to configure SSO from VCAP_SERVICES", e);
            return null;
        }
    }
}
