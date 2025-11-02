package app.chatbot.mcp;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.security.AesGcmSecretEncryptor;
import app.chatbot.security.SecretEncryptor;

class McpToolContextBuilderTest {

    private final McpServerRepository repository = mock(McpServerRepository.class);
    private final SecretEncryptor secretEncryptor = createSecretEncryptor();
    private final McpToolContextBuilder builder = new McpToolContextBuilder(repository, secretEncryptor);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void addsMcpToolEntriesAndPreservesExistingTools() {
        McpServer server = new McpServer();
        server.setServerId("mcp-1");
        server.setName("Docs Server");
        server.setBaseUrl("https://docs.example.com/mcp");
        server.setTransport(McpTransport.STREAMABLE_HTTP);
        server.setStatus(McpServerStatus.CONNECTED);
        server.setApiKey(secretEncryptor.encrypt("s3cr3t"));
        server.setLastUpdated(Instant.now());

        when(repository.findAll()).thenReturn(List.of(server));

        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode existing = payload.putArray("tools");
        existing.addObject().put("type", "code_interpreter");

        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).isNotNull();
        assertThat(tools).hasSize(2);

        assertThat(tools.get(0).path("type").asText()).isEqualTo("code_interpreter");

        ObjectNode mcpEntry = (ObjectNode) tools.get(1);
        assertThat(mcpEntry.path("type").asText()).isEqualTo("mcp");
        assertThat(mcpEntry.path("server_label").asText()).isEqualTo("mcp-1");
        assertThat(mcpEntry.path("server_url").asText()).isEqualTo("https://docs.example.com/mcp");
        assertThat(mcpEntry.path("require_approval").asText()).isEqualTo("never");
        assertThat(mcpEntry.path("server_headers").path("Authorization").asText()).isEqualTo("Bearer s3cr3t");
    }

    @Test
    void skipsAuthorizationHeaderWhenDecryptionFails() {
        McpServer server = new McpServer();
        server.setServerId("mcp-2");
        server.setName("Public Server");
        server.setBaseUrl("https://public.example.com/mcp");
        server.setTransport(McpTransport.SSE);
        server.setStatus(McpServerStatus.CONNECTED);
        server.setApiKey("not-base64");

        when(repository.findAll()).thenReturn(List.of(server));

        ObjectNode payload = objectMapper.createObjectNode();
        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).isNotNull();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("server_headers").isMissingNode()).isTrue();
    }

    @Test
    void appliesDefaultSseEndpointWhenMissing() {
        McpServer server = new McpServer();
        server.setServerId("mcp-3");
        server.setName("Local SSE");
        server.setBaseUrl("http://localhost:8081");
        server.setTransport(McpTransport.SSE);
        server.setStatus(McpServerStatus.CONNECTED);

        when(repository.findAll()).thenReturn(List.of(server));

        ObjectNode payload = objectMapper.createObjectNode();
        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).hasSize(1);
        String serverUrl = tools.get(0).path("server_url").asText();
        assertThat(serverUrl).isEqualTo("http://localhost:8081");
    }

    @Test
    void ignoresServersThatAreNotConnected() {
        McpServer disconnected = new McpServer();
        disconnected.setServerId("mcp-4");
        disconnected.setName("Disconnected");
        disconnected.setBaseUrl("https://offline.example.com/mcp");
        disconnected.setTransport(McpTransport.STREAMABLE_HTTP);
        disconnected.setStatus(McpServerStatus.ERROR);

        when(repository.findAll()).thenReturn(List.of(disconnected));

        ObjectNode payload = objectMapper.createObjectNode();
        builder.augmentPayload(payload);

        ArrayNode tools = (ArrayNode) payload.get("tools");
        assertThat(tools).isEmpty();
    }

    private static SecretEncryptor createSecretEncryptor() {
        return new AesGcmSecretEncryptor("unit-test-key");
    }
}
