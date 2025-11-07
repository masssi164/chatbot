package app.chatbot.responses;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import org.mockito.Mockito;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.config.OpenAiProperties;
import app.chatbot.conversation.Conversation;
import app.chatbot.conversation.ConversationService;
import app.chatbot.conversation.Message;
import app.chatbot.conversation.MessageRole;
import app.chatbot.conversation.ToolCall;
import app.chatbot.conversation.ToolCallStatus;
import app.chatbot.conversation.ToolCallType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ResponseStreamServiceTest {

    private ConversationService conversationService;

    private ToolDefinitionProvider toolDefinitionProvider;

    private ObjectMapper objectMapper;

    private ResponseStreamService service;

    private AtomicReference<ClientRequest> recordedRequest;
    private AtomicReference<String> recordedBody;

    private static final List<HttpMessageWriter<?>> MESSAGE_WRITERS =
            ExchangeStrategies.withDefaults().messageWriters();
    private String ssePayload;

    @BeforeEach
    void setUp() {
        this.conversationService = Mockito.mock(ConversationService.class);
        this.toolDefinitionProvider = Mockito.mock(ToolDefinitionProvider.class);
        this.objectMapper = new ObjectMapper();
        this.recordedRequest = new AtomicReference<>();
        this.recordedBody = new AtomicReference<>();

        WebClient client = WebClient.builder()
                .exchangeFunction(exchangeFunction())
                .build();

        OpenAiProperties properties = new OpenAiProperties();
        properties.setBaseUrl("http://localhost:1234/v1");

        Mockito.when(toolDefinitionProvider.listTools())
                .thenReturn(Flux.just(objectMapper.createObjectNode().put("type", "mcp")));

        service = new ResponseStreamService(client, properties, conversationService, toolDefinitionProvider, objectMapper);
        this.ssePayload = buildMcpPayload();
    }

    private ExchangeFunction exchangeFunction() {
        return request -> {
            recordedRequest.set(request);
            captureRequestBody(request);
            String payload = this.ssePayload;
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(payload.getBytes(StandardCharsets.UTF_8));
            ClientResponse response = ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(Flux.just(buffer))
                    .build();
            return Mono.just(response);
        };
    }

    private void captureRequestBody(ClientRequest request) {
        MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
        request.body().insert(mock, new BodyInserterContext()).block(Duration.ofSeconds(5));
        recordedBody.set(mock.getBodyAsString().block(Duration.ofSeconds(5)));
    }

    private static final class BodyInserterContext implements org.springframework.web.reactive.function.BodyInserter.Context {

        @Override
        public List<HttpMessageWriter<?>> messageWriters() {
            return MESSAGE_WRITERS;
        }

        @Override
        public Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
            return Optional.empty();
        }

        @Override
        public Map<String, Object> hints() {
            return Map.of();
        }
    }

    private String buildMcpPayload() {
        ObjectNode addedItem = objectMapper.createObjectNode();
        ObjectNode item = addedItem.putObject("item");
        item.put("type", "mcp_call");
        item.put("id", "item-1");
        item.put("name", "demo-tool");
        addedItem.put("output_index", 0);

        ObjectNode argsDelta = objectMapper.createObjectNode();
        argsDelta.put("item_id", "item-1");
        argsDelta.put("output_index", 0);
        argsDelta.put("delta", "{\"message\":\"");

        ObjectNode argsDone = objectMapper.createObjectNode();
        argsDone.put("item_id", "item-1");
        argsDone.put("output_index", 0);
        argsDone.put("arguments", "{\"message\":\"hello\"}");

        ObjectNode inProgress = objectMapper.createObjectNode();
        inProgress.put("item_id", "item-1");
        inProgress.put("output_index", 0);

        ObjectNode completed = objectMapper.createObjectNode();
        completed.put("item_id", "item-1");
        completed.put("output_index", 0);

        ObjectNode textDelta = objectMapper.createObjectNode();
        textDelta.put("output_index", 0);
        textDelta.put("item_id", "msg-1");
        textDelta.put("delta", "Hello");

        ObjectNode textDone = objectMapper.createObjectNode();
        textDone.put("output_index", 0);
        textDone.put("item_id", "msg-1");
        textDone.put("text", "Hello");

        return new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.mcp_call_arguments.delta", argsDelta))
                .append(encodeEvent("response.mcp_call_arguments.done", argsDone))
                .append(encodeEvent("response.mcp_call.in_progress", inProgress))
                .append(encodeEvent("response.mcp_call.completed", completed))
                .append(encodeEvent("response.output_text.delta", textDelta))
                .append(encodeEvent("response.output_text.done", textDone))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
    }

    private String buildFunctionPayload() {
        ObjectNode addedItem = objectMapper.createObjectNode();
        ObjectNode item = addedItem.putObject("item");
        item.put("type", "function_call");
        item.put("id", "fn-1");
        item.put("name", "lookup");
        addedItem.put("output_index", 1);

        ObjectNode argsDelta = objectMapper.createObjectNode();
        argsDelta.put("item_id", "fn-1");
        argsDelta.put("output_index", 1);
        argsDelta.put("delta", "{\"query\":\"hel");

        ObjectNode argsDone = objectMapper.createObjectNode();
        argsDone.put("item_id", "fn-1");
        argsDone.put("output_index", 1);
        argsDone.put("arguments", "{\"query\":\"hello\"}");

        ObjectNode textDelta = objectMapper.createObjectNode();
        textDelta.put("output_index", 1);
        textDelta.put("item_id", "fn-msg");
        textDelta.put("delta", "Answer");

        ObjectNode textDone = objectMapper.createObjectNode();
        textDone.put("output_index", 1);
        textDone.put("item_id", "fn-msg");
        textDone.put("text", "Answer");

        return new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.function_call_arguments.delta", argsDelta))
                .append(encodeEvent("response.function_call_arguments.done", argsDone))
                .append(encodeEvent("response.output_text.delta", textDelta))
                .append(encodeEvent("response.output_text.done", textDone))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
    }

    private String buildMcpFailurePayload() {
        ObjectNode addedItem = objectMapper.createObjectNode();
        ObjectNode item = addedItem.putObject("item");
        item.put("type", "mcp_call");
        item.put("id", "mcp-fail");
        item.put("name", "failing-tool");
        addedItem.put("output_index", 2);

        ObjectNode failed = objectMapper.createObjectNode();
        failed.put("item_id", "mcp-fail");
        failed.put("output_index", 2);
        failed.put("error", "timeout");

        return new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.mcp_call.failed", failed))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
    }

    private String buildToolOutputPayload() {
        ObjectNode addedItem = objectMapper.createObjectNode();
        ObjectNode item = addedItem.putObject("item");
        item.put("type", "tool_output");
        item.put("id", "tool-out");
        item.put("role", "tool");
        item.putArray("content").addObject().put("text", "output from tool");
        addedItem.put("output_index", 4);

        return new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
    }

    private String encodeEvent(String event, ObjectNode payload) {
        return "event: " + event + "\n" +
                "data: " + payload.toString() + "\n\n";
    }

    @Test
    void shouldStreamEventsAndPersistUpdates() throws Exception {
        Conversation conversation = Conversation.builder()
                .id(99L)
                .title("Test")
                .build();

        given(conversationService.ensureConversation(null, null))
                .willReturn(Mono.just(conversation));

        given(conversationService.upsertToolCall(any(), any(), any(), any(), any()))
                .willReturn(Mono.just(ToolCall.builder()
                        .id(1L)
                        .conversationId(99L)
                        .type(ToolCallType.MCP)
                        .status(ToolCallStatus.IN_PROGRESS)
                        .build()));

        given(conversationService.updateMessageContent(any(), any(), any(), any(), any()))
                .willReturn(Mono.just(Message.builder()
                        .id(2L)
                        .conversationId(99L)
                        .role(MessageRole.ASSISTANT)
                        .content("Hello")
                        .build()));

        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        ResponseStreamRequest request = new ResponseStreamRequest(null, null,
                objectMapper.createObjectNode().put("model", "gpt-test"));

        StepVerifier.create(service.streamResponses(request, null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        verify(conversationService, times(1)).ensureConversation(null, null);
        verify(conversationService, times(1)).updateMessageContent(any(), any(), any(), any(), any());
        verify(conversationService, times(4)).upsertToolCall(any(), any(), any(), any(), any());

        ClientRequest recorded = recordedRequest.get();
        assertThat(recorded).isNotNull();
        assertThat(recorded.url().getPath()).isEqualTo("/responses");

        String body = recordedBody.get();
        assertThat(body).isNotBlank();
        ObjectNode node = (ObjectNode) objectMapper.readTree(body);
        assertThat(node.path("stream").asBoolean()).isTrue();
        assertThat(node.path("tools").isArray()).isTrue();
        boolean containsMcp = Flux.fromIterable(node.path("tools"))
                .map(json -> json.path("type").asText())
                .any(type -> "mcp".equals(type))
                .blockOptional()
                .orElse(false);
        assertThat(containsMcp).isTrue();
    }

    @Test
    void shouldHandleFunctionCallEvents() throws Exception {
        this.ssePayload = buildFunctionPayload();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder()
                .id(5L)
                .title("Fn")
                .build();

        given(conversationService.ensureConversation(null, null))
                .willReturn(Mono.just(conversation));

        given(conversationService.upsertToolCall(any(), any(), any(), any(), any()))
                .willAnswer(invocation -> Mono.just(ToolCall.builder()
                        .conversationId(conversation.getId())
                        .type(invocation.getArgument(2))
                        .status(ToolCallStatus.IN_PROGRESS)
                        .build()));

        given(conversationService.updateMessageContent(any(), any(), any(), any(), any()))
                .willReturn(Mono.just(Message.builder()
                        .conversationId(conversation.getId())
                        .role(MessageRole.ASSISTANT)
                        .content("Answer")
                        .build()));

        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode().put("model", "gpt-test")), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        ArgumentCaptor<ToolCallType> typeCaptor = ArgumentCaptor.forClass(ToolCallType.class);
        verify(conversationService, times(1)).ensureConversation(null, null);
        verify(conversationService, times(1))
                .updateMessageContent(Mockito.eq(conversation.getId()), Mockito.eq("fn-msg"), Mockito.eq("Answer"), any(), Mockito.eq(1));
        verify(conversationService, times(2))
                .upsertToolCall(any(), any(), typeCaptor.capture(), any(), any());
        assertThat(typeCaptor.getAllValues()).contains(ToolCallType.FUNCTION);

        ObjectNode node = (ObjectNode) objectMapper.readTree(recordedBody.get());
        assertThat(node.path("stream").asBoolean()).isTrue();
        assertThat(node.has("tools")).isFalse();
    }

    @Test
    void shouldHandleMcpFailureEvent() throws Exception {
        this.ssePayload = buildMcpFailurePayload();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(7L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));
        List<Map<String, Object>> capturedAttributes = new java.util.ArrayList<>();
        given(conversationService.upsertToolCall(any(), any(), any(), any(), any()))
                .willAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> attrs = invocation.getArgument(4, Map.class);
                    ToolCallStatus status = (ToolCallStatus) attrs.getOrDefault("status", ToolCallStatus.IN_PROGRESS);
                    capturedAttributes.add(Map.copyOf(attrs));
                    return Mono.just(ToolCall.builder()
                            .conversationId(conversation.getId())
                            .type(invocation.getArgument(2))
                            .status(status)
                            .build());
                });

        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("tools");

        List<ServerSentEvent<String>> events = service.streamResponses(new ResponseStreamRequest(null, null, payload), null)
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).event()).isEqualTo("conversation.ready");

        verify(conversationService, times(2))
                .upsertToolCall(any(), any(), any(), any(), any());
        assertThat(capturedAttributes).hasSize(2);
        assertThat(capturedAttributes.get(1).get("status")).isEqualTo(ToolCallStatus.FAILED);
        assertThat((String) capturedAttributes.get(1).get("resultJson")).contains("timeout");

        ObjectNode node = (ObjectNode) objectMapper.readTree(recordedBody.get());
        assertThat(node.path("stream").asBoolean()).isTrue();
    }

    @Test
    void shouldAppendToolOutputMessages() {
        this.ssePayload = buildToolOutputPayload();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(8L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.appendMessage(any(), any(), any(), any(), any(), any()))
                .willAnswer(invocation -> Mono.just(Message.builder()
                        .conversationId(conversation.getId())
                        .role(invocation.getArgument(1))
                        .content(invocation.getArgument(2))
                        .build()));
        given(conversationService.upsertToolCall(any(), any(), any(), any(), any()))
                .willReturn(Mono.empty());
        given(conversationService.updateMessageContent(any(), any(), any(), any(), any()))
                .willReturn(Mono.empty());
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        verify(conversationService).appendMessage(Mockito.eq(conversation.getId()), Mockito.eq(MessageRole.TOOL),
                Mockito.eq("output from tool"), any(), Mockito.eq(4), Mockito.eq("tool-out"));
    }

    @Test
    void shouldRejectNonObjectPayload() {
        ResponseStreamRequest request = new ResponseStreamRequest(null, null, objectMapper.getNodeFactory().textNode("plain"));

        StepVerifier.create(service.streamResponses(request, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void shouldHandleEmptySSEPayload() throws Exception {
        this.ssePayload = encodeEvent("response.completed", objectMapper.createObjectNode());
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(6L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode().put("model", "test")), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();
    }

    @Test
    void shouldHandleNullPayload() {
        ResponseStreamRequest request = new ResponseStreamRequest(null, null, null);

        StepVerifier.create(service.streamResponses(request, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void shouldUseProvidedAuthHeader() throws Exception {
        this.ssePayload = encodeEvent("response.completed", objectMapper.createObjectNode());
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(9L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode().put("model", "test")), "Bearer custom-token"))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        assertThat(recordedRequest.get().headers().get(org.springframework.http.HttpHeaders.AUTHORIZATION))
                .containsExactly("Bearer custom-token");
    }

    @Test
    void shouldHandleResponseCreatedEvent() throws Exception {
        ObjectNode responseCreated = objectMapper.createObjectNode();
        responseCreated.put("id", "resp-123");

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.created", responseCreated))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(10L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.updateConversationResponseId(any(), any())).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        // Verify that updateConversationResponseId was called
        verify(conversationService, times(1)).updateConversationResponseId(any(), any());
    }

    @Test
    void shouldHandleResponseIncompleteEvent() throws Exception {
        ObjectNode incomplete = objectMapper.createObjectNode();
        incomplete.put("reason", "timeout");

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.incomplete", incomplete))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(11L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        verify(conversationService).finalizeConversation(any(), any(), any(), any());
    }

    @Test
    void shouldHandleResponseFailedEvent() throws Exception {
        ObjectNode failed = objectMapper.createObjectNode();
        failed.put("error", "rate_limit");

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.failed", failed))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(12L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        verify(conversationService).finalizeConversation(any(), any(), any(), any());
    }

    @Test
    void shouldHandleErrorEvent() throws Exception {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("message", "Something went wrong");

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("error", error))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(13L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();
    }

    @Test
    void shouldHandleTextDeltaWithEmptyText() throws Exception {
        ObjectNode textDelta = objectMapper.createObjectNode();
        textDelta.put("output_index", 0);
        textDelta.put("item_id", "msg-empty");
        textDelta.put("delta", "");

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.output_text.delta", textDelta))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(14L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();
    }

    @Test
    void shouldHandleOutputItemWithUnknownType() throws Exception {
        ObjectNode addedItem = objectMapper.createObjectNode();
        ObjectNode item = addedItem.putObject("item");
        item.put("type", "unknown_type");
        item.put("id", "item-unknown");
        addedItem.put("output_index", 0);

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(15L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.appendMessage(any(), any(), any(), any(), any(), any()))
                .willReturn(Mono.just(Message.builder().build()));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();
    }

    @Test
    void shouldHandleOutputItemWithOutputTextType() throws Exception {
        ObjectNode addedItem = objectMapper.createObjectNode();
        ObjectNode item = addedItem.putObject("item");
        item.put("type", "output_text");
        item.put("id", "item-text");
        addedItem.put("output_index", 0);

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(16L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();
    }

    @Test
    void shouldHandleSendApprovalResponse() {
        Conversation conversation = Conversation.builder()
                .id(17L)
                .responseId("resp-approval-test")
                .build();

        given(conversationService.ensureConversation(17L, null))
                .willReturn(Mono.just(conversation));
        given(conversationService.updateConversationResponseId(any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        ObjectNode responseCreated = objectMapper.createObjectNode();
        responseCreated.put("id", "resp-new");

        String approvalPayload = new StringBuilder()
                .append(encodeEvent("response.created", responseCreated))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();

        // Update the exchange function to return approval response
        AtomicReference<ClientRequest> approvalRequest = new AtomicReference<>();
        WebClient approvalClient = WebClient.builder()
                .exchangeFunction(request -> {
                    approvalRequest.set(request);
                    DataBuffer buffer = new DefaultDataBufferFactory().wrap(approvalPayload.getBytes(StandardCharsets.UTF_8));
                    ClientResponse response = ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                            .body(Flux.just(buffer))
                            .build();
                    return Mono.just(response);
                })
                .build();

        OpenAiProperties properties = new OpenAiProperties();
        properties.setBaseUrl("http://localhost:1234/v1");

        ResponseStreamService approvalService = new ResponseStreamService(
                approvalClient, properties, conversationService, toolDefinitionProvider, objectMapper);

        StepVerifier.create(approvalService.sendApprovalResponse(17L, "apreq-123", true, "User approved"))
                .expectNextMatches(event -> "response.created".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        verify(conversationService).ensureConversation(17L, null);
    }

    @Test
    void shouldHandleSendApprovalResponseWithoutResponseId() {
        Conversation conversation = Conversation.builder()
                .id(18L)
                .responseId(null)
                .build();

        given(conversationService.ensureConversation(18L, null))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.sendApprovalResponse(18L, "apreq-456", false, null))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void shouldHandleSendApprovalResponseWithEmptyResponseId() {
        Conversation conversation = Conversation.builder()
                .id(19L)
                .responseId("")
                .build();

        given(conversationService.ensureConversation(19L, null))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.sendApprovalResponse(19L, "apreq-789", true, "Reason"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void shouldHandleTextDoneWithEmptyAccumulatedText() throws Exception {
        ObjectNode textDone = objectMapper.createObjectNode();
        textDone.put("output_index", 0);
        textDone.put("item_id", "msg-no-text");
        textDone.put("text", "");

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.output_text.done", textDone))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(21L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        // updateMessageContent should NOT be called for empty text
        verify(conversationService, times(0)).updateMessageContent(any(), any(), any(), any(), any());
    }

    @Test
    void shouldHandleOutputItemWithMissingType() throws Exception {
        ObjectNode addedItem = objectMapper.createObjectNode();
        addedItem.putObject("item"); // No type field
        addedItem.put("output_index", 0);

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(22L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();
    }

    @Test
    void shouldHandleOutputItemWithNullOutputIndex() throws Exception {
        ObjectNode addedItem = objectMapper.createObjectNode();
        ObjectNode item = addedItem.putObject("item");
        item.put("type", "function_call");
        item.put("id", "fn-null-idx");
        item.put("name", "test_func");
        // No output_index field

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(23L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.upsertToolCall(any(), any(), any(), any(), any()))
                .willReturn(Mono.just(ToolCall.builder().build()));
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();
    }

    @Test
    void shouldHandleFunctionCallWithoutCallId() throws Exception {
        ObjectNode addedItem = objectMapper.createObjectNode();
        ObjectNode item = addedItem.putObject("item");
        item.put("type", "function_call");
        item.put("id", "fn-no-callid");
        item.put("name", "test_func");
        // No call_id field - should fall back to item id
        addedItem.put("output_index", 1);

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(24L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.upsertToolCall(any(), any(), any(), any(), any()))
                .willAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> attrs = invocation.getArgument(4, Map.class);
                    // Verify call_id falls back to item_id
                    assertThat(attrs.get("callId")).isEqualTo("fn-no-callid");
                    return Mono.just(ToolCall.builder().build());
                });
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();
    }

    @Test
    void shouldHandleMcpCallWithEmptyCallId() throws Exception {
        ObjectNode addedItem = objectMapper.createObjectNode();
        ObjectNode item = addedItem.putObject("item");
        item.put("type", "mcp_call");
        item.put("id", "mcp-empty-callid");
        item.put("name", "test_mcp");
        item.put("call_id", ""); // Empty call_id should fall back to item id
        addedItem.put("output_index", 2);

        this.ssePayload = new StringBuilder()
                .append(encodeEvent("response.output_item.added", addedItem))
                .append(encodeEvent("response.completed", objectMapper.createObjectNode()))
                .toString();
        Mockito.when(toolDefinitionProvider.listTools()).thenReturn(Flux.empty());

        Conversation conversation = Conversation.builder().id(25L).build();
        given(conversationService.ensureConversation(null, null)).willReturn(Mono.just(conversation));
        given(conversationService.upsertToolCall(any(), any(), any(), any(), any()))
                .willAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> attrs = invocation.getArgument(4, Map.class);
                    // Verify call_id falls back to item_id when empty
                    assertThat(attrs.get("callId")).isEqualTo("mcp-empty-callid");
                    return Mono.just(ToolCall.builder().build());
                });
        given(conversationService.finalizeConversation(any(), any(), any()))
                .willReturn(Mono.just(conversation));
        given(conversationService.finalizeConversation(any(), any(), any(), any()))
                .willReturn(Mono.just(conversation));

        StepVerifier.create(service.streamResponses(new ResponseStreamRequest(null, null,
                        objectMapper.createObjectNode()), null))
                .expectNextMatches(event -> "conversation.ready".equals(event.event()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();
    }
}
