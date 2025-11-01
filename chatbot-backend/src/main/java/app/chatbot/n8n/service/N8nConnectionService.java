package app.chatbot.n8n.service;

import app.chatbot.n8n.N8nClientException;
import app.chatbot.n8n.config.N8nProperties;
import app.chatbot.n8n.dto.N8nConnectionRequest;
import app.chatbot.n8n.dto.N8nConnectionResponse;
import app.chatbot.n8n.dto.N8nConnectionStatusResponse;
import app.chatbot.n8n.invoker.ApiClient;
import app.chatbot.n8n.invoker.auth.ApiKeyAuth;
import app.chatbot.n8n.persistence.N8nSettings;
import app.chatbot.n8n.persistence.N8nSettingsRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static app.chatbot.n8n.persistence.N8nSettings.SINGLETON_ID;

@Service
public class N8nConnectionService {

    private static final Logger log = LoggerFactory.getLogger(N8nConnectionService.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final N8nSettingsRepository repository;
    private final ApiClient apiClient;
    private final N8nWorkflowService workflowService;
    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    private volatile boolean configured;
    private volatile String currentBaseUrl;

    public N8nConnectionService(
            N8nSettingsRepository repository,
            ApiClient apiClient,
            N8nWorkflowService workflowService,
            N8nProperties properties
    ) {
        this.repository = repository;
        this.apiClient = apiClient;
        this.workflowService = workflowService;
        this.secretKey = deriveKey(properties.getEncryptionKey());
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
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Failed to decrypt stored n8n API key. Ensure n8n.encryption-key matches the previous value.", ex);
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
            String encryptedApiKey = encrypt(request.apiKey());

            N8nSettings settings = repository.findById(SINGLETON_ID)
                    .orElseGet(N8nSettings::new);
            settings.setBaseUrl(sanitizedBaseUrl);
            settings.setApiKeyEncrypted(encryptedApiKey);
            repository.save(settings);

            applyToClient(settings);
            configured = true;
            currentBaseUrl = sanitizedBaseUrl;

            return new N8nConnectionResponse(settings.getBaseUrl(), true, settings.getUpdatedAt());
        } catch (GeneralSecurityException ex) {
            revertClientConfiguration(previousBaseUrl, previousApiKey);
            throw new N8nClientException("Failed to encrypt n8n API key.", ex);
        } catch (RuntimeException ex) {
            revertClientConfiguration(previousBaseUrl, previousApiKey);
            throw ex;
        }
    }

    public N8nConnectionStatusResponse testConnection() {
        if (!configured) {
            return new N8nConnectionStatusResponse(false, "Connection is not configured yet.");
        }
        try {
            workflowService.listWorkflows(null, null, null, null, null, 1, null);
            return new N8nConnectionStatusResponse(true, "Connection successful.");
        } catch (N8nClientException ex) {
            return new N8nConnectionStatusResponse(false, ex.getMessage());
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    private void applyToClient(N8nSettings settings) throws GeneralSecurityException {
        String apiKey = decrypt(settings.getApiKeyEncrypted());
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

    private SecretKey deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to derive encryption key", ex);
        }
    }

    private String encrypt(String value) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

        ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
        buffer.put(iv);
        buffer.put(ciphertext);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private String decrypt(String encoded) throws GeneralSecurityException {
        byte[] payload = Base64.getDecoder().decode(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(payload);

        byte[] iv = new byte[IV_LENGTH_BYTES];
        buffer.get(iv);

        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] plain = cipher.doFinal(ciphertext);
        return new String(plain, StandardCharsets.UTF_8);
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
