package app.chatbot.openai;

import java.util.List;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class OpenAiProxyService {

    private static final String RESPONSES_PATH = "/responses";
    private static final String MODELS_PATH = "/models";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestTemplate restTemplate;
    private final OpenAiProperties properties;

    public OpenAiProxyService(RestTemplate restTemplate,
                              OpenAiProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public ResponseEntity<String> createResponse(JsonNode payload,
                                                 @Nullable String authorizationHeader) {
        log.info("Forwarding create response request to LLM: {} (payloadTokens={})",
                payload, payload != null ? payload.toString().length() : 0);
        return execute(HttpMethod.POST, RESPONSES_PATH, payload, authorizationHeader, true);
    }

    public ResponseEntity<String> listModels(@Nullable String authorizationHeader) {
        log.debug("Forwarding list models request to LLM");
        return execute(HttpMethod.GET, MODELS_PATH, null, authorizationHeader, false);
    }

    public ResponseEntity<String> createChatCompletion(JsonNode payload,
                                                       @Nullable String authorizationHeader) {
        log.debug("Forwarding chat completion request to LLM");
        return execute(HttpMethod.POST, CHAT_COMPLETIONS_PATH, payload, authorizationHeader, false);
    }

    private ResponseEntity<String> execute(HttpMethod method,
                                           String path,
                                           @Nullable JsonNode body,
                                           @Nullable String authorizationHeader,
                                           boolean useResponsesHeader) {
        RestTemplate template = this.restTemplate;
        HttpHeaders headers = buildHeaders(body != null, authorizationHeader, useResponsesHeader);
        HttpEntity<JsonNode> entity = new HttpEntity<>(body, headers);
        
        String targetUrl = properties.baseUrl() + path;
        
        try {
            ResponseEntity<String> response =
                    template.exchange(targetUrl, method, entity, String.class);
            log.trace("LLM call succeeded (status={}, path={})", response.getStatusCode(), path);
            return ResponseEntity
                    .status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .body(response.getBody());
        } catch (HttpStatusCodeException exception) {
            log.warn("LLM call failed with status {} and body {}", exception.getStatusCode(),
                    exception.getResponseBodyAsString(), exception);
            HttpHeaders errorHeaders = new HttpHeaders();
            errorHeaders.setContentType(MediaType.APPLICATION_JSON);
            return ResponseEntity
                    .status(exception.getStatusCode())
                    .headers(errorHeaders)
                    .body(exception.getResponseBodyAsString());
        } catch (RestClientException exception) {
            // Enhanced error message with configuration guidance
            String message = exception.getRootCause() instanceof java.net.SocketTimeoutException
                    ? "LLM request timed out. Ensure the model is loaded and responsive."
                    : String.format("Failed to reach LLM at %s. Ensure server is running or update openai.base-url configuration.", 
                                    properties.baseUrl());
            
            log.error("LLM call failed (url={}, path={}, method={}): {}", 
                     properties.baseUrl(), path, method, message, exception);
            
            throw new ResponseStatusException(BAD_GATEWAY, message, exception);
        }
    }

    private HttpHeaders buildHeaders(boolean hasBody,
                                     @Nullable String overrideAuthorization,
                                     boolean useResponsesHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (useResponsesHeader) {
            headers.add("OpenAI-Beta", "responses=v1");
        }
        if (hasBody) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        if (StringUtils.hasText(overrideAuthorization)) {
            headers.set(HttpHeaders.AUTHORIZATION, overrideAuthorization.trim());
        } else if (StringUtils.hasText(properties.apiKey())) {
            headers.setBearerAuth(properties.apiKey());
        }

        return headers;
    }
}
