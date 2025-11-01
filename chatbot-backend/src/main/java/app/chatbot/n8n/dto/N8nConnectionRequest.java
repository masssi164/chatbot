package app.chatbot.n8n.dto;

import jakarta.validation.constraints.NotBlank;

public record N8nConnectionRequest(
        @NotBlank String baseUrl,
        @NotBlank String apiKey
) {
}
