package app.chatbot.openai;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/openai")
@RequiredArgsConstructor
@Slf4j
public class OpenAiProxyController {

    private final OpenAiProxyService proxyService;

    @PostMapping("/responses")
    public ResponseEntity<String> createResponse(@RequestBody JsonNode payload,
                                                 @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                                 String authorization) {
        log.debug("Received chat response request (hasAuthHeader={})", authorization != null);
        return proxyService.createResponse(payload, authorization);
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<String> createChatCompletion(@RequestBody JsonNode payload,
                                                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                                       String authorization) {
        log.debug("Received chat completion request (hasAuthHeader={})", authorization != null);
        return proxyService.createChatCompletion(payload, authorization);
    }

    @GetMapping("/models")
    public ResponseEntity<String> listModels(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization
    ) {
        log.debug("Received models list request (hasAuthHeader={})", authorization != null);
        return proxyService.listModels(authorization);
    }
}
