package app.chatbot.mcp.dto;

import java.time.Instant;

import app.chatbot.mcp.McpServerStatus;
import app.chatbot.mcp.McpTransport;

public record McpServerDto(
        String serverId,
        String name,
        String baseUrl,
        boolean hasApiKey,
        McpServerStatus status,
        McpTransport transport,
        Instant lastUpdated
) {
}
