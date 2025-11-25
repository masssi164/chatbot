package app.chatbot.responses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.mcp.LiteLlmMcpService;
import app.chatbot.mcp.LiteLlmMcpService.McpToolDescriptor;
import app.chatbot.mcp.McpServerStatus;
import app.chatbot.mcp.McpTransport;
import app.chatbot.mcp.dto.McpServerDto;
import reactor.core.publisher.Flux;

@Component
public class LiteLlmToolDefinitionProvider implements ToolDefinitionProvider {

    private static final Logger log = LoggerFactory.getLogger(LiteLlmToolDefinitionProvider.class);

    private final LiteLlmMcpService mcpService;
    private final ObjectMapper objectMapper;

    public LiteLlmToolDefinitionProvider(LiteLlmMcpService mcpService,
                                         ObjectMapper objectMapper) {
        this.mcpService = mcpService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<JsonNode> listTools() {
        return mcpService.listServers()
                .filter(server -> {
                    McpServerStatus status = server.status();
                    return status == null || status != McpServerStatus.ERROR;
                })
                .flatMap(server -> mcpService.listTools(server.serverId())
                        .map(descriptor -> toMcpToolBlock(server, descriptor))
                        .cast(JsonNode.class)
                        .onErrorResume(ex -> {
                            log.warn("Failed to list tools for server {}: {}", server.serverId(), ex.getMessage());
                            return Flux.empty();
                        }))
                .cast(JsonNode.class)
                .doOnNext(block -> log.debug("Exposing MCP block: {}", block));
    }

    private ObjectNode toMcpToolBlock(McpServerDto server, McpToolDescriptor tool) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "mcp");
        block.put("server_name", server.serverId());
        block.put("server_label", StringUtils.hasText(server.name()) ? server.name() : server.serverId());
        block.put("server_url", server.baseUrl());
        McpTransport transport = server.transport() != null ? server.transport() : McpTransport.STREAMABLE_HTTP;
        block.put("transport", transport.name().toLowerCase());
        block.put("require_approval", StringUtils.hasText(server.requireApproval()) ? server.requireApproval() : "never");
        block.put("tool_name", tool.name());
        // Bedrock requires a non-empty name/description; default description to tool name if missing
        String description = StringUtils.hasText(tool.description()) ? tool.description() : tool.name();
        block.put("description", description);
        log.debug("Building MCP tool block: server={} tool={} description={}", server.serverId(), tool.name(), description);
        if (tool.inputSchema() != null && !tool.inputSchema().isMissingNode()) {
            block.set("input_schema", tool.inputSchema());
        }
        if (tool.mcpInfo() != null && !tool.mcpInfo().isMissingNode()) {
            block.set("mcp_info", tool.mcpInfo());
        }
        return block;
    }
}
