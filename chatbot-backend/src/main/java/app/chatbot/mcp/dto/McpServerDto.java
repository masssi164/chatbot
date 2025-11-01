package app.chatbot.mcp.dto;

import app.chatbot.mcp.McpServerStatus;

import java.time.Instant;

public record McpServerDto(
        String serverId,
        String name,
        String baseUrl,
        String apiKey,
        McpServerStatus status,
        Instant lastUpdated
) {
}
