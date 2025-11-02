package app.chatbot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.chatbot.mcp.config.McpProperties;
import app.chatbot.security.AesGcmSecretEncryptor;
import app.chatbot.security.SecretEncryptor;

/**
 * Central encryption configuration.
 * Uses MCP encryption key as the unified secret for all encryption operations.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class EncryptionConfig {

    /**
     * Creates a single SecretEncryptor bean used by all services requiring encryption.
     * This ensures consistent encryption across MCP, N8n, and other services.
     */
    @Bean
    public SecretEncryptor secretEncryptor(McpProperties properties) {
        return new AesGcmSecretEncryptor(properties.encryptionKey());
    }
}
