package app.chatbot.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;


class OpenAiProxyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiProxyService service;

    @BeforeEach
    void setUp() {
        OpenAiProperties properties = new OpenAiProperties(
                "http://localhost:1234/v1",
                "test-secret",
                "title-model",
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(120)
        );
        RestTemplate restTemplate = new RestTemplateBuilder()
                .rootUri(properties.baseUrl())
                .build();
        server = MockRestServiceServer.createServer(restTemplate);
        service = new OpenAiProxyService(restTemplate, properties);
    }

    @Test
    void createResponseForwardsPayloadAndHeaders() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"model":"gpt","input":[{"role":"user","content":"Hi"}]}
                """);

        server.expect(requestTo("http://localhost:1234/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("OpenAI-Beta", "responses=v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer override-token"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(MockRestResponseCreators.withSuccess("{\"id\":\"resp_1\"}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = service.createResponse(payload, "Bearer override-token");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("resp_1");
        server.verify();
    }

    @Test
    void listModelsFallsBackToConfiguredApiKey() {
        server.expect(requestTo("http://localhost:1234/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-secret"))
                .andRespond(MockRestResponseCreators.withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = service.listModels(null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"data\"");
        server.verify();
    }

    @Test
    void chatCompletionDoesNotSendResponsesHeader() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"model":"gpt","messages":[{"role":"user","content":"Hi"}]}
                """);

        server.expect(requestTo("http://localhost:1234/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-secret"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(request ->
                        assertThat(request.getHeaders().containsKey("OpenAI-Beta")).isFalse())
                .andRespond(MockRestResponseCreators.withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = service.createChatCompletion(payload, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        server.verify();
    }

    @Test
    void propagatesRemoteErrorResponse() {
        server.expect(requestTo("http://localhost:1234/v1/models"))
                .andRespond(MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"bad request\"}"));

        ResponseEntity<String> response = service.listModels("Bearer x");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("bad request");
    }

    @Test
    void wrapsClientExceptionAsBadGateway() {
        server.expect(requestTo("http://localhost:1234/v1/responses"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        JsonNode payload = objectMapper.createObjectNode();

        assertThatThrownBy(() -> service.createResponse(payload, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Failed to reach LLM at")
                .satisfies(ex ->
                        assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(502)
                );
    }

    @Test
    void wrapsSocketTimeoutWithFriendlyMessage() {
        server.expect(requestTo("http://localhost:1234/v1/chat/completions"))
                .andRespond(request -> {
                    throw new ResourceAccessException("timeout",
                            new SocketTimeoutException("Read timed out"));
                });

        JsonNode payload = objectMapper.createObjectNode();

        assertThatThrownBy(() -> service.createChatCompletion(payload, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException statusException = (ResponseStatusException) ex;
                    assertThat(statusException.getStatusCode().value()).isEqualTo(502);
                    assertThat(statusException.getReason())
                            .contains("LLM request timed out");
                });
    }
}
