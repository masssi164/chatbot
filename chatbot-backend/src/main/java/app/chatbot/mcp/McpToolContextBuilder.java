package app.chatbot.mcp;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.security.EncryptionException;
import app.chatbot.security.SecretEncryptor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class McpToolContextBuilder {

    private final McpServerRepository repository;
    private final SecretEncryptor secretEncryptor;

    public McpToolContextBuilder(McpServerRepository repository,
                                 SecretEncryptor secretEncryptor) {
        this.repository = repository;
        this.secretEncryptor = secretEncryptor;
    }

    public void augmentPayload(ObjectNode payload) {
        if (payload == null) {
            return;
        }

        ArrayNode toolsNode = payload.withArray("tools");
        if (toolsNode.size() > 0) {
            ArrayNode preserved = toolsNode.arrayNode();
            for (JsonNode node : toolsNode) {
                if (!isMcpTool(node)) {
                    preserved.add(node);
                }
            }
            toolsNode.removeAll();
            toolsNode.addAll(preserved);
        }

        List<McpServer> servers = repository.findAll()
                .stream()
                .filter(server -> server.getStatus() == McpServerStatus.CONNECTED)
                .toList();

        if (servers.isEmpty()) {
            log.debug("No connected MCP servers available for tool context");
            return;
        }

        for (McpServer server : servers) {
            ObjectNode mcpTool = payload.objectNode();
            mcpTool.put("type", "mcp");
            mcpTool.put("server_label", server.getServerId());
            mcpTool.put("server_url", server.getBaseUrl());
            mcpTool.put("require_approval", "never");

            String apiKey = decryptApiKey(server);
            if (StringUtils.hasText(apiKey)) {
                ObjectNode headers = payload.objectNode();
                headers.put("Authorization", "Bearer " + apiKey);
                mcpTool.set("server_headers", headers);
            }

            toolsNode.add(mcpTool);
        }
    }

    private String decryptApiKey(McpServer server) {
        if (!StringUtils.hasText(server.getApiKey())) {
            return null;
        }
        try {
            return secretEncryptor.decrypt(server.getApiKey());
        } catch (EncryptionException ex) {
            log.warn("Failed to decrypt API key for MCP server {}: {}", server.getServerId(), ex.getMessage());
            return null;
        }
    }

    private boolean isMcpTool(JsonNode node) {
        return node != null
                && node.isObject()
                && "mcp".equalsIgnoreCase(node.path("type").asText());
    }
}
