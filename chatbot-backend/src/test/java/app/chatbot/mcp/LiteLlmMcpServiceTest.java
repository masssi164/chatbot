package app.chatbot.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.chatbot.litellm.api.McpApi;
import app.chatbot.litellm.model.LiteLLMMCPServerTable;
import app.chatbot.litellm.model.LiteLLMMCPServerTable.StatusEnum;
import app.chatbot.litellm.model.LiteLLMMCPServerTable.TransportEnum;
import app.chatbot.litellm.model.NewMCPServerRequest;
import app.chatbot.litellm.model.UpdateMCPServerRequest;
import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class LiteLlmMcpServiceTest {

    private McpApi mcpApi;
    private LiteLlmMcpService service;

    @BeforeEach
    void setUp() {
        mcpApi = Mockito.mock(McpApi.class);
        service = new LiteLlmMcpService(mcpApi, new ObjectMapper());
    }

    @Test
    void listServersMapsLiteLlmPayload() {
        LiteLLMMCPServerTable server = new LiteLLMMCPServerTable()
                .serverId("n8n")
                .serverName("n8n Dev")
                .url("http://n8n:5678")
                .transport(TransportEnum.SSE)
                .status(StatusEnum.CONNECTED)
                .createdAt(OffsetDateTime.ofInstant(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.ofInstant(Instant.parse("2024-01-02T00:00:00Z"), ZoneOffset.UTC))
                .extraHeaders(List.of("x-user-id"))
                .mcpAccessGroups(List.of("session-42"));

        when(mcpApi.fetchAllMcpServersV1McpServerGet()).thenReturn(Flux.just(server));

        StepVerifier.create(service.listServers())
                .assertNext(dto -> {
                    assertThat(dto.serverId()).isEqualTo("n8n");
                    assertThat(dto.name()).isEqualTo("n8n Dev");
                    assertThat(dto.baseUrl()).isEqualTo("http://n8n:5678");
                    assertThat(dto.transport()).isEqualTo(McpTransport.SSE);
                    assertThat(dto.status()).isEqualTo(McpServerStatus.CONNECTED);
                    assertThat(dto.extraHeaders()).containsExactly("x-user-id");
                    assertThat(dto.accessGroups()).containsExactly("session-42");
                })
                .verifyComplete();
    }

    @Test
    void upsertServerUsesCreateForNewEntries() {
        LiteLLMMCPServerTable server = new LiteLLMMCPServerTable().serverId("new-server");
        when(mcpApi.addMcpServerV1McpServerPost(any(NewMCPServerRequest.class), eq(null)))
                .thenReturn(Mono.just(server));

        McpServerRequest request = new McpServerRequest(
                null,
                "New Server",
                "http://localhost",
                McpTransport.SSE,
                "api_key",
                "secret",
                Map.of(),
                List.of("header"),
                List.of("group"),
                "never");

        StepVerifier.create(service.upsertServer(request))
                .assertNext(dto -> assertThat(dto.serverId()).isEqualTo("new-server"))
                .verifyComplete();

        ArgumentCaptor<NewMCPServerRequest> payload = ArgumentCaptor.forClass(NewMCPServerRequest.class);
        verify(mcpApi).addMcpServerV1McpServerPost(payload.capture(), eq(null));
        assertThat(payload.getValue().getServerName()).isEqualTo("New Server");
        assertThat(payload.getValue().getTransport()).isEqualTo(NewMCPServerRequest.TransportEnum.SSE);
    }

    @Test
    void upsertServerUsesUpdateWhenServerIdPresent() {
        LiteLLMMCPServerTable server = new LiteLLMMCPServerTable().serverId("existing");
        when(mcpApi.editMcpServerV1McpServerPut(any(UpdateMCPServerRequest.class), eq(null)))
                .thenReturn(Mono.just(server));

        McpServerRequest request = new McpServerRequest(
                "existing",
                "Updated",
                "http://localhost",
                McpTransport.STREAMABLE_HTTP,
                null,
                null,
                Map.of(),
                List.of(),
                List.of(),
                "never");

        StepVerifier.create(service.upsertServer(request))
                .assertNext(dto -> assertThat(dto.serverId()).isEqualTo("existing"))
                .verifyComplete();

        ArgumentCaptor<UpdateMCPServerRequest> payload = ArgumentCaptor.forClass(UpdateMCPServerRequest.class);
        verify(mcpApi).editMcpServerV1McpServerPut(payload.capture(), eq(null));
        assertThat(payload.getValue().getServerId()).isEqualTo("existing");
        assertThat(payload.getValue().getTransport()).isEqualTo(UpdateMCPServerRequest.TransportEnum.HTTP);
    }

    @Test
    void getCapabilitiesMapsResponse() {
        Map<String, Object> response = Map.of(
                "tools", List.of(Map.of("name", "workflow", "description", "Runs workflow", "input_schema", Map.of())),
                "resources", List.of(),
                "prompts", List.of()
        );

        when(mcpApi.listToolRestApiMcpRestToolsListGet("n8n")).thenReturn(Mono.just(response));

        StepVerifier.create(service.getCapabilities("n8n"))
                .assertNext(capabilities -> {
                    assertThat(capabilities.tools()).hasSize(1);
                    assertThat(capabilities.tools().get(0).name()).isEqualTo("workflow");
                })
                .verifyComplete();
    }

    @Test
    void deleteServerDelegatesToApi() {
        when(mcpApi.removeMcpServerV1McpServerServerIdDelete("server", null)).thenReturn(Mono.empty());

        StepVerifier.create(service.deleteServer("server")).verifyComplete();
        verify(mcpApi).removeMcpServerV1McpServerServerIdDelete("server", null);
    }
}
