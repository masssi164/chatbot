package app.chatbot.config;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/models")
@Slf4j
public class ModelController {

    private final WebClient webClient;
    private final OpenAiProperties properties;

    public ModelController(WebClient webClient, OpenAiProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @GetMapping
    public Mono<List<String>> listModels() {
        log.info("Fetching models from LLM Studio at: {}", properties.getBaseUrl() + "/models");
        return webClient.get()
                .uri(properties.getBaseUrl() + "/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> {
                    var models = new java.util.ArrayList<String>();
                    if (response.has("data") && response.get("data").isArray()) {
                        response.get("data").forEach(model -> {
                            if (model.has("id")) {
                                models.add(model.get("id").asText());
                            }
                        });
                    }
                    log.info("Successfully fetched {} models from LLM Studio", models.size());
                    log.debug("Models: {}", models);
                    return (List<String>) models;
                })
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("Failed to fetch models from LLM Studio: {} - {}", e.getStatusCode(), e.getMessage());
                    return Mono.just(List.of());
                })
                .onErrorResume(e -> {
                    log.error("Unexpected error fetching models", e);
                    return Mono.just(List.of());
                });
    }
}
