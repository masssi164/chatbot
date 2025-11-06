# Responses Package - OpenAI Streaming Integration

## Overview

This package implements the integration with **OpenAI Responses API** (or compatible APIs like LiteLLM) using **Server-Sent Events (SSE)** for real-time streaming of chat responses.

**Key Features**:
- Real-time message streaming to frontend
- Tool execution with approval workflow
- Conversation lifecycle tracking
- Error handling and recovery
- Multi-turn conversations with context

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Frontend (React)                     │
│  fetchEventSource("/api/responses/stream")              │
└─────────────────┬───────────────────────────────────────┘
                  │ HTTP POST (SSE)
                  ▼
┌─────────────────────────────────────────────────────────┐
│            ResponseStreamController                     │
│  POST /api/responses/stream                             │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│          ResponseStreamService                          │
│  - Build OpenAI request                                 │
│  - Inject MCP tools                                     │
│  - Stream from OpenAI API                               │
│  - Parse SSE events                                     │
│  - Handle tool execution                                │
│  - Update conversation state                            │
└─────────────────┬───────────────────────────────────────┘
                  │
         ┌────────┴────────┐
         ▼                 ▼
┌──────────────────┐  ┌──────────────────┐
│  OpenAI API      │  │  McpClientService│
│  (via LiteLLM)   │  │  (Tool execution)│
└──────────────────┘  └──────────────────┘
```

---

## Core Component: ResponseStreamService ⭐

**File**: `ResponseStreamService.java` (800+ lines)

**Purpose**: Orchestrates the entire streaming flow from request to response.

### Responsibilities

1. **Request Building**:
   - Merge conversation history
   - Add system prompt
   - Inject MCP tools via `McpToolContextBuilder`
   - Set streaming options

2. **SSE Streaming**:
   - Connect to OpenAI API
   - Parse SSE events
   - Forward events to frontend
   - Handle streaming errors

3. **Tool Execution**:
   - Detect tool calls in OpenAI response
   - Check approval policy
   - Execute tool via `McpClientService`
   - Send tool result back to OpenAI

4. **Conversation Management**:
   - Create/update conversation
   - Save messages to database
   - Track tool execution state
   - Update conversation status

5. **Error Handling**:
   - Timeout handling (30s default)
   - Retry on transient errors
   - Mark conversation as FAILED on unrecoverable errors

---

### Main Method: `streamResponses()`

**Signature**:
```java
public Flux<ServerSentEvent<String>> streamResponses(
    ResponseStreamRequest request,
    String authorizationHeader
)
```

**Request DTO**:
```java
public record ResponseStreamRequest(
    Long conversationId,
    String title,
    JsonNode payload  // OpenAI request format
) {}
```

**Response**: `Flux<ServerSentEvent<String>>` (SSE stream)

---

### Flow Diagram

```
1. Validate request payload
   ↓
2. Ensure conversation exists (create if needed)
   ↓
3. Load available tools (McpToolContextBuilder)
   ↓
4. Merge tools into OpenAI request
   ↓
5. Connect to OpenAI API (SSE)
   ↓
6. Stream events:
   ├─ response.created → Emit init event
   ├─ response.output_item.added → Create message/tool_call
   ├─ response.text.delta → Append text, emit message event
   ├─ response.function_call_arguments.done → Tool call ready
   │  ├─ Check approval policy
   │  ├─ If ASK_USER: Emit approval_required, wait
   │  └─ Execute tool → Send result to OpenAI
   ├─ response.output_item.done → Finalize output
   └─ response.done → Complete, emit done event
   ↓
7. Update conversation status (COMPLETED, INCOMPLETE, FAILED)
```

---

### Internal State: `StreamState`

**Purpose**: Tracks per-stream state across SSE events.

```java
private static class StreamState {
    final Long conversationId;
    
    // Message tracking
    AtomicReference<String> currentMessageId = new AtomicReference<>();
    AtomicReference<String> currentItemId = new AtomicReference<>();
    Map<Integer, String> messagesByOutputIndex = new ConcurrentHashMap<>();
    
    // Tool call tracking
    Map<String, ToolCallInfo> toolCalls = new ConcurrentHashMap<>();
    AtomicReference<String> currentToolCallId = new AtomicReference<>();
    AtomicReference<String> currentToolName = new AtomicReference<>();
    
    // Approval workflow
    AtomicBoolean waitingForApproval = new AtomicBoolean(false);
    Map<String, CompletableFuture<ApprovalResponse>> pendingApprovals = new ConcurrentHashMap<>();
    Set<String> processedApprovals = ConcurrentHashMap.newKeySet();
    
    // Response lifecycle
    AtomicReference<String> responseId = new AtomicReference<>();
    AtomicBoolean responseCreated = new AtomicBoolean(false);
    
    // Error tracking
    AtomicReference<String> errorMessage = new AtomicReference<>();
}
```

**Complexity**: Many atomic references and concurrent maps due to SSE event ordering issues.

---

### SSE Event Handling

OpenAI Responses API emits various SSE events. Each event type requires specific handling.

#### Event Types (from OpenAI)

1. **`response.created`**
   - Emitted when response starts
   - Contains `response_id`
   - **Action**: Initialize response tracking

2. **`response.output_item.added`**
   - Emitted when new output item added (message or tool call)
   - Contains `output_index`, `item_id`, `type` (message, function_call)
   - **Action**: Create message or tool call record

3. **`response.text.delta`**
   - Emitted for each text chunk
   - Contains `output_index`, `delta` (text)
   - **Action**: Append to current message, emit to frontend

4. **`response.function_call_arguments.delta`**
   - Emitted for each tool argument chunk
   - Contains `output_index`, `call_id`, `delta` (JSON)
   - **Action**: Accumulate arguments (JSON parsing)

5. **`response.function_call_arguments.done`**
   - Emitted when tool arguments complete
   - Contains `output_index`, `call_id`, `name`, `arguments` (JSON)
   - **Action**: Execute tool (with approval check)

6. **`response.output_item.done`**
   - Emitted when output item finalized
   - Contains `output_index`, `item_id`
   - **Action**: Finalize message or tool call

7. **`response.done`**
   - Emitted when entire response complete
   - Contains `status` (completed, incomplete, failed)
   - **Action**: Update conversation status, close stream

8. **`error`**
   - Emitted on OpenAI error
   - Contains `error` object
   - **Action**: Mark conversation as FAILED, emit error event

---

### Custom SSE Events (to Frontend)

The service transforms OpenAI events into frontend-friendly events:

1. **`init`**
   - Emitted at start
   - Contains `conversationId`, `responseId`

2. **`conversation_status`**
   - Emitted on status change
   - Contains `status` (STREAMING, COMPLETED, etc.)

3. **`message`**
   - Emitted for each text chunk
   - Contains `delta`, `itemId`, `outputIndex`

4. **`tool_call_update`**
   - Emitted on tool state change
   - Contains `itemId`, `name`, `status`, `result`, `error`

5. **`approval_required`**
   - Emitted when tool needs approval
   - Contains `approvalRequestId`, `serverId`, `toolName`, `arguments`

6. **`error`**
   - Emitted on error
   - Contains `message`

7. **`done`**
   - Emitted at end
   - Contains `status`, `completionReason`

---

### Tool Execution Flow

**Scenario**: OpenAI requests tool call

```
1. Parse tool call from response.function_call_arguments.done
   ↓
2. Extract: call_id, tool_name, arguments
   ↓
3. Find which MCP server has this tool
   ↓
4. Check approval policy (ToolApprovalPolicyService)
   ├─ ALWAYS_ALLOW:
   │  └─ Execute immediately (step 6)
   ├─ ALWAYS_DENY:
   │  └─ Return error result
   └─ ASK_USER:
      ├─ Generate approval request ID
      ├─ Emit approval_required SSE event
      ├─ Create CompletableFuture
      └─ Wait for user response (POST /api/responses/approval/{id})
   ↓
5. User approves/denies
   ↓
6. Execute tool via McpClientService.callToolAsync()
   ↓
7. Transform result to OpenAI format
   ↓
8. Send tool result to OpenAI (second request)
   ↓
9. OpenAI generates final response with tool result context
   ↓
10. Stream final response to frontend
```

**Code Snippet**:
```java
private Mono<JsonNode> executeToolWithApproval(
    String serverId,
    String toolName,
    Map<String, Object> arguments,
    StreamState state
) {
    // Check approval policy
    if (toolApprovalService.requiresApproval(serverId, toolName)) {
        String approvalRequestId = UUID.randomUUID().toString();
        
        // Create future to wait for user response
        CompletableFuture<ApprovalResponse> future = new CompletableFuture<>();
        state.pendingApprovals.put(approvalRequestId, future);
        
        // Emit approval_required event
        ServerSentEvent<String> approvalEvent = buildApprovalRequiredEvent(
            approvalRequestId, serverId, toolName, arguments
        );
        
        // Wait for approval (blocking, but on reactive scheduler)
        return Mono.fromFuture(future)
            .flatMap(response -> {
                if (response.approved()) {
                    return executeToolAsync(serverId, toolName, arguments);
                } else {
                    return Mono.just(buildToolDeniedResult());
                }
            });
    } else {
        // Auto-approve
        return executeToolAsync(serverId, toolName, arguments);
    }
}

private Mono<JsonNode> executeToolAsync(
    String serverId,
    String toolName,
    Map<String, Object> arguments
) {
    return mcpClientService.callToolAsync(serverId, toolName, arguments)
        .map(result -> transformToOpenAiFormat(result))
        .timeout(Duration.ofSeconds(30))
        .doOnError(ex -> log.error("Tool execution failed: {}", ex.getMessage()));
}
```

---

### Approval Response Handling

**Endpoint**: `POST /api/responses/approval/{approvalRequestId}`

**Controller**: `ApprovalResponseController.java`

**Request DTO**:
```java
public record ApprovalResponseRequest(
    boolean approved
) {}
```

**Flow**:
```java
@PostMapping("/approval/{approvalRequestId}")
public Mono<ResponseEntity<Void>> handleApprovalResponse(
    @PathVariable String approvalRequestId,
    @RequestBody ApprovalResponseRequest request
) {
    // Find pending approval
    CompletableFuture<ApprovalResponse> future = 
        responseStreamService.getPendingApproval(approvalRequestId);
    
    if (future == null) {
        return Mono.just(ResponseEntity.notFound().build());
    }
    
    // Complete future (unblocks tool execution)
    future.complete(new ApprovalResponse(request.approved()));
    
    return Mono.just(ResponseEntity.ok().build());
}
```

**Frontend**:
```typescript
// User clicks Approve
await apiClient.approveToolExecution(approvalRequestId, true);
```

---

## Other Components

### ResponseStreamController

**File**: `ResponseStreamController.java`

**Purpose**: REST endpoint for streaming.

**Endpoint**: `POST /api/responses/stream`

**Request**:
```json
{
  "conversationId": 123,
  "title": "New Chat",
  "payload": {
    "model": "gpt-4",
    "messages": [
      {"role": "user", "content": "Hello"}
    ],
    "tools": [...],
    "stream": true
  }
}
```

**Response**: SSE stream (text/event-stream)

**Implementation**:
```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(
    @RequestBody ResponseStreamRequest request,
    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader
) {
    return responseStreamService.streamResponses(request, authHeader)
        .doOnError(ex -> log.error("Streaming error", ex))
        .onErrorResume(ex -> Flux.just(
            ServerSentEvent.<String>builder()
                .event("error")
                .data("{\"message\": \"" + ex.getMessage() + "\"}")
                .build()
        ));
}
```

---

### ApprovalResponseController

**File**: `ApprovalResponseController.java`

**Purpose**: Handle tool approval decisions.

**Endpoint**: `POST /api/responses/approval/{approvalRequestId}`

See previous section for details.

---

### ToolDefinitionProvider

**File**: `ToolDefinitionProvider.java` (interface)

**Purpose**: Provides list of available tools for OpenAI request.

**Implementation**: `DefaultToolDefinitionProvider.java`

**Method**:
```java
public Flux<JsonNode> listTools() {
    return mcpServerRepository.findAll()
        .filter(server -> server.getStatus() == McpServerStatus.CONNECTED)
        .filter(server -> server.getToolsCache() != null)
        .flatMap(server -> {
            try {
                List<McpSchema.Tool> tools = objectMapper.readValue(
                    server.getToolsCache(),
                    new TypeReference<List<McpSchema.Tool>>() {}
                );
                return Flux.fromIterable(tools)
                    .map(tool -> transformToOpenAiFormat(tool));
            } catch (JsonProcessingException ex) {
                log.error("Failed to parse tools cache", ex);
                return Flux.empty();
            }
        });
}
```

**Used by**: `ResponseStreamService` to inject tools into request.

---

## Request Building

### OpenAI Request Format

```json
{
  "model": "gpt-4",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "What's the weather in Berlin?"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "Get weather data",
        "parameters": {
          "type": "object",
          "properties": {
            "location": {"type": "string"}
          },
          "required": ["location"]
        }
      }
    }
  ],
  "stream": true,
  "temperature": 0.7,
  "max_tokens": 2000
}
```

### Building Logic

```java
private ObjectNode buildOpenAiRequest(
    ResponseStreamRequest request,
    Conversation conversation,
    List<JsonNode> tools
) {
    ObjectNode payload = (ObjectNode) request.payload().deepCopy();
    
    // 1. Ensure stream flag
    payload.put("stream", true);
    
    // 2. Merge conversation history
    ArrayNode messages = payload.putArray("messages");
    conversationService.getMessages(conversation.getId())
        .forEach(msg -> {
            ObjectNode messageNode = objectMapper.createObjectNode();
            messageNode.put("role", msg.getRole().name().toLowerCase());
            messageNode.put("content", msg.getContent());
            messages.add(messageNode);
        });
    
    // 3. Add user message
    ObjectNode userMessage = objectMapper.createObjectNode();
    userMessage.put("role", "user");
    userMessage.put("content", extractUserMessage(request.payload()));
    messages.add(userMessage);
    
    // 4. Inject MCP tools
    mcpToolContextBuilder.augmentPayload(payload);
    
    return payload;
}
```

---

## Error Handling

### Error Types

1. **Connection Errors**:
   - OpenAI API unreachable
   - Network timeout
   - **Action**: Retry 3 times, then fail

2. **OpenAI API Errors**:
   - Rate limit exceeded (429)
   - Invalid request (400)
   - Model overloaded (503)
   - **Action**: Parse error, emit to frontend, mark conversation FAILED

3. **Tool Execution Errors**:
   - MCP server unreachable
   - Tool not found
   - Invalid arguments
   - **Action**: Return error result to OpenAI, continue response

4. **Streaming Errors**:
   - SSE connection dropped
   - Parse error (invalid JSON)
   - **Action**: Mark conversation INCOMPLETE, emit error event

### Error Response Format

```json
{
  "event": "error",
  "data": {
    "message": "Failed to execute tool: Connection timeout",
    "code": "TOOL_EXECUTION_FAILED",
    "details": {...}
  }
}
```

---

## Conversation Lifecycle

### Status States

```java
enum ConversationStatus {
    CREATED,     // Initial state
    STREAMING,   // Response generation in progress
    COMPLETED,   // Response successfully completed
    INCOMPLETE,  // Streaming interrupted (timeout, abort)
    FAILED       // Error occurred
}
```

### State Transitions

```
CREATED → STREAMING (on stream start)
    ↓
STREAMING → COMPLETED (on response.done with status=completed)
STREAMING → INCOMPLETE (on timeout, user abort, connection drop)
STREAMING → FAILED (on unrecoverable error)
```

### Database Updates

```java
// On stream start
conversationService.updateStatus(conversationId, ConversationStatus.STREAMING);

// On stream complete
conversationService.updateStatus(conversationId, ConversationStatus.COMPLETED);

// On error
conversationService.updateStatus(conversationId, ConversationStatus.FAILED);
```

---

## Performance Considerations

### Bottlenecks

1. **Database Writes**:
   - Every text delta saves message to DB
   - **Mitigation**: Batch updates, save only on item completion

2. **Tool Execution Latency**:
   - External MCP server calls can be slow
   - **Mitigation**: Timeout, parallel execution (if multiple tools)

3. **SSE Buffering**:
   - WebFlux buffers events
   - **Mitigation**: Use `Flux.create()` with backpressure

### Optimization Ideas

1. **Reduce DB Writes**:
   ```java
   // Instead of saving every delta
   conversationService.appendMessageDelta(messageId, delta);
   
   // Save only on completion
   conversationService.finalizeMessage(messageId);
   ```

2. **Cache Conversation History**:
   ```java
   @Cacheable("conversation-history")
   public Mono<List<Message>> getMessages(Long conversationId) {
       return messageRepository.findByConversationId(conversationId);
   }
   ```

3. **Parallel Tool Execution**:
   ```java
   // If OpenAI requests multiple tools
   Flux.fromIterable(toolCalls)
       .flatMap(tc -> executeToolAsync(tc), 3) // Max 3 concurrent
       .collectList();
   ```

---

## Testing

### Unit Tests

**ResponseStreamServiceTest**:
```java
@Test
void shouldStreamMessageDeltas() {
    // Mock OpenAI API
    when(webClient.post().uri(anyString()))
        .thenReturn(mockSseStream("response.text.delta", "{\"delta\": \"Hello\"}"));
    
    // Stream
    StepVerifier.create(service.streamResponses(request, authHeader))
        .expectNextMatches(event -> event.event().equals("message"))
        .verifyComplete();
}

@Test
void shouldHandleToolExecution() {
    // Mock tool call event
    when(webClient.post().uri(anyString()))
        .thenReturn(mockSseStream("response.function_call_arguments.done", 
            "{\"name\": \"get_weather\", \"arguments\": {...}}"));
    
    // Mock tool execution
    when(mcpClientService.callToolAsync(anyString(), anyString(), any()))
        .thenReturn(Mono.just(mockToolResult));
    
    // Stream
    StepVerifier.create(service.streamResponses(request, authHeader))
        .expectNextMatches(event -> event.event().equals("tool_call_update"))
        .verifyComplete();
}
```

### Integration Tests

**E2E Test**:
```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ResponseStreamIntegrationTest {
    
    @Autowired WebTestClient webClient;
    
    @MockBean WebClient openAiWebClient;
    @MockBean McpClientService mcpClientService;
    
    @Test
    void shouldStreamCompleteResponse() {
        // Mock OpenAI API
        mockOpenAiResponse();
        
        // Send request
        Flux<ServerSentEvent<String>> stream = webClient.post()
            .uri("/api/responses/stream")
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk()
            .returnResult(ServerSentEvent.class)
            .getResponseBody();
        
        // Verify events
        StepVerifier.create(stream)
            .expectNextMatches(e -> e.event().equals("init"))
            .expectNextMatches(e -> e.event().equals("message"))
            .expectNextMatches(e -> e.event().equals("done"))
            .verifyComplete();
    }
}
```

---

## Known Issues & Improvements

### Critical Issues ⚠️

1. **Complexity** (800+ lines in one class)
   - **Problem**: Hard to maintain, test, debug
   - **Fix**: Extract sub-services:
     - `ToolExecutionOrchestrator` (tool execution logic)
     - `SseEventParser` (event parsing)
     - `ConversationStateTracker` (status updates)
     - `OpenAiRequestBuilder` (request building)

2. **State Management** (many atomic refs, concurrent maps)
   - **Problem**: Prone to race conditions
   - **Fix**: Use formal state machine:
     ```java
     enum StreamingState {
         INITIALIZING, STREAMING_TEXT, WAITING_FOR_APPROVAL,
         EXECUTING_TOOL, FINALIZING, COMPLETED, FAILED
     }
     class StreamStateMachine {
         State transition(Event event) { ... }
     }
     ```

3. **Error Recovery** (limited retry logic)
   - **Problem**: Transient errors fail entire stream
   - **Fix**: Add exponential backoff retry with circuit breaker

### Minor Issues

4. **Missing Timeout Configuration**
   - **Problem**: Hardcoded timeouts (30s, 15s)
   - **Fix**: Move to `application.properties`

5. **No Metrics**
   - **Problem**: Hard to monitor in production
   - **Fix**: Add Micrometer metrics:
     - `streaming.duration` (histogram)
     - `tool.execution.count` (counter)
     - `streaming.errors` (counter)

6. **Database Write Overhead**
   - **Problem**: Saves every message delta
   - **Fix**: Batch updates or save only on completion

---

## Future Enhancements

1. **Multi-tool Execution**
   - Support parallel tool calls
   - Aggregate results before sending to OpenAI

2. **Streaming Interruption**
   - Allow user to stop streaming
   - Endpoint: `POST /api/responses/{responseId}/cancel`

3. **Response Caching**
   - Cache similar requests
   - Reduce OpenAI API calls

4. **Webhook Support**
   - Alternative to SSE for high-latency scenarios
   - POST final response to webhook URL

---

## Contributing

When modifying streaming logic:
1. Add unit tests for new event types
2. Update state machine diagram
3. Add integration tests for E2E flow
4. Update metrics if adding new operations
5. Update this documentation

---

For related documentation:
- [Parent: Backend AGENTS.md](../AGENTS.md)
- [Root: Project AGENTS.md](../../AGENTS.md)
- [MCP Package: AGENTS.md](../mcp/AGENTS.md)
