package com.charitha.transactions.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${security.observability.public-prometheus:false}") boolean publicPrometheus) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(
                            "/actuator/health",
                            "/actuator/health/**",
                            "/actuator/info",
                            "/error"
                    ).permitAll();

                    if (publicPrometheus) {
                        authorize.requestMatchers("/actuator/prometheus").permitAll();
                    } else {
                        authorize.requestMatchers("/actuator/prometheus")
                                .hasAuthority("SCOPE_ops:read");
                    }

                    authorize.requestMatchers("/actuator/metrics", "/actuator/metrics/**")
                            .hasAuthority("SCOPE_ops:read");
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/operations/dlt/*/replay")
                            .hasAuthority("SCOPE_ops:write");
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/operations/dlt/**")
                            .hasAuthority("SCOPE_ops:read");
                    authorize.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${security.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "JWT is missing the required audience",
                        null
                ));
        OAuth2TokenValidator<Jwt> subjectValidator = jwt -> {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank() && subject.length() <= 120) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "JWT subject must be present and no longer than 120 characters",
                    null
            ));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator,
                subjectValidator
        ));
        return decoder;
    }
}
