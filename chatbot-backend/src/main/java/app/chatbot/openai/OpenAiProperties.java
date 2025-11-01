package app.chatbot.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(String baseUrl,
                               String apiKey,
                               String defaultTitleModel,
                               Duration connectTimeout,
                               Duration readTimeout) {

    public static final String DEFAULT_BASE_URL = "http://localhost:1234/v1";
    public static final String DEFAULT_TITLE_MODEL = "gpt-4.1-mini";
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(120);

    public OpenAiProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        apiKey = normalizeApiKey(apiKey);
        defaultTitleModel = normalizeTitleModel(defaultTitleModel);
        connectTimeout = normalizeConnectTimeout(connectTimeout);
        readTimeout = normalizeReadTimeout(readTimeout);
    }

    private static String normalizeBaseUrl(String value) {
        String candidate = StringUtils.hasText(value) ? value.trim() : DEFAULT_BASE_URL;
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private static String normalizeApiKey(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String normalizeTitleModel(String value) {
        String candidate = StringUtils.hasText(value) ? value.trim() : DEFAULT_TITLE_MODEL;
        return candidate.isEmpty() ? DEFAULT_TITLE_MODEL : candidate;
    }

    private static Duration normalizeConnectTimeout(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            return DEFAULT_CONNECT_TIMEOUT;
        }
        return value;
    }

    private static Duration normalizeReadTimeout(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            return DEFAULT_READ_TIMEOUT;
        }
        return value;
    }

}
