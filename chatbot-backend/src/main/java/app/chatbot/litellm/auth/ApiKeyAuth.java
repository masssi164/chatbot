package app.chatbot.litellm.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

public class ApiKeyAuth implements Authentication {

    private final String location;
    private final String paramName;

    private String apiKey;
    private String apiKeyPrefix;

    public ApiKeyAuth(String location, String paramName) {
        this.location = location;
        this.paramName = paramName;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setApiKeyPrefix(String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    @Override
    public void applyToParams(MultiValueMap<String, String> queryParams,
                              HttpHeaders headerParams,
                              MultiValueMap<String, String> cookieParams) {
        if (apiKey == null || apiKey.isEmpty()) {
            return;
        }
        String value = apiKeyPrefix != null && !apiKeyPrefix.isEmpty()
                ? apiKeyPrefix + " " + apiKey
                : apiKey;

        switch (location) {
            case "query" -> queryParams.add(paramName, value);
            case "header" -> headerParams.add(paramName, value);
            case "cookie" -> cookieParams.add(paramName, value);
            default -> {
            }
        }
    }
}
