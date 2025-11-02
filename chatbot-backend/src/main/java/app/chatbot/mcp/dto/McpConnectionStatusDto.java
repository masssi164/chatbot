package app.chatbot.mcp.dto;

import app.chatbot.mcp.McpServerStatus;

public record McpConnectionStatusDto(
        McpServerStatus status,
        Integer toolCount,
        String message
) {
}
