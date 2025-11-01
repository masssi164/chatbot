package app.chatbot.n8n.config;

import app.chatbot.n8n.api.ExecutionApi;
import app.chatbot.n8n.api.TagsApi;
import app.chatbot.n8n.api.WorkflowApi;
import app.chatbot.n8n.invoker.ApiClient;
import app.chatbot.n8n.invoker.auth.ApiKeyAuth;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(N8nProperties.class)
public class N8nClientConfig {

    @Bean
    public ApiClient n8nApiClient(RestTemplateBuilder restTemplateBuilder, N8nProperties properties) {
        Duration connectTimeout = properties.getConnectTimeout();
        Duration readTimeout = properties.getReadTimeout();

        var restTemplate = restTemplateBuilder
                .requestFactory(() -> {
                    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) connectTimeout.toMillis());
                    factory.setReadTimeout((int) readTimeout.toMillis());
                    return factory;
                })
                .build();

        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(properties.getBaseUrl());

        ApiKeyAuth apiKeyAuth = (ApiKeyAuth) apiClient.getAuthentication("ApiKeyAuth");
        Assert.notNull(apiKeyAuth, "ApiKeyAuth must be configured");
        apiKeyAuth.setApiKey(properties.getApiKey());

        return apiClient;
    }

    @Bean
    public WorkflowApi workflowApi(ApiClient apiClient) {
        return new WorkflowApi(apiClient);
    }

    @Bean
    public ExecutionApi executionApi(ApiClient apiClient) {
        return new ExecutionApi(apiClient);
    }

    @Bean
    public TagsApi tagsApi(ApiClient apiClient) {
        return new TagsApi(apiClient);
    }
}
