package app.chatbot.responses;

import com.fasterxml.jackson.databind.JsonNode;

public record ResponseStreamRequest(
        Long conversationId,
        String title,
        JsonNode payload
) {}
