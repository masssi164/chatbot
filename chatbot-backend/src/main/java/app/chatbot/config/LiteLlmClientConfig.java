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

@Configuration
@EnableConfigurationProperties(LiteLlmProperties.class)
public class LiteLlmClientConfig {

    @Bean
    public ApiClient liteLlmApiClient(LiteLlmProperties properties) {
        // Use generated ApiClient defaults (includes JsonNullableModule) and just set headers/base path.
        ApiClient apiClient = new ApiClient();
        apiClient.getWebClient().mutate()
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .baseUrl(properties.getBaseUrl())
                .build();
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
