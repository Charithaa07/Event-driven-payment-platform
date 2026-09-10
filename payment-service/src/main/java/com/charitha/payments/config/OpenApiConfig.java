package com.charitha.payments.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    public static final String BEARER_AUTH_SCHEME = "bearerAuth";

    @Bean
    OpenAPI paymentServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event-Driven Payment Platform API")
                        .version("v1")
                        .description("""
                                Authenticated payment command API backed by PostgreSQL, Redis, Kafka, and a transactional outbox.
                                Payment creation requires the `payments:write` scope, payment retrieval requires `payments:read`,
                                and operational metrics require `ops:read`. Customer identity is derived from the JWT `sub` claim.
                                """)
                        .contact(new Contact().name("Charitha")))
                .components(new Components().addSecuritySchemes(
                        BEARER_AUTH_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("OAuth2 bearer access token. Endpoint descriptions list the required scopes.")
                ));
    }
}
