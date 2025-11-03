package app.chatbot.mcp.dto;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * DTO für MCP Capabilities (Tools, Resources, Prompts).
 * Wird von GET /api/mcp-servers/{serverId}/capabilities zurückgegeben.
 */
public record McpCapabilitiesDto(
    List<McpSchema.Tool> tools,
    List<McpSchema.Resource> resources,
    List<McpSchema.Prompt> prompts,
    ServerInfo serverInfo
) {
    public record ServerInfo(String name, String version) {}
}
