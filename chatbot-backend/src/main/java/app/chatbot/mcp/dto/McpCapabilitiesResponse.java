package app.chatbot.mcp.dto;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.Builder;

/**
 * DTO representing tools/resources/prompts returned by LiteLLM's MCP REST proxy.
 */
@Builder
public record McpCapabilitiesResponse(
        List<ToolInfo> tools,
        List<ResourceInfo> resources,
        List<PromptInfo> prompts,
        ServerInfo serverInfo
) {
    @Builder
    public record ToolInfo(
            String name,
            String description,
            JsonNode inputSchema
    ) {}

    @Builder
    public record ResourceInfo(
            String uri,
            String name,
            String description,
            String mimeType
    ) {}

    @Builder
    public record PromptInfo(
            String name,
            String description,
            List<PromptArgument> arguments
    ) {}

    @Builder
    public record PromptArgument(
            String name,
            String description,
            boolean required
    ) {}

    @Builder
    public record ServerInfo(
            String name,
            String version,
            boolean supportsTools,
            boolean supportsResources,
            boolean supportsPrompts
    ) {}
}
