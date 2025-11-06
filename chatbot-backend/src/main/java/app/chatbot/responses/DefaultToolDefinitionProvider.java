package app.chatbot.responses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.mcp.ApprovalPolicy;
import app.chatbot.mcp.McpServerRepository;
import app.chatbot.mcp.McpServerStatus;
import app.chatbot.mcp.ToolApprovalPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Provides MCP tools from connected MCP servers to OpenAI Responses API.
 * 
 * <p>Architecture:
 * <ul>
 *   <li>Reads tools_cache from mcp_servers table (synced by McpConnectionService)</li>
 *   <li>Groups tools by approval policy (ALWAYS/NEVER) using ToolApprovalPolicyService</li>
 *   <li>Creates separate MCP blocks for each policy group</li>
 *   <li>Converts MCP tool definitions to OpenAI Responses API format with type:"mcp"</li>
 *   <li>Only includes tools from servers with status=CONNECTED</li>
 *   <li>Returns Flux<JsonNode> for reactive streaming to ResponseStreamService</li>
 * </ul>
 * 
 * <p>OpenAI Responses API Format (multiple blocks per server possible):
 * <pre>{@code
 * // Block 1: Tools requiring approval
 * {
 *   "type": "mcp",
 *   "server_label": "weather-api",
 *   "server_description": "Weather forecasting tools",
 *   "server_url": "https://mcp-weather.example.com/sse",
 *   "allowed_tools": ["delete_forecast"],
 *   "require_approval": "always"
 * }
 * // Block 2: Auto-executing tools
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
    private final ToolApprovalPolicyService approvalPolicyService;
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
                    
                    // Extract tool names
                    List<String> toolNames = new ArrayList<>();
                    for (JsonNode tool : toolsArray) {
                        String toolName = tool.path("name").asText(null);
                        if (toolName != null) {
                            toolNames.add(toolName);
                        }
                    }
                    
                    if (toolNames.isEmpty()) {
                        log.debug("Server {} has no valid tool names", server.getServerId());
                        return Flux.empty();
                    }
                    
                    // Group tools by approval policy
                    return groupToolsByPolicy(server.getServerId(), toolNames)
                        .flatMapMany(toolsByPolicy -> {
                            List<JsonNode> mcpBlocks = new ArrayList<>();
                            
                            // Create separate MCP block for each policy
                            for (Map.Entry<ApprovalPolicy, List<String>> entry : toolsByPolicy.entrySet()) {
                                ApprovalPolicy policy = entry.getKey();
                                List<String> toolsForPolicy = entry.getValue();
                                
                                if (toolsForPolicy.isEmpty()) {
                                    continue;
                                }
                                
                                ArrayNode allowedTools = objectMapper.createArrayNode();
                                toolsForPolicy.forEach(allowedTools::add);
                                
                                ObjectNode mcpBlock = objectMapper.createObjectNode();
                                mcpBlock.put("type", "mcp");
                                mcpBlock.put("server_label", server.getServerId());
                                mcpBlock.put("server_description", server.getName());
                                mcpBlock.put("server_url", server.getBaseUrl());
                                mcpBlock.set("allowed_tools", allowedTools);
                                mcpBlock.put("require_approval", policy.getValue());
                                
                                mcpBlocks.add(mcpBlock);
                                
                                log.debug("Created MCP block for server {} with policy={} and {} tools", 
                                    server.getServerId(), policy.getValue(), toolsForPolicy.size());
                            }
                            
                            return Flux.fromIterable(mcpBlocks);
                        });
                    
                } catch (JsonProcessingException e) {
                    log.error("Failed to parse tools_cache for server {}", server.getServerId(), e);
                    return Flux.empty();
                }
            })
            .doOnComplete(() -> log.debug("Finished listing MCP tools"));
    }
    
    /**
     * Groups tools by their approval policy.
     * 
     * <p>Queries ToolApprovalPolicyService for each tool and groups by policy.
     * Default policy: NEVER (auto-execute) if not explicitly set.
     * 
     * @param serverId Server-ID
     * @param toolNames List of tool names
     * @return Mono<Map<ApprovalPolicy, List<String>>> grouped tools
     */
    private Mono<Map<ApprovalPolicy, List<String>>> groupToolsByPolicy(String serverId, List<String> toolNames) {
        return Flux.fromIterable(toolNames)
            .flatMap(toolName -> 
                approvalPolicyService.getPolicyForTool(serverId, toolName)
                    .map(policy -> Map.entry(toolName, policy))
            )
            .collectList()
            .map(entries -> {
                Map<ApprovalPolicy, List<String>> grouped = new HashMap<>();
                
                for (Map.Entry<String, ApprovalPolicy> entry : entries) {
                    ApprovalPolicy policy = entry.getValue();
                    grouped.computeIfAbsent(policy, k -> new ArrayList<>())
                           .add(entry.getKey());
                }
                
                return grouped;
            });
    }
}

