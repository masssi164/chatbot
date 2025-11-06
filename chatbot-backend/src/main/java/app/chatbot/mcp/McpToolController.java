package app.chatbot.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.chatbot.mcp.dto.McpCapabilitiesResponse;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * REST-Controller für MCP-Tool-Operationen.
 * Ermöglicht das Abrufen und Aufrufen von Tools auf externen MCP-Servern.
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@Slf4j
public class McpToolController {

    private final McpServerRepository serverRepository;
    private final McpClientService clientService;

    /**
     * Gibt alle Capabilities eines MCP-Servers zurück (Tools, Resources, Prompts).
     *
     * @param serverId Die Server-ID
     * @return Capabilities Response mit allen verfügbaren Features
     */
    @GetMapping("/servers/{serverId}/capabilities")
    public Mono<ResponseEntity<McpCapabilitiesResponse>> getCapabilities(@PathVariable String serverId) {
        log.debug("Getting capabilities for MCP server: {}", serverId);

        return serverRepository.findByServerId(serverId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("MCP server not found: " + serverId)))
                .flatMap(this::fetchCapabilitiesForServer)
                .onErrorResume(McpClientException.class, ex -> {
                    log.error("Failed to get capabilities for server {}", serverId, ex);
                    return Mono.just(ResponseEntity.<McpCapabilitiesResponse>status(500).body(null));
                });
    }

    private Mono<ResponseEntity<McpCapabilitiesResponse>> fetchCapabilitiesForServer(McpServer server) {
        if (server.getStatusEnum() != McpServerStatus.CONNECTED) {
            return Mono.just(ResponseEntity.<McpCapabilitiesResponse>badRequest().build());
        }

        Mono<McpSchema.ServerCapabilities> capabilitiesMono = clientService.getServerCapabilities(server.getServerId())
                .timeout(java.time.Duration.ofSeconds(5))
                .switchIfEmpty(Mono.empty());
        
        Mono<List<McpSchema.Tool>> toolsMono = clientService.listToolsAsync(server.getServerId())
                .timeout(java.time.Duration.ofSeconds(15))
                .defaultIfEmpty(List.of());
        
        Mono<List<McpSchema.Resource>> resourcesMono = clientService.listResourcesAsync(server.getServerId())
                .timeout(java.time.Duration.ofSeconds(15))
                .defaultIfEmpty(List.of());
        
        Mono<List<McpSchema.Prompt>> promptsMono = clientService.listPromptsAsync(server.getServerId())
                .timeout(java.time.Duration.ofSeconds(15))
                .defaultIfEmpty(List.of());

        return Mono.zip(capabilitiesMono, toolsMono, resourcesMono, promptsMono)
                .map(tuple -> {
                    McpSchema.ServerCapabilities capabilities = tuple.getT1();
                    List<McpSchema.Tool> tools = tuple.getT2();
                    List<McpSchema.Resource> resources = tuple.getT3();
                    List<McpSchema.Prompt> prompts = tuple.getT4();

                    boolean supportsTools = capabilities != null && capabilities.tools() != null;
                    boolean supportsResources = capabilities != null && capabilities.resources() != null;
                    boolean supportsPrompts = capabilities != null && capabilities.prompts() != null;

                    McpCapabilitiesResponse response = McpCapabilitiesResponse.builder()
                            .tools(tools.stream().map(McpCapabilitiesResponse.ToolInfo::from).toList())
                            .resources(resources.stream().map(McpCapabilitiesResponse.ResourceInfo::from).toList())
                            .prompts(prompts.stream().map(McpCapabilitiesResponse.PromptInfo::from).toList())
                            .serverInfo(McpCapabilitiesResponse.ServerInfo.builder()
                                    .name(server.getName())
                                    .version("1.0")
                                    .supportsTools(supportsTools)
                                    .supportsResources(supportsResources)
                                    .supportsPrompts(supportsPrompts)
                                    .build())
                            .build();

                    return ResponseEntity.ok(response);
                });
    }

    /**
     * Listet alle verfügbaren Tools eines MCP-Servers auf.
     *
     * @param serverId Die Server-ID
     * @return Liste der verfügbaren Tools
     */
    @GetMapping("/servers/{serverId}/tools")
    public Mono<ResponseEntity<List<McpSchema.Tool>>> listTools(@PathVariable String serverId) {
        log.debug("Listing tools for MCP server: {}", serverId);

        return serverRepository.findByServerId(serverId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("MCP server not found: " + serverId)))
                .flatMap(server -> {
                    if (server.getStatusEnum() != McpServerStatus.CONNECTED) {
                        return Mono.just(ResponseEntity.badRequest().build());
                    }

                    return clientService.listToolsAsync(server.getServerId())
                            .timeout(java.time.Duration.ofSeconds(15))
                            .map(ResponseEntity::ok)
                            .onErrorResume(McpClientException.class, ex -> {
                                log.error("Failed to list tools for server {}", serverId, ex);
                                return Mono.just(ResponseEntity.internalServerError().build());
                            });
                });
    }

    /**
     * Ruft ein Tool auf einem MCP-Server auf.
     *
     * @param serverId Die Server-ID
     * @param request Die Tool-Aufruf-Anfrage
     * @return Das Ergebnis des Tool-Aufrufs
     */
    @PostMapping("/servers/{serverId}/tools/call")
    public Mono<ResponseEntity<McpToolCallResponse>> callTool(
            @PathVariable String serverId,
            @RequestBody McpToolCallRequest request) {

        log.debug("Calling tool {} on MCP server: {}", request.toolName(), serverId);

        return serverRepository.findByServerId(serverId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("MCP server not found: " + serverId)))
                .flatMap(server -> {
                    if (server.getStatusEnum() != McpServerStatus.CONNECTED) {
                        return Mono.just(ResponseEntity.badRequest().build());
                    }

                    return clientService.callToolAsync(
                            server.getServerId(),
                            request.toolName(),
                            request.arguments()
                    )
                            .timeout(java.time.Duration.ofSeconds(30))
                            .map(result -> ResponseEntity.ok(new McpToolCallResponse(
                                    result.content(),
                                    result.isError()
                            )))
                            .onErrorResume(McpClientException.class, ex -> {
                                log.error("Failed to call tool {} on server {}", request.toolName(), serverId, ex);
                                return Mono.just(ResponseEntity.internalServerError().build());
                            });
                });
    }

    /**
     * Tool-Aufruf-Anfrage
     */
    public record McpToolCallRequest(
            String toolName,
            Map<String, Object> arguments
    ) {}

    /**
     * Tool-Aufruf-Antwort
     */
    public record McpToolCallResponse(
            List<? extends McpSchema.Content> content,
            Boolean isError
    ) {}
}
