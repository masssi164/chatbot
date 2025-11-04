package app.chatbot.responses;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.config.OpenAiProperties;
import app.chatbot.conversation.Conversation;
import app.chatbot.conversation.ConversationService;
import app.chatbot.conversation.MessageRole;
import app.chatbot.conversation.ToolCall;
import app.chatbot.conversation.ToolCallStatus;
import app.chatbot.conversation.ToolCallType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ResponseStreamService {

    private static final Logger log = LoggerFactory.getLogger(ResponseStreamService.class);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final OpenAiProperties properties;
    private final ConversationService conversationService;
    private final ToolDefinitionProvider toolDefinitionProvider;
    private final ObjectMapper objectMapper;

    public ResponseStreamService(WebClient responsesWebClient,
                                 OpenAiProperties properties,
                                 ConversationService conversationService,
                                 ToolDefinitionProvider toolDefinitionProvider,
                                 ObjectMapper objectMapper) {
        this.webClient = responsesWebClient;
        this.properties = properties;
        this.conversationService = conversationService;
        this.toolDefinitionProvider = toolDefinitionProvider;
        this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<String>> streamResponses(ResponseStreamRequest request,
                                                         String authorizationHeader) {
        JsonNode payload = request.payload();
        if (payload == null || !payload.isObject()) {
            return Flux.error(new IllegalArgumentException("Payload must be a JSON object."));
        }

        ObjectNode mutablePayload = ((ObjectNode) payload).deepCopy();
        enforceStreamingFlag(mutablePayload);

        Mono<List<JsonNode>> toolsMono = toolDefinitionProvider.listTools().collectList();

        Mono<Conversation> conversationMono = conversationService.ensureConversation(
                request.conversationId(), request.title());

        return Mono.zip(conversationMono, toolsMono)
                .flatMapMany(tuple -> {
                    Conversation conversation = tuple.getT1();
                    List<JsonNode> tools = tuple.getT2();
                    mergeTools(mutablePayload, tools);

                    StreamState state = new StreamState(conversation.getId());

                    ServerSentEvent<String> initEvent = ServerSentEvent.<String>builder(
                                    objectMapper.createObjectNode()
                                            .put("conversation_id", conversation.getId())
                                            .put("title", conversation.getTitle())
                                            .toString()
                            )
                            .event("conversation.ready")
                            .build();

                    WebClient.RequestBodySpec spec = webClient.post()
                            .uri("/responses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM);

                    WebClient.RequestHeadersSpec<?> headersSpec = spec.body(BodyInserters.fromValue(mutablePayload));

                    if (StringUtils.hasText(authorizationHeader)) {
                        headersSpec = headersSpec.header(HttpHeaders.AUTHORIZATION, authorizationHeader.trim());
                    } else if (StringUtils.hasText(properties.getApiKey())) {
                        headersSpec = headersSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey().trim());
                    }

                    Flux<ServerSentEvent<String>> upstream = headersSpec.exchangeToFlux(response -> {
                                if (response.statusCode().is2xxSuccessful()) {
                                    return response.bodyToFlux(SSE_TYPE);
                                }
                                return response.createException()
                                        .flatMapMany(exception -> Flux.error(exception));
                            })
                            .onErrorResume(error -> Flux.just(buildErrorEvent(error)));

                    Flux<ServerSentEvent<String>> processed = upstream.concatMap(event -> handleEvent(event, state)
                                    .thenReturn(cloneEvent(event)))
                            .doFinally(signal -> state.clear());

                    return Flux.concat(Flux.just(initEvent), processed);
                });
    }

    private void enforceStreamingFlag(ObjectNode payload) {
        payload.put("stream", true);
    }

    private void mergeTools(ObjectNode payload, List<JsonNode> additionalTools) {
        if (additionalTools == null || additionalTools.isEmpty()) {
            return;
        }

        ArrayNode toolsArray;
        if (payload.has("tools") && payload.get("tools").isArray()) {
            toolsArray = (ArrayNode) payload.get("tools");
        } else {
            toolsArray = payload.putArray("tools");
        }

        for (JsonNode toolNode : additionalTools) {
            if (toolNode != null && !toolNode.isNull()) {
                toolsArray.add(toolNode);
            }
        }
    }

    private ServerSentEvent<String> cloneEvent(ServerSentEvent<String> original) {
        return ServerSentEvent.<String>builder(original.data())
                .event(original.event())
                .id(original.id())
                .comment(original.comment())
                .retry(original.retry())
                .build();
    }

    private Mono<Void> handleEvent(ServerSentEvent<String> event, StreamState state) {
        String eventName = event.event();
        if (!StringUtils.hasText(eventName)) {
            return Mono.empty();
        }

        String data = event.data();
        JsonNode payload;
        try {
            payload = StringUtils.hasText(data) ? objectMapper.readTree(data) : objectMapper.nullNode();
        } catch (IOException ex) {
            log.warn("Failed to parse SSE payload for event {}: {}", eventName, ex.getMessage());
            return Mono.empty();
        }

        return switch (eventName) {
            case "response.output_text.delta" -> handleTextDelta(payload, state);
            case "response.output_text.done" -> handleTextDone(payload, state, data);
            case "response.output_item.added" -> handleOutputItemAdded(payload, state);
            case "response.function_call_arguments.delta" -> handleFunctionArgumentsDelta(payload, state);
            case "response.function_call_arguments.done" -> handleFunctionArgumentsDone(payload, state);
            case "response.mcp_call_arguments.delta" -> handleMcpArgumentsDelta(payload, state);
            case "response.mcp_call_arguments.done" -> handleMcpArgumentsDone(payload, state);
            case "response.mcp_call.in_progress" -> updateToolCallStatus(payload, state, ToolCallStatus.IN_PROGRESS, null);
            case "response.mcp_call.completed" -> updateToolCallStatus(payload, state, ToolCallStatus.COMPLETED, null);
            case "response.mcp_call.failed" -> updateToolCallStatus(payload, state, ToolCallStatus.FAILED, payload.path("error").asText(null));
            case "response.mcp_list_tools.in_progress" -> handleMcpListToolsEvent(payload, "in_progress");
            case "response.mcp_list_tools.completed" -> handleMcpListToolsEvent(payload, "completed");
            case "response.mcp_list_tools.failed" -> handleMcpListToolsEvent(payload, "failed");
            default -> Mono.empty();
        };
    }

    private Mono<Void> handleTextDelta(JsonNode payload, StreamState state) {
        int outputIndex = payload.path("output_index").asInt(0);
        String delta = payload.path("delta").asText("");
        if (!delta.isEmpty()) {
            state.textByOutputIndex
                    .computeIfAbsent(outputIndex, ignored -> new StringBuilder())
                    .append(delta);
        }
        return Mono.empty();
    }

    private Mono<Void> handleTextDone(JsonNode payload, StreamState state, String rawJson) {
        int outputIndex = payload.path("output_index").asInt(0);
        String itemId = payload.path("item_id").asText();
        String text = payload.path("text").asText("");

        if (!StringUtils.hasText(text)) {
            StringBuilder builder = state.textByOutputIndex.get(outputIndex);
            if (builder != null) {
                text = builder.toString();
            }
        }

        final String finalText = text;
        state.textByOutputIndex.remove(outputIndex);

        if (!StringUtils.hasText(finalText)) {
            return Mono.empty();
        }

        return conversationService.updateMessageContent(
                state.conversationId,
                itemId,
                finalText,
                rawJson,
                outputIndex
        ).then();
    }

    private Mono<Void> handleOutputItemAdded(JsonNode payload, StreamState state) {
        JsonNode item = payload.path("item");
        if (item.isMissingNode() || !item.hasNonNull("type")) {
            return Mono.empty();
        }

        String type = item.get("type").asText();
        Integer outputIndex = payload.path("output_index").isInt() ? payload.get("output_index").asInt() : null;
        String itemId = item.path("id").asText();

        if ("output_text".equals(type)) {
            // nothing to persist yet; handled via text events
            return Mono.empty();
        }

        if ("function_call".equals(type)) {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("name", item.path("name").asText(null));
            
            // Fallback: use item_id if call_id is not provided
            String callId = item.path("call_id").asText(null);
            if (callId == null || callId.isEmpty()) {
                callId = itemId;
            }
            attributes.put("callId", callId);
            attributes.put("status", ToolCallStatus.IN_PROGRESS);
            attributes.put("outputIndex", outputIndex);

            return conversationService.upsertToolCall(state.conversationId, itemId, ToolCallType.FUNCTION, outputIndex, attributes)
                    .doOnNext(toolCall -> state.toolCalls.put(itemId, ToolCallTracker.from(toolCall)))
                    .then();
        }

        if ("mcp_call".equals(type)) {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("name", item.path("name").asText(null));
            
            // Fallback: use item_id if call_id is not provided
            String callId = item.path("call_id").asText(null);
            if (callId == null || callId.isEmpty()) {
                callId = itemId;
            }
            attributes.put("callId", callId);
            attributes.put("status", ToolCallStatus.IN_PROGRESS);
            attributes.put("outputIndex", outputIndex);

            JsonNode outputNode = item.get("output");
            if (outputNode != null && !outputNode.isNull()) {
                attributes.put("resultJson", outputNode.toString());
            }

            JsonNode errorNode = item.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                attributes.put("resultJson", errorNode.toString());
                attributes.put("status", ToolCallStatus.FAILED);
            }

            return conversationService.upsertToolCall(state.conversationId, itemId, ToolCallType.MCP, outputIndex, attributes)
                    .doOnNext(toolCall -> state.toolCalls.put(itemId, ToolCallTracker.from(toolCall)))
                    .then();
        }

        if ("tool_output".equals(type)) {
            // Some models emit tool output as a normal message chunk.
            String role = item.path("role").asText("");
            if ("tool".equalsIgnoreCase(role)) {
                String content = extractTextContent(item.path("content"));
                if (StringUtils.hasText(content)) {
                    return conversationService.appendMessage(
                                    state.conversationId,
                                    MessageRole.TOOL,
                                    content,
                                    item.toString(),
                                    outputIndex,
                                    itemId
                            ).then();
                }
            }
        }

        return Mono.empty();
    }

    private Mono<Void> handleFunctionArgumentsDelta(JsonNode payload, StreamState state) {
        String itemId = payload.path("item_id").asText();
        String delta = payload.path("delta").asText("");
        if (!delta.isEmpty()) {
            state.toolCalls
                    .computeIfAbsent(itemId, ignored -> new ToolCallTracker())
                    .arguments.append(delta);
        }
        return Mono.empty();
    }

    private Mono<Void> handleFunctionArgumentsDone(JsonNode payload, StreamState state) {
        String itemId = payload.path("item_id").asText();
        int outputIndex = payload.path("output_index").asInt(0);
        String arguments = payload.path("arguments").asText("");

        ToolCallTracker tracker = state.toolCalls.computeIfAbsent(itemId, ignored -> new ToolCallTracker());
        if (!arguments.isEmpty()) {
            tracker.arguments = new StringBuilder(arguments);
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("argumentsJson", tracker.arguments.toString());
        attributes.put("status", ToolCallStatus.IN_PROGRESS);
        attributes.put("outputIndex", outputIndex);

        return conversationService.upsertToolCall(state.conversationId, itemId, ToolCallType.FUNCTION, outputIndex, attributes)
                .doOnNext(toolCall -> state.toolCalls.put(itemId, ToolCallTracker.from(toolCall)))
                .then();
    }

    private Mono<Void> handleMcpArgumentsDelta(JsonNode payload, StreamState state) {
        String itemId = payload.path("item_id").asText();
        String delta = payload.path("delta").asText("");
        if (!delta.isEmpty()) {
            state.toolCalls
                    .computeIfAbsent(itemId, ignored -> new ToolCallTracker())
                    .arguments.append(delta);
        }
        return Mono.empty();
    }

    private Mono<Void> handleMcpArgumentsDone(JsonNode payload, StreamState state) {
        String itemId = payload.path("item_id").asText();
        int outputIndex = payload.path("output_index").asInt(0);
        String arguments = payload.path("arguments").asText("");

        ToolCallTracker tracker = state.toolCalls.computeIfAbsent(itemId, ignored -> new ToolCallTracker());
        if (!arguments.isEmpty()) {
            tracker.arguments = new StringBuilder(arguments);
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("argumentsJson", tracker.arguments.toString());
        attributes.put("outputIndex", outputIndex);

        return conversationService.upsertToolCall(state.conversationId, itemId, ToolCallType.MCP, outputIndex, attributes)
                .doOnNext(toolCall -> state.toolCalls.put(itemId, ToolCallTracker.from(toolCall)))
                .then();
    }

    private Mono<Void> updateToolCallStatus(JsonNode payload,
                                           StreamState state,
                                           ToolCallStatus status,
                                           String errorMessage) {
        String itemId = payload.path("item_id").asText();
        int outputIndex = payload.path("output_index").asInt(0);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("status", status);
        attributes.put("outputIndex", outputIndex);
        if (StringUtils.hasText(errorMessage)) {
            attributes.put("resultJson", objectMapper.createObjectNode()
                    .put("error", errorMessage)
                    .toString());
        }

        ToolCallType type = ToolCallType.MCP;
        ToolCallTracker tracker = state.toolCalls.get(itemId);
        if (tracker != null && tracker.type != null) {
            type = tracker.type;
        }

        return conversationService.upsertToolCall(state.conversationId, itemId, type, outputIndex, attributes)
                .doOnNext(toolCall -> state.toolCalls.put(itemId, ToolCallTracker.from(toolCall)))
                .then();
    }

    private Mono<Void> handleMcpListToolsEvent(JsonNode payload, String status) {
        String itemId = payload.path("item_id").asText();
        int outputIndex = payload.path("output_index").asInt(0);
        log.debug("MCP list tools event: {} for item {} at output {}", status, itemId, outputIndex);
        // These events are informational - no persistence needed yet
        return Mono.empty();
    }

    private String extractTextContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return null;
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode node : contentNode) {
                String part = extractTextContent(node);
                if (StringUtils.hasText(part)) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(part);
                }
            }
            return builder.length() == 0 ? null : builder.toString();
        }
        if (contentNode.hasNonNull("text")) {
            return contentNode.get("text").asText();
        }
        return null;
    }

    private ServerSentEvent<String> buildErrorEvent(Throwable error) {
        ObjectNode errorNode = objectMapper.createObjectNode();
        errorNode.put("message", error.getMessage());
        errorNode.put("timestamp", Instant.now().toString());

        if (error instanceof WebClientResponseException responseException) {
            errorNode.put("status", responseException.getStatusCode().value());
            errorNode.put("response", responseException.getResponseBodyAsString());
        }

        return ServerSentEvent.<String>builder(errorNode.toString())
                .event("response.failed")
                .build();
    }

    private static final class StreamState {
        private final Long conversationId;
        private final Map<Integer, StringBuilder> textByOutputIndex = new ConcurrentHashMap<>();
        private final Map<String, ToolCallTracker> toolCalls = new ConcurrentHashMap<>();

        private StreamState(Long conversationId) {
            this.conversationId = conversationId;
        }

        private void clear() {
            textByOutputIndex.clear();
            toolCalls.clear();
        }
    }

    private static final class ToolCallTracker {
        private Long id;
        private ToolCallType type;
        private Integer outputIndex;
        private String itemId;
        private StringBuilder arguments = new StringBuilder();

        private static ToolCallTracker from(ToolCall toolCall) {
            ToolCallTracker tracker = new ToolCallTracker();
            tracker.id = toolCall.getId();
            tracker.type = toolCall.getType();
            tracker.outputIndex = toolCall.getOutputIndex();
            tracker.itemId = toolCall.getItemId();
            if (StringUtils.hasText(toolCall.getArgumentsJson())) {
                tracker.arguments = new StringBuilder(toolCall.getArgumentsJson());
            }
            return tracker;
        }
    }
}
