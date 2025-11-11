package app.chatbot.mcp.dto;

import java.util.List;
import java.util.Map;

import app.chatbot.mcp.McpTransport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record McpServerRequest(
        String serverId,
        @NotBlank String name,
        @NotBlank String baseUrl,
        @NotNull McpTransport transport,
        String authType,
        String authValue,
        Map<String, String> staticHeaders,
        List<String> extraHeaders,
        List<String> accessGroups,
        String requireApproval
) {}
