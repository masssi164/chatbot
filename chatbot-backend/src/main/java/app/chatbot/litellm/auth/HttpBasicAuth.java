package app.chatbot.litellm.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

public class HttpBasicAuth implements Authentication {

    private String username;
    private String password;

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public void applyToParams(MultiValueMap<String, String> queryParams,
                              HttpHeaders headerParams,
                              MultiValueMap<String, String> cookieParams) {
        if (username == null && password == null) {
            return;
        }
        String value = (username == null ? "" : username) + ":" + (password == null ? "" : password);
        String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        headerParams.add(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
    }
}
