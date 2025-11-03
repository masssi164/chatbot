package app.chatbot.mcp;

import app.chatbot.ChatbotBackendApplication;
import app.chatbot.mcp.dto.McpCapabilitiesDto;
import app.chatbot.mcp.dto.McpConnectionStatusDto;
import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ChatbotBackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "mcp.client.connect-timeout=30s",
        "mcp.client.read-timeout=30s"
})
class McpIntegrationE2ETest {

    private static final String TEST_SERVER_NAME = "spring-ai-test-server";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private McpServerRepository mcpServerRepository;

    @LocalServerPort
    private int backendPort;

    private ConfigurableApplicationContext mcpServerContext;
    private String springAiBaseUrl;

    @BeforeAll
    void startSpringAiServer() {
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("server.port", "0"),
                Map.entry("spring.main.web-application-type", "servlet"),
                Map.entry("spring.ai.mcp.server.enabled", "true"),
                Map.entry("spring.ai.mcp.server.protocol", "SSE"),
                Map.entry("spring.ai.mcp.server.name", "integration-test"),
                Map.entry("spring.ai.mcp.server.version", "1.0.0-it"),
                Map.entry("spring.ai.mcp.server.capabilities.resource", "false"),
                Map.entry("spring.ai.mcp.server.capabilities.prompt", "false"),
                Map.entry("spring.ai.mcp.server.capabilities.completion", "false"),
                Map.entry("spring.ai.mcp.server.tool-change-notification", "false"),
                Map.entry("spring.ai.mcp.server.prompt-change-notification", "false"),
                Map.entry("spring.ai.mcp.server.resource-change-notification", "false"),
                Map.entry("spring.ai.mcp.server.sse-endpoint", "/sse"),
                Map.entry("spring.ai.mcp.server.sse-message-endpoint", "/mcp/message")
        );

        mcpServerContext = new SpringApplicationBuilder(TestMcpServerApplication.class)
                .properties(properties)
                .web(WebApplicationType.SERVLET)
                .run();

        String port = Objects.requireNonNull(
                mcpServerContext.getEnvironment().getProperty("local.server.port"));
        springAiBaseUrl = "http://localhost:" + port + "/sse";
    }

    @AfterAll
    void stopSpringAiServer() {
        if (mcpServerContext != null) {
            mcpServerContext.close();
        }
    }

    @AfterEach
    void cleanRepository() {
        mcpServerRepository.deleteAll();
    }

    @Test
    void shouldRegisterMcpServer() throws Exception {
        McpServerRequest request = new McpServerRequest(
                null,
                TEST_SERVER_NAME,
                springAiBaseUrl,
                null,
                null,
                McpTransport.SSE
        );

        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/mcp-servers"), request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        McpServerDto created = objectMapper.readValue(response.getBody(), McpServerDto.class);
        assertThat(created.serverId()).isNotNull();
        assertThat(created.serverId()).startsWith("mcp-");
        assertThat(created.name()).isEqualTo(TEST_SERVER_NAME);
        assertThat(created.baseUrl()).isEqualTo(springAiBaseUrl);
        assertThat(created.transport()).isEqualTo(McpTransport.SSE);
    }

    @Test
    void shouldCompleteFullMcpServerWorkflow() throws Exception {
        McpServerRequest request = new McpServerRequest(
                null,
                TEST_SERVER_NAME,
                springAiBaseUrl,
                null,
                null,
                McpTransport.SSE
        );

        ResponseEntity<String> registerResponse = restTemplate.postForEntity(url("/api/mcp-servers"), request, String.class);
        System.out.println("Register response status=" + registerResponse.getStatusCodeValue() + ", body=" + registerResponse.getBody());
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        McpServerDto server = objectMapper.readValue(registerResponse.getBody(), McpServerDto.class);
        String serverId = server.serverId();

        awaitServerRepositoryStatus(serverId, McpServerStatus.CONNECTED);
        McpConnectionStatusDto connectionStatus = awaitConnectionStatus(serverId);
        assertThat(connectionStatus.status()).isEqualTo(McpServerStatus.CONNECTED);
        assertThat(connectionStatus.toolCount()).isGreaterThanOrEqualTo(0);

        ResponseEntity<String> capabilitiesResponse = restTemplate.getForEntity(
                url("/api/mcp/servers/" + serverId + "/capabilities"), String.class);
        assertThat(capabilitiesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        McpCapabilitiesDto capabilities = objectMapper.readValue(capabilitiesResponse.getBody(), McpCapabilitiesDto.class);
        assertThat(capabilities.serverInfo().name()).isNotEmpty();

        ResponseEntity<String> listResponse = restTemplate.getForEntity(url("/api/mcp-servers"), String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        McpServerDto[] servers = objectMapper.readValue(listResponse.getBody(), McpServerDto[].class);
        assertThat(Arrays.stream(servers).map(McpServerDto::serverId)).contains(serverId);
    }

    @Test
    void shouldUpdateExistingMcpServer() throws Exception {
        McpServerRequest createRequest = new McpServerRequest(
                null,
                "Original Name",
                springAiBaseUrl,
                null,
                null,
                McpTransport.SSE
        );

        ResponseEntity<String> createResult = restTemplate.postForEntity(url("/api/mcp-servers"), createRequest, String.class);
        assertThat(createResult.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        McpServerDto created = objectMapper.readValue(createResult.getBody(), McpServerDto.class);

        McpServerRequest updateRequest = new McpServerRequest(
                created.serverId(),
                "Updated Name",
                springAiBaseUrl,
                null,
                null,
                McpTransport.SSE
        );

        ResponseEntity<String> updateResult = restTemplate.exchange(
                url("/api/mcp-servers/" + created.serverId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest),
                String.class);
        assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        McpServerDto updated = objectMapper.readValue(updateResult.getBody(), McpServerDto.class);
        assertThat(updated.serverId()).isEqualTo(created.serverId());
        assertThat(updated.name()).isEqualTo("Updated Name");
    }

    @Test
    void shouldDeleteMcpServer() throws Exception {
        McpServerRequest request = new McpServerRequest(
                null,
                "To Be Deleted",
                springAiBaseUrl,
                null,
                null,
                McpTransport.SSE
        );

        ResponseEntity<String> createResult = restTemplate.postForEntity(url("/api/mcp-servers"), request, String.class);
        assertThat(createResult.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        McpServerDto created = objectMapper.readValue(createResult.getBody(), McpServerDto.class);

        restTemplate.delete(url("/api/mcp-servers/" + created.serverId()));
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> mcpServerRepository.findByServerId(created.serverId()).isEmpty());
    }

    private McpConnectionStatusDto awaitConnectionStatus(String serverId) throws Exception {
        long deadline = System.currentTimeMillis() + 20000L;
        McpConnectionStatusDto lastStatus = null;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<String> verifyResponse = restTemplate.postForEntity(
                    url("/api/mcp-servers/" + serverId + "/verify"),
                    null,
                    String.class);

            HttpStatusCode statusCode = verifyResponse.getStatusCode();
            if (statusCode.equals(HttpStatus.NOT_FOUND)) {
                Thread.sleep(500);
                continue;
            }

            if (!statusCode.equals(HttpStatus.OK)) {
                throw new AssertionError("Unexpected HTTP status from /verify: " + statusCode
                        + ", body=" + verifyResponse.getBody());
            }

            lastStatus = objectMapper.readValue(verifyResponse.getBody(), McpConnectionStatusDto.class);
            if (lastStatus.status() == McpServerStatus.CONNECTED) {
                return lastStatus;
            }
            Thread.sleep(1000);
        }
        return lastStatus != null
                ? lastStatus
                : new McpConnectionStatusDto(McpServerStatus.ERROR, 0,
                        "Connection did not reach CONNECTED within timeout");
    }

    private void awaitServerRepositoryStatus(String serverId, McpServerStatus expectedStatus) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> mcpServerRepository.findByServerId(serverId)
                        .map(McpServer::getStatus)
                        .map(status -> status == expectedStatus)
                        .orElse(false));
    }

    private String url(String path) {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://localhost:" + backendPort + path;
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
