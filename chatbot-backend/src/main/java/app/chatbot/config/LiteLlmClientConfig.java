package app.chatbot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import app.chatbot.litellm.ApiClient;
import app.chatbot.litellm.api.McpApi;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(LiteLlmProperties.class)
public class LiteLlmClientConfig {

    @Bean
    public ApiClient liteLlmApiClient(LiteLlmProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getReadTimeout());

        WebClient webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        ApiClient apiClient = new ApiClient(webClient);
        apiClient.setBasePath(properties.getBaseUrl());
        if (StringUtils.hasText(properties.getAdminToken())) {
            apiClient.addDefaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getAdminToken());
        }
        return apiClient;
    }

    @Bean
    public McpApi liteLlmMcpApi(ApiClient liteLlmApiClient) {
        return new McpApi(liteLlmApiClient);
    }
}
