package app.chatbot.litellm.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

public interface Authentication {
    void applyToParams(MultiValueMap<String, String> queryParams,
                       HttpHeaders headerParams,
                       MultiValueMap<String, String> cookieParams);
}
