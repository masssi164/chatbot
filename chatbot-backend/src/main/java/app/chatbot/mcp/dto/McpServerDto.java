package app.chatbot.mcp.dto;

import java.time.Instant;
import java.util.List;

import app.chatbot.mcp.McpServerStatus;
import app.chatbot.mcp.McpTransport;

public record McpServerDto(
        String serverId,
        String name,
        String baseUrl,
        McpTransport transport,
        McpServerStatus status,
        Instant createdAt,
        Instant updatedAt,
        String requireApproval,
        List<String> extraHeaders,
        List<String> accessGroups
) {}
