package app.chatbot.litellm.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

public class HttpBearerAuth implements Authentication {

    private final String scheme;
    private String bearerToken;

    public HttpBearerAuth(String scheme) {
        this.scheme = scheme;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    @Override
    public void applyToParams(MultiValueMap<String, String> queryParams,
                              HttpHeaders headerParams,
                              MultiValueMap<String, String> cookieParams) {
        if (!StringUtils.hasText(bearerToken)) {
            return;
        }
        String prefix = StringUtils.hasText(scheme) ? scheme : "Bearer";
        headerParams.add(HttpHeaders.AUTHORIZATION, prefix + " " + bearerToken);
    }
}
