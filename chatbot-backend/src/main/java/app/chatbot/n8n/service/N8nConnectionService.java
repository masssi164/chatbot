package app.chatbot.n8n.service;

import java.net.URI;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import app.chatbot.connection.ConnectionVerificationTemplate;
import app.chatbot.n8n.N8nClientException;
import app.chatbot.n8n.config.N8nProperties;
import app.chatbot.n8n.dto.N8nConnectionRequest;
import app.chatbot.n8n.dto.N8nConnectionResponse;
import app.chatbot.n8n.dto.N8nConnectionStatusResponse;
import app.chatbot.n8n.invoker.ApiClient;
import app.chatbot.n8n.invoker.auth.ApiKeyAuth;
import app.chatbot.n8n.persistence.N8nSettings;
import static app.chatbot.n8n.persistence.N8nSettings.SINGLETON_ID;
import app.chatbot.n8n.persistence.N8nSettingsRepository;
import app.chatbot.security.EncryptionException;
import app.chatbot.security.SecretEncryptor;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;

@Service
public class N8nConnectionService extends ConnectionVerificationTemplate<N8nConnectionStatusResponse> {

    private static final Logger log = LoggerFactory.getLogger(N8nConnectionService.class);

    private final N8nSettingsRepository repository;
    private final ApiClient apiClient;
    private final N8nWorkflowService workflowService;
    private final SecretEncryptor secretEncryptor;

    private volatile boolean configured;
    private volatile String currentBaseUrl;

    public N8nConnectionService(
            N8nSettingsRepository repository,
            ApiClient apiClient,
            N8nWorkflowService workflowService,
            SecretEncryptor secretEncryptor,
            N8nProperties properties
    ) {
        this.repository = repository;
        this.apiClient = apiClient;
        this.workflowService = workflowService;
        this.secretEncryptor = secretEncryptor;
        this.currentBaseUrl = properties.getBaseUrl();
        this.configured = false;
    }

    @PostConstruct
    void applyPersistedConfiguration() {
        repository.findById(SINGLETON_ID).ifPresent(settings -> {
            try {
                applyToClient(settings);
                configured = true;
                currentBaseUrl = settings.getBaseUrl();
            } catch (EncryptionException ex) {
                throw new IllegalStateException("Failed to decrypt stored n8n API key. Ensure encryption key is correct.", ex);
            }
        });
    }

    public N8nConnectionResponse currentConnection() {
        Optional<N8nSettings> entity = repository.findById(SINGLETON_ID);
        if (entity.isEmpty()) {
            return new N8nConnectionResponse(currentBaseUrl, configured, null);
        }
        N8nSettings settings = entity.get();
        return new N8nConnectionResponse(settings.getBaseUrl(), configured, settings.getUpdatedAt());
    }

    @Transactional
    public N8nConnectionResponse updateConnection(N8nConnectionRequest request) {
        String sanitizedBaseUrl = normalizeBaseUrl(request.baseUrl());

        ApiKeyAuth apiKeyAuth = extractApiKeyAuth(apiClient);
        String previousBaseUrl = apiClient.getBasePath();
        String previousApiKey = apiKeyAuth.getApiKey();

        try {
            String encryptedApiKey = secretEncryptor.encrypt(request.apiKey());

            N8nSettings settings = repository.findById(SINGLETON_ID)
                    .orElseGet(N8nSettings::new);
            settings.setBaseUrl(sanitizedBaseUrl);
            settings.setApiKeyEncrypted(encryptedApiKey);
            repository.save(settings);

            applyToClient(settings);
            configured = true;
            currentBaseUrl = sanitizedBaseUrl;

            return new N8nConnectionResponse(settings.getBaseUrl(), true, settings.getUpdatedAt());
        } catch (EncryptionException ex) {
            revertClientConfiguration(previousBaseUrl, previousApiKey);
            throw new N8nClientException("Failed to encrypt n8n API key.", ex);
        } catch (RuntimeException ex) {
            revertClientConfiguration(previousBaseUrl, previousApiKey);
            throw ex;
        }
    }

    /**
     * Tests the n8n connection by delegating to the template method.
     * <p>
     * This method uses the Connection Verification Template pattern to ensure
     * consistent error handling and logging across connection tests.
     *
     * @return Connection status with success flag and message
     */
    public N8nConnectionStatusResponse testConnection() {
        return verify();
    }

    public boolean isConfigured() {
        return configured;
    }

    // =========================
    // Template Method Overrides
    // =========================

    @Override
    protected ValidationResult validatePreConditions() {
        if (!configured) {
            return ValidationResult.invalid("Connection is not configured yet.");
        }
        return ValidationResult.valid();
    }

    @Override
    protected void performConnectionTest() throws N8nClientException {
        // Lightweight operation: fetch only 1 workflow to verify connectivity
        workflowService.listWorkflows(null, null, null, null, null, 1, null);
    }

    @Override
    protected N8nConnectionStatusResponse buildSuccessResponse() {
        return new N8nConnectionStatusResponse(true, "Connection successful.");
    }

    @Override
    protected N8nConnectionStatusResponse buildFailureResponse(String errorMessage) {
        return new N8nConnectionStatusResponse(false, errorMessage);
    }

    @Override
    protected String getServiceName() {
        return "n8n";
    }

    // =========================
    // Private Helper Methods
    // =========================


    private void applyToClient(N8nSettings settings) {
        String apiKey = secretEncryptor.decrypt(settings.getApiKeyEncrypted());
        String baseUrl = settings.getBaseUrl();

        apiClient.setBasePath(baseUrl);

        ApiKeyAuth apiKeyAuth = extractApiKeyAuth(apiClient);
        apiKeyAuth.setApiKey(apiKey);
    }

    private void revertClientConfiguration(String baseUrl, String apiKey) {
        apiClient.setBasePath(baseUrl);
        ApiKeyAuth apiKeyAuth = extractApiKeyAuth(apiClient);
        apiKeyAuth.setApiKey(apiKey);
    }

    private ApiKeyAuth extractApiKeyAuth(ApiClient client) {
        var auth = client.getAuthentication("ApiKeyAuth");
        Assert.notNull(auth, "ApiKeyAuth must be present on ApiClient");
        if (!(auth instanceof ApiKeyAuth apiKeyAuth)) {
            throw new IllegalStateException("ApiKeyAuth is not configured correctly.");
        }
        return apiKeyAuth;
    }

    private String normalizeBaseUrl(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Base URL must not be blank.");
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("Base URL must start with http:// or https://");
        }

        String normalized = trimmed.replaceAll("/+$", "");
        if (normalized.endsWith("/api/v1")) {
            // ok
        } else if (normalized.endsWith("/api/v1/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("/api")) {
            normalized = normalized + "/v1";
        } else if (normalized.endsWith("/api/")) {
            normalized = normalized + "v1";
        } else if (!normalized.endsWith("/api/v1")) {
            normalized = normalized + "/api/v1";
        }

        try {
            URI uri = URI.create(normalized);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("Base URL must include a host.");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Base URL is not valid: " + ex.getMessage(), ex);
        }

        return normalized;
    }
}
