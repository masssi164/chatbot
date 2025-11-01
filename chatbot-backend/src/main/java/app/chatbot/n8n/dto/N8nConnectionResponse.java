package app.chatbot.n8n.dto;

import java.time.Instant;

public record N8nConnectionResponse(
        String baseUrl,
        boolean configured,
        Instant updatedAt
) {
}
