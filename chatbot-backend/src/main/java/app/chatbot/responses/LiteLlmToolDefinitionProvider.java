package app.chatbot.responses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.mcp.LiteLlmMcpService;
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
                .map(this::toMcpBlock)
                .cast(JsonNode.class)
                .doOnNext(block -> log.debug("Exposing MCP server block: {}", block));
    }

    private ObjectNode toMcpBlock(McpServerDto server) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "mcp");
        block.put("server_name", server.serverId());
        String label = StringUtils.hasText(server.name()) ? server.name() : server.serverId();
        block.put("server_label", label);
        block.put("server_url", server.baseUrl());
        McpTransport transport = server.transport() != null ? server.transport() : McpTransport.STREAMABLE_HTTP;
        block.put("transport", transport.name().toLowerCase());
        block.put("require_approval", StringUtils.hasText(server.requireApproval()) ? server.requireApproval() : "never");
        return block;
    }
}
