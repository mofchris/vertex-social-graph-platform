package com.vertex.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The HTTP clients the gateway uses to forward requests downstream, built on the JDK
 * {@link HttpClient} so every method (incl. PATCH) is supported.
 *
 * <ul>
 *   <li>{@code proxyRestClient} — bounded connect and read timeouts, so a slow or dead downstream
 *       can't pin a gateway thread indefinitely;</li>
 *   <li>{@code streamingProxyRestClient} — same connect timeout but no read timeout, used only for
 *       requests that ask for {@code text/event-stream}, whose body is open-ended by design.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class ProxyConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @Primary
    RestClient proxyRestClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    RestClient streamingProxyRestClient() {
        return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient())).build();
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER) // a gateway forwards, it doesn't chase
                .build();
    }
}
