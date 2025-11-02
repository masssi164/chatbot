package app.chatbot.mcp;

import java.util.List;
import java.util.Map;

import app.chatbot.mcp.dto.McpCapabilitiesResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    public ResponseEntity<McpCapabilitiesResponse> getCapabilities(@PathVariable String serverId) {
        log.debug("Getting capabilities for MCP server: {}", serverId);

        McpServer server = serverRepository.findByServerId(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverId));

        if (server.getStatus() != McpServerStatus.CONNECTED) {
            return ResponseEntity.badRequest().build();
        }

        try {
            List<McpSchema.Tool> tools = clientService.listTools(server);
            List<McpSchema.Resource> resources = clientService.listResources(server);
            List<McpSchema.Prompt> prompts = clientService.listPrompts(server);

            McpCapabilitiesResponse response = McpCapabilitiesResponse.builder()
                    .tools(tools.stream().map(McpCapabilitiesResponse.ToolInfo::from).toList())
                    .resources(resources.stream().map(McpCapabilitiesResponse.ResourceInfo::from).toList())
                    .prompts(prompts.stream().map(McpCapabilitiesResponse.PromptInfo::from).toList())
                    .serverInfo(McpCapabilitiesResponse.ServerInfo.builder()
                            .name(server.getName())
                            .version("1.0")
                            .build())
                    .build();

            return ResponseEntity.ok(response);
        } catch (McpClientException ex) {
            log.error("Failed to get capabilities for server {}", serverId, ex);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Listet alle verfügbaren Tools eines MCP-Servers auf.
     *
     * @param serverId Die Server-ID
     * @return Liste der verfügbaren Tools
     */
    @GetMapping("/servers/{serverId}/tools")
    public ResponseEntity<List<McpSchema.Tool>> listTools(@PathVariable String serverId) {
        log.debug("Listing tools for MCP server: {}", serverId);

        McpServer server = serverRepository.findByServerId(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverId));

        if (server.getStatus() != McpServerStatus.CONNECTED) {
            return ResponseEntity.badRequest().build();
        }

        try {
            List<McpSchema.Tool> tools = clientService.listTools(server);
            return ResponseEntity.ok(tools);
        } catch (McpClientException ex) {
            log.error("Failed to list tools for server {}", serverId, ex);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Ruft ein Tool auf einem MCP-Server auf.
     *
     * @param serverId Die Server-ID
     * @param request Die Tool-Aufruf-Anfrage
     * @return Das Ergebnis des Tool-Aufrufs
     */
    @PostMapping("/servers/{serverId}/tools/call")
    public ResponseEntity<McpToolCallResponse> callTool(
            @PathVariable String serverId,
            @RequestBody McpToolCallRequest request) {

        log.debug("Calling tool {} on MCP server: {}", request.toolName(), serverId);

        McpServer server = serverRepository.findByServerId(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverId));

        if (server.getStatus() != McpServerStatus.CONNECTED) {
            return ResponseEntity.badRequest().build();
        }

        try {
            McpSchema.CallToolResult result = clientService.callTool(
                    server,
                    request.toolName(),
                    request.arguments()
            );

            return ResponseEntity.ok(new McpToolCallResponse(
                    result.content(),
                    result.isError()
            ));
        } catch (McpClientException ex) {
            log.error("Failed to call tool {} on server {}", request.toolName(), serverId, ex);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Gibt Statistiken über aktive MCP-Verbindungen zurück.
     *
     * @return Statistiken
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "activeConnections", clientService.getActiveConnectionCount(),
                "totalServers", serverRepository.count()
        ));
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
