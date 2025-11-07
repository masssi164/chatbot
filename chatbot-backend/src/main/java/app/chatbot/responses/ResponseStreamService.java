package app.chatbot.responses;

import static app.chatbot.responses.ResponseStreamConstants.*;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.chatbot.conversation.Conversation;
import app.chatbot.conversation.ConversationStatus;
import app.chatbot.conversation.ToolCall;
import app.chatbot.conversation.ToolCallStatus;
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
import reactor.core.scheduler.Schedulers;

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
                    
                    // Debug: Log the complete request payload to OpenAI
                    try {
                        log.info("🚀 Request to OpenAI Responses API: {}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(mutablePayload));
                    } catch (Exception e) {
                        log.warn("Failed to log request payload", e);
                    }

                    StreamState state = new StreamState(conversation.getId());

                    ServerSentEvent<String> initEvent = ServerSentEvent.<String>builder(
                                    objectMapper.createObjectNode()
                                            .put("conversation_id", conversation.getId())
                                            .put("title", conversation.getTitle())
                                            .toString()
                            )
                            .event(EVENT_CONVERSATION_READY)
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
                                        .flatMapMany(Flux::error);
                            })
                            .onErrorResume(error -> Flux.just(buildErrorEvent(error)));

                    // Use flatMap with subscribeOn for non-blocking DB writes
                    // Concurrency limit prevents overwhelming R2DBC connection pool
                    Flux<ServerSentEvent<String>> processed = upstream
                            .flatMap(event -> 
                                    handleEvent(event, state)
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .thenReturn(cloneEvent(event)),
                                    MAX_EVENT_CONCURRENCY
                            )
                            .doFinally(signal -> {
                                log.info("Stream terminated: {} (conversation: {})", signal, state.conversationId);
                                state.clear();
                            });

                    return Flux.concat(Flux.just(initEvent), processed);
                });
    }

    /**
     * Sendet MCP Approval Response an OpenAI Responses API.
     * 
     * <p>Lädt Conversation.responseId aus DB und sendet Approval-Entscheidung
     * mit previous_response_id zurück an OpenAI.
     * 
     * <p>OpenAI Responses API Format:
     * <pre>{@code
     * POST /responses
     * {
     *   "previous_response_id": "resp_12345",
     *   "model": "gpt-4o",
     *   "modalities": ["text"],
     *   "input": [{
     *     "type": "mcp_approval_response",
     *     "approval_request_id": "apreq_67890",
     *     "approve": true,
     *     "reason": "User confirmed action"
     *   }]
     * }
     * }</pre>
     * 
     * @param conversationId Conversation-ID für Response-ID-Lookup
     * @param approvalRequestId Approval-Request-ID aus mcp_approval_request Event
     * @param approve User-Entscheidung (true = approve, false = deny)
     * @param reason Optional: Grund für Entscheidung
     * @return Flux mit SSE-Events vom neuen Response-Stream
     */
    public Flux<ServerSentEvent<String>> sendApprovalResponse(
            Long conversationId,
            String approvalRequestId,
            boolean approve,
            String reason) {
        
        return conversationService.ensureConversation(conversationId, null)
            .flatMapMany(conversation -> {
                String previousResponseId = conversation.getResponseId();
                
                if (previousResponseId == null || previousResponseId.isEmpty()) {
                    log.error("Cannot send approval response: no responseId found for conversation {}", conversationId);
                    return Flux.error(new IllegalStateException("No responseId found for conversation"));
                }
                
                // Build approval response input
                ObjectNode approvalInput = objectMapper.createObjectNode();
                approvalInput.put("type", MCP_TYPE_APPROVAL_RESPONSE);
                approvalInput.put("approval_request_id", approvalRequestId);
                approvalInput.put("approve", approve);
                if (reason != null && !reason.isEmpty()) {
                    approvalInput.put("reason", reason);
                }
                
                // Build request payload
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("previous_response_id", previousResponseId);
                payload.put("model", DEFAULT_MODEL);
                payload.putArray("modalities").add(MODALITY_TEXT);
                payload.putArray("input").add(approvalInput);
                payload.put("stream", true);
                
                log.info("🔔 Sending MCP Approval Response: conversation={}, approval_request_id={}, approve={}, previous_response_id={}", 
                    conversationId, approvalRequestId, approve, previousResponseId);
                
                // Create new stream state for approval response
                StreamState state = new StreamState(conversationId);
                
                // Send request to OpenAI
                return webClient.post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToFlux(SSE_TYPE)
                    .flatMap(sseEvent -> handleEvent(sseEvent, state)
                        .thenReturn(sseEvent))
                    .doOnComplete(() -> log.info("✅ Approval response stream completed for conversation {}", conversationId))
                    .doOnError(error -> log.error("❌ Approval response stream failed for conversation {}: {}", 
                        conversationId, error.getMessage()));
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

        // Log all MCP-related events for debugging
        if (eventName.startsWith("response.mcp_call")) {
            log.trace("MCP Event: {} for conversation: {}", eventName, state.conversationId);
        }

        return switch (eventName) {
            // Lifecycle events
            case EVENT_RESPONSE_CREATED -> handleResponseCreated(payload, state);
            case EVENT_RESPONSE_COMPLETED -> handleResponseCompleted(payload, state);
            case EVENT_RESPONSE_INCOMPLETE -> handleResponseIncomplete(payload, state);
            case EVENT_RESPONSE_FAILED -> handleResponseFailed(payload, state);
            
            // Error events
            case EVENT_RESPONSE_ERROR -> handleResponseError(payload, state);
            case EVENT_ERROR -> handleCriticalError(payload, state);
            
            // Text output events
            case EVENT_OUTPUT_TEXT_DELTA -> handleTextDelta(payload, state);
            case EVENT_OUTPUT_TEXT_DONE -> handleTextDone(payload, state, data);
            case EVENT_OUTPUT_ITEM_ADDED -> handleOutputItemAdded(payload, state);
            
            // Function call events
            case EVENT_FUNCTION_ARGUMENTS_DELTA -> handleFunctionArgumentsDelta(payload, state);
            case EVENT_FUNCTION_ARGUMENTS_DONE -> handleFunctionArgumentsDone(payload, state);
            
            // MCP call events
            case EVENT_MCP_ARGUMENTS_DELTA -> handleMcpArgumentsDelta(payload, state);
            case EVENT_MCP_ARGUMENTS_DONE -> handleMcpArgumentsDone(payload, state);
            case EVENT_MCP_CALL_IN_PROGRESS -> updateToolCallStatus(payload, state, ToolCallStatus.IN_PROGRESS, null);
            case EVENT_MCP_CALL_COMPLETED -> updateToolCallStatus(payload, state, ToolCallStatus.COMPLETED, null);
            case EVENT_MCP_CALL_FAILED -> updateToolCallStatus(payload, state, ToolCallStatus.FAILED, payload.path("error").asText(null));
            
            // MCP approval events
            case EVENT_MCP_APPROVAL_REQUEST -> {
                log.debug("MCP APPROVAL REQUEST EVENT RECEIVED for conversation: {}", state.conversationId);
                yield handleMcpApprovalRequest(payload, state);
            }
            
            // MCP list tools events
            case EVENT_MCP_LIST_TOOLS_IN_PROGRESS -> handleMcpListToolsEvent(payload, "in_progress");
            case EVENT_MCP_LIST_TOOLS_COMPLETED -> handleMcpListToolsEvent(payload, "completed");
            case EVENT_MCP_LIST_TOOLS_FAILED -> handleMcpListToolsEvent(payload, "failed");
            
            default -> Mono.empty();
        };
    }

    private Mono<Void> handleTextDelta(JsonNode payload, StreamState state) {
        int outputIndex = payload.path("output_index").asInt(0);
        String delta = payload.path("delta").asText("");
        if (!delta.isEmpty()) {
            state.appendText(outputIndex, delta);
        }
        return Mono.empty();
    }

    private Mono<Void> handleTextDone(JsonNode payload, StreamState state, String rawJson) {
        int outputIndex = payload.path("output_index").asInt(0);
        String itemId = payload.path("item_id").asText();
        String text = payload.path("text").asText("");

        if (!StringUtils.hasText(text)) {
            text = state.getText(outputIndex);
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

        if (ITEM_TYPE_OUTPUT_TEXT.equals(type)) {
            // nothing to persist yet; handled via text events
            return Mono.empty();
        }

        if (ITEM_TYPE_FUNCTION_CALL.equals(type)) {
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

        if (ITEM_TYPE_MCP_CALL.equals(type)) {
            log.info("MCP Tool Call detected - item_id: {}, outputIndex: {}", itemId, outputIndex);
            
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
                    .doOnNext(toolCall -> {
                        state.toolCalls.put(itemId, ToolCallTracker.from(toolCall));
                        log.info("MCP Tool Call upserted - item_id: {}, status: {}", itemId, toolCall.getStatus());
                    })
                    .then();
        }

        if (ITEM_TYPE_TOOL_OUTPUT.equals(type)) {
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

        log.info("📝 MCP Arguments Done - item_id: {}, arguments length: {}, outputIndex: {}", 
                itemId, arguments.length(), outputIndex);

        ToolCallTracker tracker = state.toolCalls.computeIfAbsent(itemId, ignored -> new ToolCallTracker());
        if (!arguments.isEmpty()) {
            tracker.arguments = new StringBuilder(arguments);
        }

        // CRITICAL FIX: According to OpenAI Realtime API docs, response.mcp_call_arguments.done 
        // does NOT contain a 'name' field - only arguments, item_id, and output_index.
        // The name is never sent in the streaming events for MCP calls.
        // We set name=NULL here as per V5 migration (nullable name column).
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("argumentsJson", tracker.arguments.toString());
        attributes.put("outputIndex", outputIndex);
        // name is intentionally NOT set here - it remains NULL per OpenAI API specification

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

        log.info("🔄 Updating Tool Call status - item_id: {}, status: {}, outputIndex: {}", itemId, status, outputIndex);
        
        return conversationService.upsertToolCall(state.conversationId, itemId, type, outputIndex, attributes)
                .doOnNext(toolCall -> {
                    state.toolCalls.put(itemId, ToolCallTracker.from(toolCall));
                    log.info("✅ Tool Call status updated - item_id: {}, final_status: {}", itemId, toolCall.getStatus());
                })
                .then();
    }

    private Mono<Void> handleMcpListToolsEvent(JsonNode payload, String status) {
        String itemId = payload.path("item_id").asText();
        int outputIndex = payload.path("output_index").asInt(0);
        log.debug("MCP list tools event: {} for item {} at output {}", status, itemId, outputIndex);
        // These events are informational - no persistence needed yet
        return Mono.empty();
    }

    /**
     * Handle MCP approval request event - pass through to frontend.
     * 
     * <p>Responses API Format:
     * <pre>{@code
     * {
     *   "approval_request_id": "apreq_12345",
     *   "server_label": "weather-api",
     *   "tool_name": "delete_forecast",
     *   "arguments": "{\"city\":\"Berlin\"}"
     * }
     * }</pre>
     * 
     * <p>This event signals that a tool requires user approval before execution.
     * The frontend will display a dialog, and user decision is sent back via
     * approval-response endpoint with previous_response_id.
     * 
     * @param payload Event payload
     * @param state Current stream state
     * @return Mono<Void> - no persistence needed, pure pass-through
     */
    private Mono<Void> handleMcpApprovalRequest(JsonNode payload, StreamState state) {
        String approvalRequestId = payload.path("approval_request_id").asText(null);
        String serverLabel = payload.path("server_label").asText(null);
        String toolName = payload.path("tool_name").asText(null);
        String arguments = payload.path("arguments").asText(null);
        
        log.info("🔔 MCP Approval Request: tool={}, server={}, approval_request_id={}", 
            toolName, serverLabel, approvalRequestId);
        
        // Event is automatically passed through to frontend via SSE
        // No persistence needed - approval decision handled by separate endpoint
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

    // ============================================
    // Lifecycle Event Handlers
    // ============================================

    /**
     * Handle response.created event - sets responseId and transitions to STREAMING state.
     */
    private Mono<Void> handleResponseCreated(JsonNode payload, StreamState state) {
        JsonNode response = payload.path("response");
        String responseId = response.path("id").asText();
        state.responseId = responseId;
        state.status = ConversationStatus.STREAMING;

        log.info("✅ Response created: {} for conversation: {}", responseId, state.conversationId);

        return conversationService.updateConversationResponseId(state.conversationId, responseId)
                .then();
    }

    /**
     * Handle response.completed event - marks conversation as successfully completed.
     */
    private Mono<Void> handleResponseCompleted(JsonNode payload, StreamState state) {
        JsonNode response = payload.path("response");
        String responseId = response.path("id").asText();
        state.status = ConversationStatus.COMPLETED;

        log.info("✅ Response completed: {} for conversation: {}", responseId, state.conversationId);

        return conversationService.finalizeConversation(
                state.conversationId,
                responseId,
                ConversationStatus.COMPLETED
        ).then();
    }

    /**
     * Handle response.incomplete event - marks conversation as incomplete (e.g., token limit).
     */
    private Mono<Void> handleResponseIncomplete(JsonNode payload, StreamState state) {
        JsonNode response = payload.path("response");
        String responseId = response.path("id").asText();
        String reason = response.path("status_details").path("reason").asText("length");
        state.status = ConversationStatus.INCOMPLETE;

        log.warn("⚠️ Response incomplete ({}): {} for conversation: {}",
                reason, responseId, state.conversationId);

        return conversationService.finalizeConversation(
                state.conversationId,
                responseId,
                ConversationStatus.INCOMPLETE,
                reason
        ).then();
    }

    /**
     * Handle response.failed event - marks conversation as failed.
     */
    private Mono<Void> handleResponseFailed(JsonNode payload, StreamState state) {
        JsonNode response = payload.path("response");
        JsonNode error = response.path("error");

        String errorCode = error.path("code").asText("unknown");
        String errorMessage = error.path("message").asText("");
        state.status = ConversationStatus.FAILED;

        log.error("❌ Response failed: {} - {} (conversation: {})",
                errorCode, errorMessage, state.conversationId);

        return conversationService.finalizeConversation(
                state.conversationId,
                state.responseId,
                ConversationStatus.FAILED,
                errorCode + ": " + errorMessage
        ).then();
    }

    /**
     * Handle response.error event - logs error but doesn't necessarily fail the conversation.
     */
    private Mono<Void> handleResponseError(JsonNode payload, StreamState state) {
        JsonNode error = payload.path("error");
        String code = error.path("code").asText("unknown");
        String message = error.path("message").asText("");

        // Special handling for rate limits
        if ("rate_limit_exceeded".equals(code)) {
            log.warn("⚠️ Rate limit hit for conversation {}: {}", state.conversationId, message);
        } else {
            log.error("❌ Response error: {} - {} (conversation: {})", code, message, state.conversationId);
        }

        return Mono.empty();
    }

    /**
     * Handle critical error event - fails the conversation immediately.
     */
    private Mono<Void> handleCriticalError(JsonNode payload, StreamState state) {
        JsonNode error = payload.path("error");
        String code = error.path("code").asText("unknown");
        String message = error.path("message").asText("");

        state.status = ConversationStatus.FAILED;

        log.error("❌ CRITICAL ERROR: {} - {} (conversation: {})",
                code, message, state.conversationId);

        return conversationService.finalizeConversation(
                state.conversationId,
                state.responseId,
                ConversationStatus.FAILED,
                "CRITICAL: " + code
        ).then();
    }

    private static final class StreamState {
        private final Long conversationId;
        private volatile String responseId;
        private volatile ConversationStatus status = ConversationStatus.CREATED;
        private final Map<Integer, AtomicReference<String>> textByOutputIndex = new ConcurrentHashMap<>();
        private final Map<String, ToolCallTracker> toolCalls = new ConcurrentHashMap<>();

        private StreamState(Long conversationId) {
            this.conversationId = conversationId;
        }

        /**
         * Thread-safe text append using AtomicReference.
         */
        void appendText(int outputIndex, String delta) {
            textByOutputIndex
                    .computeIfAbsent(outputIndex, k -> new AtomicReference<>(""))
                    .updateAndGet(current -> current + delta);
        }

        /**
         * Get accumulated text for output index.
         */
        String getText(int outputIndex) {
            AtomicReference<String> ref = textByOutputIndex.get(outputIndex);
            return ref != null ? ref.get() : "";
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
