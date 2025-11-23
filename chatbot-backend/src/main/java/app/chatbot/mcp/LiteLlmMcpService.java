package app.chatbot.mcp;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.chatbot.litellm.api.McpApi;
import app.chatbot.litellm.model.LiteLLMMCPServerTable;
import app.chatbot.litellm.model.NewMCPServerRequest;
import app.chatbot.litellm.model.UpdateMCPServerRequest;
import app.chatbot.mcp.dto.McpCapabilitiesResponse;
import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class LiteLlmMcpService {

    private static final Logger log = LoggerFactory.getLogger(LiteLlmMcpService.class);

    private final McpApi mcpApi;
    private final ObjectMapper objectMapper;

    public LiteLlmMcpService(McpApi mcpApi, ObjectMapper objectMapper) {
        this.mcpApi = mcpApi;
        this.objectMapper = objectMapper;
    }

    public Flux<McpServerDto> listServers() {
        return mcpApi.fetchAllMcpServersV1McpServerGet()
                .map(this::mapServer)
                .sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
    }

    public Mono<McpServerDto> getServer(String serverId) {
        return mcpApi.fetchMcpServerV1McpServerServerIdGet(serverId)
                .map(this::mapServer);
    }

    public Mono<McpServerDto> upsertServer(McpServerRequest request) {
        String serverId = normalizedServerId(request);
        if (serverId != null) {
            UpdateMCPServerRequest payload = toUpdateRequest(serverId, request);
            return mcpApi.editMcpServerV1McpServerPut(payload, null)
                    .map(this::mapServer);
        }
        NewMCPServerRequest payload = toNewRequest(request);
        return mcpApi.addMcpServerV1McpServerPost(payload, null)
                .map(this::mapServer);
    }

    public Mono<Void> deleteServer(String serverId) {
        return mcpApi.removeMcpServerV1McpServerServerIdDelete(serverId, null).then();
    }

    public Mono<McpCapabilitiesResponse> getCapabilities(String serverId) {
        return mcpApi.listToolRestApiMcpRestToolsListGet(serverId)
                .map(payload -> objectMapper.valueToTree(payload))
                .map(JsonNode.class::cast)
                .map(node -> mapCapabilities(node, serverId));
    }

    private NewMCPServerRequest toNewRequest(McpServerRequest request) {
        NewMCPServerRequest.TransportEnum transport = mapTransport(request.transport());
        NewMCPServerRequest.AuthTypeEnum authType = mapAuthType(request.authType());

        NewMCPServerRequest payload = new NewMCPServerRequest()
                .serverName(request.name())
                .alias(request.name())
                .description(request.name())
                .transport(transport)
                .authType(authType)
                .url(request.baseUrl())
                .extraHeaders(request.extraHeaders())
                .mcpAccessGroups(request.accessGroups());
        if (StringUtils.hasText(request.serverId())) {
            payload.serverId(request.serverId().trim());
        }
        return payload;
    }

    private UpdateMCPServerRequest toUpdateRequest(String serverId, McpServerRequest request) {
        NewMCPServerRequest.AuthTypeEnum newAuthType = mapAuthType(request.authType());
        NewMCPServerRequest.TransportEnum newTransport = mapTransport(request.transport());
        UpdateMCPServerRequest.AuthTypeEnum authType = newAuthType == null
                ? null
                : UpdateMCPServerRequest.AuthTypeEnum.fromValue(newAuthType.getValue());

        UpdateMCPServerRequest payload = new UpdateMCPServerRequest();
        payload.setServerId(serverId);
        payload.serverName(request.name());
        payload.alias(request.name());
        payload.description(request.name());
        payload.transport(UpdateMCPServerRequest.TransportEnum.fromValue(newTransport.getValue()));
        payload.authType(authType);
        payload.url(request.baseUrl());
        payload.mcpAccessGroups(request.accessGroups());
        return payload;
    }

    private McpServerDto mapServer(LiteLLMMCPServerTable server) {
        String serverId = server.getServerId();
        String name = coalesce(server.getServerName(), server.getAlias(), serverId);
        String baseUrl = server.getUrl();
        McpTransport transport = McpTransport.fromString(server.getTransport() != null ? server.getTransport().getValue().toUpperCase(Locale.ROOT) : null);
        McpServerStatus status = server.getStatus() != null ? McpServerStatus.fromString(server.getStatus().getValue()) : McpServerStatus.IDLE;
        Instant createdAt = toInstant(server.getCreatedAt());
        Instant updatedAt = toInstant(server.getUpdatedAt());
        if (updatedAt == null) {
            updatedAt = createdAt != null ? createdAt : Instant.now();
        }
        if (createdAt == null) {
            createdAt = updatedAt;
        }
        List<String> extraHeaders = server.getExtraHeaders() != null ? server.getExtraHeaders() : Collections.emptyList();
        List<String> accessGroups = server.getMcpAccessGroups() != null ? server.getMcpAccessGroups() : Collections.emptyList();
        return new McpServerDto(
                serverId,
                name,
                baseUrl,
                transport,
                status,
                createdAt,
                updatedAt,
                "never",
                extraHeaders,
                accessGroups
        );
    }

    private McpCapabilitiesResponse mapCapabilities(JsonNode root, String serverId) {
        JsonNode toolsNode = root.path("tools");
        List<McpCapabilitiesResponse.ToolInfo> tools = parseTools(toolsNode);
        List<McpCapabilitiesResponse.ResourceInfo> resources = parseResources(root.path("resources"));
        List<McpCapabilitiesResponse.PromptInfo> prompts = parsePrompts(root.path("prompts"));

        McpCapabilitiesResponse.ServerInfo serverInfo = McpCapabilitiesResponse.ServerInfo.builder()
                .name(serverId)
                .version("unknown")
                .supportsTools(!tools.isEmpty())
                .supportsResources(!resources.isEmpty())
                .supportsPrompts(!prompts.isEmpty())
                .build();

        return McpCapabilitiesResponse.builder()
                .tools(tools)
                .resources(resources)
                .prompts(prompts)
                .serverInfo(serverInfo)
                .build();
    }

    private List<McpCapabilitiesResponse.ToolInfo> parseTools(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return streamArray(node)
                .map(toolNode -> McpCapabilitiesResponse.ToolInfo.builder()
                        .name(toolNode.path("name").asText())
                        .description(toolNode.path("description").asText(null))
                        .inputSchema(toolNode.path("input_schema"))
                        .build())
                .collect(Collectors.toList());
    }

    private List<McpCapabilitiesResponse.ResourceInfo> parseResources(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return streamArray(node)
                .map(resourceNode -> McpCapabilitiesResponse.ResourceInfo.builder()
                        .uri(resourceNode.path("uri").asText())
                        .name(resourceNode.path("name").asText(null))
                        .description(resourceNode.path("description").asText(null))
                        .mimeType(resourceNode.path("mime_type").asText(null))
                        .build())
                .collect(Collectors.toList());
    }

    private List<McpCapabilitiesResponse.PromptInfo> parsePrompts(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return streamArray(node)
                .map(promptNode -> McpCapabilitiesResponse.PromptInfo.builder()
                        .name(promptNode.path("name").asText())
                        .description(promptNode.path("description").asText(null))
                        .arguments(Collections.emptyList())
                        .build())
                .collect(Collectors.toList());
    }

    private java.util.stream.Stream<JsonNode> streamArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return java.util.stream.Stream.empty();
        }
        Iterable<JsonNode> iterable = arrayNode::elements;
        return StreamSupport.stream(iterable.spliterator(), false);
    }

    private Instant toInstant(OffsetDateTime input) {
        return input != null ? input.toInstant() : null;
    }

    private String coalesce(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        if (StringUtils.hasText(second)) {
            return second;
        }
        return fallback;
    }

    private String normalizedServerId(McpServerRequest request) {
        if (StringUtils.hasText(request.serverId())) {
            return request.serverId().trim();
        }
        return null;
    }

    private NewMCPServerRequest.TransportEnum mapTransport(McpTransport transport) {
        if (transport == null) {
            return NewMCPServerRequest.TransportEnum.SSE;
        }
        return switch (transport) {
            case SSE -> NewMCPServerRequest.TransportEnum.SSE;
            case STREAMABLE_HTTP -> NewMCPServerRequest.TransportEnum.HTTP;
        };
    }

    private NewMCPServerRequest.AuthTypeEnum mapAuthType(String authType) {
        if (!StringUtils.hasText(authType)) {
            return null;
        }
        try {
            return NewMCPServerRequest.AuthTypeEnum.fromValue(authType.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.debug("Unknown auth type received from request: {}", authType, ex);
            return null;
        }
    }
}
