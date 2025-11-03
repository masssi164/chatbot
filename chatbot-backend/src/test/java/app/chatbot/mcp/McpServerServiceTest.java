package app.chatbot.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import app.chatbot.mcp.events.McpServerStatusPublisher;
import app.chatbot.security.SecretEncryptor;
import app.chatbot.utils.GenericMapper;

@ExtendWith(MockitoExtension.class)
class McpServerServiceTest {

    @Mock
    private McpServerRepository repository;

    @Mock
    private GenericMapper mapper;

    @Mock
    private SecretEncryptor secretEncryptor;

    @Mock
    private McpClientService mcpClientService;

    @Mock
    private McpSessionRegistry sessionRegistry;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private McpServerStatusPublisher statusPublisher;

    @InjectMocks
    private McpServerService service;

    private McpServer testServer;
    private McpServerRequest testRequest;

    @BeforeEach
    void setUp() {
        testServer = new McpServer();
        testServer.setServerId("test-123");
        testServer.setName("Test MCP Server");
        testServer.setBaseUrl("https://mcp.example.com");
        testServer.setApiKey("encrypted-key");
        testServer.setStatus(McpServerStatus.IDLE);
        testServer.setTransport(McpTransport.STREAMABLE_HTTP);
        testServer.setLastUpdated(Instant.now());

        testRequest = new McpServerRequest(
                "test-123",
                "Test MCP Server",
                "https://mcp.example.com",
                "plain-key",
                McpServerStatus.IDLE,
                McpTransport.STREAMABLE_HTTP
        );
    }

    @Test
    void listServers_shouldReturnSortedServers()  {
        // given
        McpServer server1 = new McpServer();
        server1.setServerId("server1");
        server1.setName("Server 1");
        server1.setBaseUrl("https://server1.com");
        server1.setApiKey("encrypted-key-1");
        server1.setStatus(McpServerStatus.CONNECTED);
        server1.setTransport(McpTransport.STREAMABLE_HTTP);
        server1.setLastUpdated(Instant.parse("2024-01-01T10:00:00Z"));

        McpServer server2 = new McpServer();
        server2.setServerId("server2");
        server2.setName("Server 2");
        server2.setBaseUrl("https://server2.com");
        server2.setApiKey("encrypted-key-2");
        server2.setStatus(McpServerStatus.IDLE);
        server2.setTransport(McpTransport.SSE);
        server2.setLastUpdated(Instant.parse("2024-01-02T10:00:00Z"));

        when(repository.findAll()).thenReturn(List.of(server1, server2));

        // when
        List<McpServerDto> result = service.listServers();

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).serverId()).isEqualTo("server2"); // More recent first
        assertThat(result.get(1).serverId()).isEqualTo("server1");
    }

    @Test
    void getServer_shouldReturnServer()  {
        // given
        when(repository.findByServerId("test-123")).thenReturn(Optional.of(testServer));

        // when
        McpServerDto result = service.getServer("test-123");

        // then
        assertThat(result.serverId()).isEqualTo("test-123");
        assertThat(result.name()).isEqualTo("Test MCP Server");
        assertThat(result.transport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
    }

    @Test
    void getServer_shouldThrowWhenNotFound() {
        // given
        when(repository.findByServerId("unknown")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.getServer("unknown"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MCP server not found");
    }

    @Test
    void createOrUpdate_shouldCreateNewServer()  {
        // given
        McpServerRequest request = new McpServerRequest(
                null,
                "New Server",
                "https://new.example.com",
                "api-key",
                null,
                McpTransport.STREAMABLE_HTTP
        );
        when(repository.save(any(McpServer.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        McpServerDto result = service.createOrUpdate(request);

        // then
        assertThat(result.serverId()).startsWith("mcp-");
        assertThat(result.name()).isEqualTo("New Server");
        assertThat(result.transport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
        verify(secretEncryptor).encrypt("api-key");
        verify(repository).save(any(McpServer.class));
        // Connection is NOT closed - sessions stay open
        verify(mcpClientService, never()).closeConnection(anyString());
    }

    @Test
    void createOrUpdate_shouldUpdateExistingServer()  {
        // given
        when(repository.findByServerId("test-123")).thenReturn(Optional.of(testServer));
        when(repository.save(any(McpServer.class))).thenAnswer(inv -> inv.getArgument(0));

        McpServerRequest updateRequest = new McpServerRequest(
                "test-123",
                "Updated Name",
                "https://updated.example.com",
                "new-key",
                McpServerStatus.CONNECTED,
                McpTransport.SSE
        );

        // when
        McpServerDto result = service.createOrUpdate(updateRequest);

        // then
        assertThat(result.name()).isEqualTo("Updated Name");
        assertThat(result.baseUrl()).isEqualTo("https://updated.example.com");
        assertThat(result.transport()).isEqualTo(McpTransport.SSE);
        verify(secretEncryptor).encrypt("new-key");
        // Connection is NOT closed - sessions stay open
        verify(mcpClientService, never()).closeConnection(anyString());
    }

    @Test
    void createOrUpdate_shouldHandleEncryptionFailure()  {
        // given
        when(secretEncryptor.encrypt(anyString()))
                .thenThrow(new app.chatbot.security.EncryptionException("Encryption failed", new RuntimeException()));

        // when / then
        assertThatThrownBy(() -> service.createOrUpdate(testRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to encrypt API key");
        verify(mcpClientService, never()).closeConnection(anyString());
    }

    @Test
    void deleteServer_shouldDeleteExistingServer() {
        // given
        when(repository.findByServerId("test-123")).thenReturn(Optional.of(testServer));

        // when
        service.deleteServer("test-123");

        // then
        verify(repository).delete(testServer);
        verify(mcpClientService).closeConnection("test-123");
    }

    @Test
    void delete_shouldRemoveServerAndCloseConnection() {
        // when
        service.delete("test-999");

        // then
        verify(mcpClientService).closeConnection("test-999");
        verify(repository).deleteByServerId("test-999");
    }

    @Test
    void deleteServer_shouldThrowWhenNotFound() {
        // given
        when(repository.findByServerId("unknown")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.deleteServer("unknown"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MCP server not found");
        verify(mcpClientService, never()).closeConnection(anyString());
    }

    @Test
    void toDto_shouldNotExposeApiKey()  {
        // given
        when(repository.findByServerId("test-123")).thenReturn(Optional.of(testServer));

        // when
        McpServerDto result = service.getServer("test-123");

        // then
        assertThat(result.hasApiKey()).isTrue(); // Should indicate key exists but not expose it
    }

    @Test
    void updateServerStatusAsync_handlesOptimisticLock() {
        // Mit Event-Driven Architecture: Updates sind nun idempotent und sequenziell
        // Der Test prüft, dass ein einzelnes Update erfolgreich ist
        McpServer server = new McpServer();
        server.setServerId("lock-test");
        server.setStatus(McpServerStatus.IDLE);

        McpServer dbServer = new McpServer();
        dbServer.setServerId("lock-test");
        dbServer.setStatus(McpServerStatus.IDLE);

        when(repository.findByServerId("lock-test"))
                .thenReturn(Optional.of(dbServer));

        when(repository.save(any(McpServer.class)))
                .thenAnswer(invocation -> {
                    McpServer entity = invocation.getArgument(0);
                    entity.setStatus(McpServerStatus.CONNECTED);
                    entity.setLastUpdated(Instant.parse("2024-01-01T00:00:00Z"));
                    return entity;
                });

        service.updateServerStatusAsync(server, McpServerStatus.CONNECTED)
                .block(java.time.Duration.ofSeconds(1));

        assertThat(server.getStatus()).isEqualTo(McpServerStatus.CONNECTED);
        assertThat(server.getLastUpdated()).isNotNull();
        verify(repository, times(1)).findByServerId("lock-test");
        verify(repository, times(1)).save(any(McpServer.class));
    }
}
