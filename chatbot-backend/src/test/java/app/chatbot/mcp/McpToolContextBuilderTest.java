package app.chatbot.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.security.AesGcmSecretEncryptor;
import app.chatbot.security.SecretEncryptor;

/**
 * Tests für McpToolContextBuilder mit OpenAI Responses API MCP-Server-Format.
 * 
 * Das korrekte Format ist:
 * {
 *   "type": "mcp",
 *   "server_label": "server-id",
 *   "server_url": "https://example.com/mcp",
 *   "headers": { "Authorization": "Bearer token" },
 *   "require_approval": "never"
 * }
 */
class McpToolContextBuilderTest {

    private final McpServerRepository repository = mock(McpServerRepository.class);
    private final SecretEncryptor secretEncryptor = new AesGcmSecretEncryptor("test-key-12345");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpToolContextBuilder builder = new McpToolContextBuilder(
        repository, secretEncryptor, objectMapper
    );

    @Test
    void addsMcpServerConfigInResponsesApiFormat() throws Exception {
        McpServer server = new McpServer();
        server.setServerId("test-server");
        server.setName("Test MCP Server");
        server.setBaseUrl("https://mcp.example.com/api");
        server.setTransport(McpTransport.STREAMABLE_HTTP);
        server.setStatus(McpServerStatus.CONNECTED);
        server.setSyncStatus(SyncStatus.SYNCED);
        server.setLastSyncedAt(Instant.now());
        
        // Mit API Key
        String encryptedKey = secretEncryptor.encrypt("test-api-key");
        server.setApiKey(encryptedKey);

        when(repository.findAll()).thenReturn(List.of(server));

        ObjectNode payload = objectMapper.createObjectNode();
        builder.augmentPayload(payload);

        // Validierung
        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).isNotNull();
        assertThat(tools).hasSize(1);

        ObjectNode mcpConfig = (ObjectNode) tools.get(0);
        
        // Format-Validierung
        assertThat(mcpConfig.path("type").asText()).isEqualTo("mcp");
        assertThat(mcpConfig.path("server_label").asText()).isEqualTo("test-server");
        assertThat(mcpConfig.path("server_url").asText()).isEqualTo("https://mcp.example.com/api");
        assertThat(mcpConfig.path("require_approval").asText()).isEqualTo("never");
        
        // Headers-Validierung
        ObjectNode headers = (ObjectNode) mcpConfig.get("headers");
        assertThat(headers).isNotNull();
        assertThat(headers.path("Authorization").asText()).startsWith("Bearer ");
    }

    @Test
    void addsMcpServerWithoutHeaders() {
        McpServer server = new McpServer();
        server.setServerId("public-server");
        server.setBaseUrl("https://public.example.com/mcp");
        server.setTransport(McpTransport.SSE);
        server.setStatus(McpServerStatus.CONNECTED);
        server.setSyncStatus(SyncStatus.SYNCED);
        server.setLastSyncedAt(Instant.now());
        // Kein API Key gesetzt

        when(repository.findAll()).thenReturn(List.of(server));

        ObjectNode payload = objectMapper.createObjectNode();
        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).hasSize(1);

        ObjectNode mcpConfig = (ObjectNode) tools.get(0);
        assertThat(mcpConfig.path("type").asText()).isEqualTo("mcp");
        assertThat(mcpConfig.path("server_label").asText()).isEqualTo("public-server");
        assertThat(mcpConfig.has("headers")).isFalse(); // Keine Headers ohne API Key
    }

    @Test
    void skipsServerWithExpiredSync() {
        McpServer server = new McpServer();
        server.setServerId("expired-server");
        server.setBaseUrl("https://expired.example.com/mcp");
        server.setStatus(McpServerStatus.CONNECTED);
        server.setSyncStatus(SyncStatus.SYNCED);
        server.setLastSyncedAt(Instant.now().minus(Duration.ofMinutes(10))); // Abgelaufen (> 5 Min)

        when(repository.findAll()).thenReturn(List.of(server));

        ObjectNode payload = objectMapper.createObjectNode();
        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).isEmpty();
    }

    @Test
    void skipsDisconnectedServers() {
        McpServer disconnected = new McpServer();
        disconnected.setServerId("disconnected-server");
        disconnected.setBaseUrl("https://offline.example.com/mcp");
        disconnected.setStatus(McpServerStatus.ERROR);
        disconnected.setSyncStatus(SyncStatus.SYNCED);
        disconnected.setLastSyncedAt(Instant.now());

        when(repository.findAll()).thenReturn(List.of(disconnected));

        ObjectNode payload = objectMapper.createObjectNode();
        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).isEmpty();
    }

    @Test
    void skipsUnsyncedServers() {
        McpServer unsynced = new McpServer();
        unsynced.setServerId("unsynced-server");
        unsynced.setBaseUrl("https://unsynced.example.com/mcp");
        unsynced.setStatus(McpServerStatus.CONNECTED);
        unsynced.setSyncStatus(SyncStatus.SYNC_FAILED);
        unsynced.setLastSyncedAt(Instant.now());

        when(repository.findAll()).thenReturn(List.of(unsynced));

        ObjectNode payload = objectMapper.createObjectNode();
        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).isEmpty();
    }

    @Test
    void handlesMultipleMcpServers() throws Exception {
        McpServer server1 = new McpServer();
        server1.setServerId("server-1");
        server1.setBaseUrl("https://server1.example.com/mcp");
        server1.setStatus(McpServerStatus.CONNECTED);
        server1.setSyncStatus(SyncStatus.SYNCED);
        server1.setLastSyncedAt(Instant.now());
        server1.setApiKey(secretEncryptor.encrypt("key1"));

        McpServer server2 = new McpServer();
        server2.setServerId("server-2");
        server2.setBaseUrl("https://server2.example.com/mcp");
        server2.setStatus(McpServerStatus.CONNECTED);
        server2.setSyncStatus(SyncStatus.SYNCED);
        server2.setLastSyncedAt(Instant.now());
        // Kein API Key

        when(repository.findAll()).thenReturn(List.of(server1, server2));

        ObjectNode payload = objectMapper.createObjectNode();
        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).hasSize(2);

        // Verify server labels
        List<String> serverLabels = List.of(
            tools.get(0).path("server_label").asText(),
            tools.get(1).path("server_label").asText()
        );
        
        assertThat(serverLabels).containsExactlyInAnyOrder("server-1", "server-2");
    }

    @Test
    void preservesExistingNonMcpTools() {
        McpServer server = new McpServer();
        server.setServerId("test-server");
        server.setBaseUrl("https://test.example.com/mcp");
        server.setStatus(McpServerStatus.CONNECTED);
        server.setSyncStatus(SyncStatus.SYNCED);
        server.setLastSyncedAt(Instant.now());

        when(repository.findAll()).thenReturn(List.of(server));

        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode existingTools = payload.putArray("tools");
        
        // Existing function tool
        ObjectNode functionTool = existingTools.addObject();
        functionTool.put("type", "function");
        ObjectNode function = functionTool.putObject("function");
        function.put("name", "custom_function");

        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).hasSize(2); // Existing + new MCP server
        
        // First tool should be the existing function
        assertThat(tools.get(0).path("type").asText()).isEqualTo("function");
        assertThat(tools.get(0).path("function").path("name").asText()).isEqualTo("custom_function");
        
        // Second tool should be MCP server
        assertThat(tools.get(1).path("type").asText()).isEqualTo("mcp");
    }
}


