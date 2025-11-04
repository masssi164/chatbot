package app.chatbot.responses;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.mcp.McpServerRepository;
import app.chatbot.mcp.McpServerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Provides MCP tools from connected MCP servers to OpenAI Responses API.
 * 
 * <p>Architecture:
 * <ul>
 *   <li>Reads tools_cache from mcp_servers table (synced by McpConnectionService)</li>
 *   <li>Converts MCP tool definitions to OpenAI Responses API format with type:"mcp"</li>
 *   <li>Only includes tools from servers with status=CONNECTED</li>
 *   <li>Returns Flux<JsonNode> for reactive streaming to ResponseStreamService</li>
 * </ul>
 * 
 * <p>OpenAI Responses API Format:
 * <pre>{@code
 * {
 *   "type": "mcp",
 *   "server_label": "weather-api",
 *   "server_description": "Weather forecasting tools",
 *   "server_url": "https://mcp-weather.example.com/sse",
 *   "allowed_tools": ["get_weather", "get_forecast"],
 *   "require_approval": "never"
 * }
 * }</pre>
 * 
 * @see <a href="https://platform.openai.com/docs/guides/tools-connectors-mcp">OpenAI MCP Tools Documentation</a>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultToolDefinitionProvider implements ToolDefinitionProvider {

    private final McpServerRepository mcpServerRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Flux<JsonNode> listTools() {
        log.debug("Listing MCP tools from connected servers");
        
        return mcpServerRepository.findAll()
            .filter(server -> {
                boolean isConnected = server.getStatusEnum() == McpServerStatus.CONNECTED;
                boolean hasToolsCache = StringUtils.hasText(server.getToolsCache());
                
                if (!isConnected) {
                    log.trace("Server {} skipped: status={}", server.getServerId(), server.getStatusEnum());
                } else if (!hasToolsCache) {
                    log.warn("Server {} is CONNECTED but has no tools_cache", server.getServerId());
                }
                
                return isConnected && hasToolsCache;
            })
            .flatMap(server -> {
                try {
                    // Parse cached tools from DB
                    JsonNode toolsCache = objectMapper.readTree(server.getToolsCache());
                    
                    if (!toolsCache.isArray()) {
                        log.warn("Server {} has invalid tools_cache (not an array)", server.getServerId());
                        return Flux.empty();
                    }
                    
                    ArrayNode toolsArray = (ArrayNode) toolsCache;
                    if (toolsArray.isEmpty()) {
                        log.debug("Server {} has no tools in cache", server.getServerId());
                        return Flux.empty();
                    }
                    
                    // Extract tool names for allowed_tools
                    ArrayNode allowedTools = objectMapper.createArrayNode();
                    for (JsonNode tool : toolsArray) {
                        String toolName = tool.path("name").asText(null);
                        if (toolName != null) {
                            allowedTools.add(toolName);
                        }
                    }
                    
                    // Build OpenAI Responses API MCP tool definition
                    ObjectNode mcpTool = objectMapper.createObjectNode();
                    mcpTool.put("type", "mcp");
                    mcpTool.put("server_label", server.getServerId());
                    mcpTool.put("server_description", server.getName());
                    mcpTool.put("server_url", server.getBaseUrl());
                    mcpTool.set("allowed_tools", allowedTools);
                    mcpTool.put("require_approval", "never");  // TODO: Make configurable per server
                    
                    log.debug("Including MCP server {} with {} tools", 
                        server.getServerId(), allowedTools.size());
                    
                    return Flux.just((JsonNode) mcpTool);
                    
                } catch (JsonProcessingException e) {
                    log.error("Failed to parse tools_cache for server {}", server.getServerId(), e);
                    return Flux.empty();
                }
            })
            .doOnComplete(() -> log.debug("Finished listing MCP tools"));
    }
}

