package app.chatbot.mcp.dto;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.Builder;

/**
 * DTO für MCP Server Capabilities.
 * Enthält alle verfügbaren Tools, Resources und Prompts eines MCP Servers.
 * 
 * @param tools Liste aller verfügbaren Tools
 * @param resources Liste aller verfügbaren Resources
 * @param prompts Liste aller verfügbaren Prompts
 * @param serverInfo Server-Metadaten
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
        Object inputSchema
    ) {
        public static ToolInfo from(McpSchema.Tool tool) {
            return ToolInfo.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
        }
    }
    
    @Builder
    public record ResourceInfo(
        String uri,
        String name,
        String description,
        String mimeType
    ) {
        public static ResourceInfo from(McpSchema.Resource resource) {
            return ResourceInfo.builder()
                .uri(resource.uri())
                .name(resource.name())
                .description(resource.description())
                .mimeType(resource.mimeType())
                .build();
        }
    }
    
    @Builder
    public record PromptInfo(
        String name,
        String description,
        List<PromptArgument> arguments
    ) {
        public static PromptInfo from(McpSchema.Prompt prompt) {
            List<PromptArgument> args = prompt.arguments() != null
                ? prompt.arguments().stream()
                    .map(PromptArgument::from)
                    .toList()
                : List.of();
            
            return PromptInfo.builder()
                .name(prompt.name())
                .description(prompt.description())
                .arguments(args)
                .build();
        }
    }
    
    @Builder
    public record PromptArgument(
        String name,
        String description,
        boolean required
    ) {
        public static PromptArgument from(McpSchema.PromptArgument arg) {
            return PromptArgument.builder()
                .name(arg.name())
                .description(arg.description())
                .required(arg.required() != null && arg.required())
                .build();
        }
    }
    
    @Builder
    public record ServerInfo(
        String name,
        String version,
        boolean supportsTools,
        boolean supportsResources,
        boolean supportsPrompts
    ) {}
}
