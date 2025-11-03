package app.chatbot.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import app.chatbot.ChatbotBackendApplication;
import app.chatbot.mcp.dto.McpCapabilitiesResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end tests that verify our MCP client integration against an actual Spring AI
 * MCP server for both SSE and Streamable HTTP transports.
 */
@SpringBootTest(classes = ChatbotBackendApplication.class)
class McpSpringAiServerE2ETest {

    @Autowired
    private McpConnectionService mcpConnectionService;

    @Autowired
    private McpServerRepository mcpServerRepository;

    @Autowired
    private McpSessionRegistry mcpSessionRegistry;

    @Autowired
    private McpToolController mcpToolController;

    private final ArrayList<String> registeredServerIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        registeredServerIds.forEach(id -> mcpSessionRegistry.closeSession(id).block(Duration.ofSeconds(5)));
        registeredServerIds.clear();
        mcpServerRepository.deleteAll();
    }

    @Test
    void shouldConnectToSpringAiSseServer() {
        try (ConfigurableApplicationContext context = startMcpServer("SSE")) {
            int port = Integer.parseInt(Objects.requireNonNull(context.getEnvironment().getProperty("local.server.port")));
            String baseUrl = "http://localhost:%d/sse".formatted(port);

            McpServer server = createServerEntity("spring-ai-sse", baseUrl, McpTransport.SSE);
            mcpConnectionService.connectAndSync(server.getServerId());

            McpServer persisted = mcpServerRepository.findByServerId(server.getServerId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(McpServerStatus.CONNECTED);
            assertThat(persisted.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
            assertThat(persisted.getToolsCache()).contains("echo");

            assertCapabilitiesExposeOnlyTools(server.getServerId());
        }
    }

    @Test
    void shouldConnectToSpringAiStreamableHttpServer() {
        try (ConfigurableApplicationContext context = startMcpServer("STREAMABLE")) {
            int port = Integer.parseInt(Objects.requireNonNull(context.getEnvironment().getProperty("local.server.port")));
            String baseUrl = "http://localhost:%d/mcp".formatted(port);

            McpServer server = createServerEntity("spring-ai-streamable", baseUrl, McpTransport.STREAMABLE_HTTP);
            mcpConnectionService.connectAndSync(server.getServerId());

            McpServer persisted = mcpServerRepository.findByServerId(server.getServerId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(McpServerStatus.CONNECTED);
            assertThat(persisted.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
            assertThat(persisted.getToolsCache()).contains("echo");

            assertCapabilitiesExposeOnlyTools(server.getServerId());
        }
    }

    private void assertCapabilitiesExposeOnlyTools(String serverId) {
        ResponseEntity<McpCapabilitiesResponse> response = mcpToolController.getCapabilities(serverId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        McpCapabilitiesResponse capabilities = response.getBody();
        assertThat(capabilities).isNotNull();
        assertThat(capabilities.tools()).extracting(McpCapabilitiesResponse.ToolInfo::name)
                .contains("echo");
        assertThat(capabilities.resources()).isEmpty();
        assertThat(capabilities.prompts()).isEmpty();
        assertThat(capabilities.serverInfo().supportsTools()).isTrue();
        assertThat(capabilities.serverInfo().supportsResources()).isFalse();
        assertThat(capabilities.serverInfo().supportsPrompts()).isFalse();
    }

    private ConfigurableApplicationContext startMcpServer(String protocol) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", "0");
        properties.put("spring.main.web-application-type", "servlet");
        properties.put("spring.ai.mcp.server.enabled", "true");
        properties.put("spring.ai.mcp.server.protocol", protocol);
        properties.put("spring.ai.mcp.server.name", "test-" + protocol.toLowerCase(Locale.ROOT));
        properties.put("spring.ai.mcp.server.version", "1.0.0-test");
        properties.put("spring.ai.mcp.server.capabilities.resource", "false");
        properties.put("spring.ai.mcp.server.capabilities.prompt", "false");
        properties.put("spring.ai.mcp.server.capabilities.completion", "false");
        properties.put("spring.ai.mcp.server.tool-change-notification", "false");
        properties.put("spring.ai.mcp.server.prompt-change-notification", "false");
        properties.put("spring.ai.mcp.server.resource-change-notification", "false");

        if ("SSE".equals(protocol)) {
            properties.put("spring.ai.mcp.server.sse-endpoint", "/sse");
            properties.put("spring.ai.mcp.server.sse-message-endpoint", "/mcp/message");
        }
        if ("STREAMABLE".equals(protocol)) {
            properties.put("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
        }

        return new SpringApplicationBuilder(TestMcpServerApplication.class)
                .properties(properties)
                .web(WebApplicationType.SERVLET)
                .run();
    }

    private McpServer createServerEntity(String id, String baseUrl, McpTransport transport) {
        McpServer server = new McpServer();
        server.setServerId(id);
        server.setName(id);
        server.setBaseUrl(baseUrl);
        server.setTransport(transport);
        mcpServerRepository.save(server);
        registeredServerIds.add(id);
        return server;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(TestMcpServerTools.class)
    static class TestMcpServerApplication {
    }

    @Component
    static class TestMcpServerTools {

        @McpTool(name = "echo", description = "Echo the provided text")
        public String echo(@McpToolParam(description = "Text to echo", required = true) String text) {
            return text;
        }
    }
}
