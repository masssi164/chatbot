package app.chatbot.mcp.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    @NotBlank
    private String encryptionKey;

    @NotBlank
    private String clientName = "chatbot-backend";

    @NotBlank
    private String clientVersion = "1.0.0";

    @NotNull
    private Duration requestTimeout = Duration.ofSeconds(30);

    @NotNull
    private Duration initializationTimeout = Duration.ofSeconds(30);

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(10);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(60);

    public String encryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String clientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String clientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration initializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
