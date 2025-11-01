package app.chatbot.mcp.dto;

import app.chatbot.mcp.McpServerStatus;
import jakarta.validation.constraints.NotBlank;

public record McpServerRequest(
        String serverId,
        @NotBlank String name,
        @NotBlank String baseUrl,
        String apiKey,
        McpServerStatus status
) {
}

