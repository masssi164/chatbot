package app.chatbot.mcp;

import static app.chatbot.mcp.config.McpSessionConstants.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Service für die Verwaltung von MCP-Client-Verbindungen zu externen MCP-Servern.
 * Delegiert Session-Management an McpSessionRegistry (Async mit Project Reactor).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpClientService {

    private final McpSessionRegistry sessionRegistry;

    /**
     * Listet alle verfügbaren Tools eines MCP-Servers auf (async).
     *
     * @param serverId Die Server-ID
     * @return Mono mit Liste der verfügbaren Tools
     */
    public Mono<List<McpSchema.Tool>> listToolsAsync(String serverId) {
        return sessionRegistry.getOrCreateSession(serverId)
                .flatMap(client -> {
                    var capabilities = client.getServerCapabilities();
                    if (capabilities != null && capabilities.tools() == null) {
                        log.info("Server {} does not advertise tools capability, returning empty list", serverId);
                        return Mono.just(List.<McpSchema.Tool>of());
                    }
                    return client.listTools()
                            .map(result -> {
                                @SuppressWarnings("unchecked")
                                List<McpSchema.Tool> tools = result != null && result.tools() != null
                                        ? (List<McpSchema.Tool>) (List<?>) result.tools()
                                        : List.of();
                                return tools;
                            })
                            .onErrorResume(IllegalStateException.class, translateMissingCapability("tools", serverId));
                })
                .timeout(OPERATION_TIMEOUT)
                .doOnError(ex -> log.error("Failed to list tools for server {}", serverId, ex));
    }

    /**
     * Listet alle verfügbaren Resources eines MCP-Servers auf (async).
     *
     * @param serverId Die Server-ID
     * @return Mono mit Liste der verfügbaren Resources
     */
    public Mono<List<McpSchema.Resource>> listResourcesAsync(String serverId) {
        return sessionRegistry.getOrCreateSession(serverId)
                .flatMap(client -> {
                    var capabilities = client.getServerCapabilities();
                    if (capabilities == null || capabilities.resources() == null) {
                        log.debug("Server {} does not advertise resources capability, returning empty list", serverId);
                        return Mono.just(List.<McpSchema.Resource>of());
                    }
                    return client.listResources()
                            .map(result -> {
                                @SuppressWarnings("unchecked")
                                List<McpSchema.Resource> resources = result != null && result.resources() != null
                                        ? (List<McpSchema.Resource>) (List<?>) result.resources()
                                        : List.of();
                                return resources;
                            })
                            .onErrorResume(IllegalStateException.class, translateMissingCapability("resources", serverId));
                })
                .timeout(OPERATION_TIMEOUT)
                .doOnError(ex -> log.error("Failed to list resources for server {}", serverId, ex));
    }

    /**
     * Listet alle verfügbaren Prompts eines MCP-Servers auf (async).
     *
     * @param serverId Die Server-ID
     * @return Mono mit Liste der verfügbaren Prompts
     */
    public Mono<List<McpSchema.Prompt>> listPromptsAsync(String serverId) {
        return sessionRegistry.getOrCreateSession(serverId)
                .flatMap(client -> {
                    var capabilities = client.getServerCapabilities();
                    if (capabilities == null || capabilities.prompts() == null) {
                        log.debug("Server {} does not advertise prompts capability, returning empty list", serverId);
                        return Mono.just(List.<McpSchema.Prompt>of());
                    }
                    return client.listPrompts()
                            .map(result -> {
                                @SuppressWarnings("unchecked")
                                List<McpSchema.Prompt> prompts = result != null && result.prompts() != null
                                        ? (List<McpSchema.Prompt>) (List<?>) result.prompts()
                                        : List.of();
                                return prompts;
                            })
                            .onErrorResume(IllegalStateException.class, translateMissingCapability("prompts", serverId));
                })
                .timeout(OPERATION_TIMEOUT)
                .doOnError(ex -> log.error("Failed to list prompts for server {}", serverId, ex));
    }

    /**
     * Ruft ein Tool auf einem MCP-Server auf (async).
     *
     * @param serverId Die Server-ID
     * @param toolName Name des Tools
     * @param arguments Tool-Argumente
     * @return Mono mit dem Ergebnis des Tool-Aufrufs
     */
    public Mono<McpSchema.CallToolResult> callToolAsync(String serverId, String toolName, Map<String, Object> arguments) {
        return sessionRegistry.getOrCreateSession(serverId)
                .flatMap(client -> {
                    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, arguments);
                    return client.callTool(request);
                })
                .doOnSuccess(result -> log.info("Tool {} called on server {} with result: {}",
                        toolName, serverId, result != null ? "success" : "null"))
                .doOnError(ex -> log.error("Failed to call tool {} on server {}", toolName, serverId, ex));
    }

    /**
     * Liefert die Server-Fähigkeiten für Diagnosezwecke.
     *
     * @param serverId Die Server-ID
     * @return Mono mit den bekannten Server-Fähigkeiten (kann null sein, falls Server diese nicht meldet)
     */
    public Mono<McpSchema.ServerCapabilities> getServerCapabilities(String serverId) {
        return sessionRegistry.getOrCreateSession(serverId)
                .map(client -> {
                    var capabilities = client.getServerCapabilities();
                    if (capabilities == null) {
                        log.debug("Server {} returned null capabilities snapshot", serverId);
                    }
                    return capabilities;
                })
                .doOnError(ex -> log.error("Failed to fetch server capabilities for {}", serverId, ex));
    }

    private <T> java.util.function.Function<Throwable, Mono<List<T>>> translateMissingCapability(String capabilityName, String serverId) {
        return throwable -> {
            if (throwable instanceof IllegalStateException ise
                    && ise.getMessage() != null
                    && ise.getMessage().toLowerCase().contains("does not provide")) {
                log.info("Server {} rejected {} listing because capability is missing; returning empty list", serverId, capabilityName);
                return Mono.just(List.<T>of());
            }
            return Mono.error(throwable);
        };
    }

    /**
     * Schließt die Verbindung zu einem MCP-Server.
     *
     * @param serverId Die Server-ID
     */
    public void closeConnection(String serverId) {
        sessionRegistry.closeSession(serverId)
                .doOnSuccess(v -> log.info("Closed MCP session for server {}", serverId))
                .doOnError(ex -> log.warn("Error closing MCP session for server {}", serverId, ex))
                .subscribe();
    }
}
