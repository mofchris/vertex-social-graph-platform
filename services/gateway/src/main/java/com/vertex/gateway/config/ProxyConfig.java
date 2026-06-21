package com.vertex.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The HTTP client the gateway uses to forward requests downstream. Built on the JDK
 * {@link HttpClient} so every method (incl. PATCH) is supported, with bounded connect/read timeouts
 * so a slow or dead downstream can't pin a gateway thread indefinitely.
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class ProxyConfig {

    @Bean
    RestClient proxyRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER) // a gateway forwards, it doesn't chase
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }
}
