package app.chatbot.responses;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Flux;

public interface ToolDefinitionProvider {

    Flux<JsonNode> listTools();
}
