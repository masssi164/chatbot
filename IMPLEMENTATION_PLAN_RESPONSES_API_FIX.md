# 🔧 IMPLEMENTATION PLAN: Backend an OpenAI Responses API v1 anpassen

**Datum:** 5. November 2025  
**Scope:** REST Responses API (HTTP/SSE) - NICHT Realtime Audio API  
**Ziel:** Backend-Implementierung gemäß API-Spec korrigieren

---

## Executive Summary

Das Backend muss an die **REST Responses API** angepasst werden. Basierend auf `events_responses_api.md` und der Tatsache, dass ihr die REST API (nicht die Realtime Audio API) nutzt, gibt es folgende kritische Abweichungen:

**KRITISCH:**
1. ❌ `call_id`-Feld wird erwartet, ist aber nicht in events_responses_api.md dokumentiert
2. ❌ Lifecycle-Events (`response.created`, `completed`, `incomplete`) werden ignoriert
3. ❌ Error-Events werden nicht behandelt
4. ⚠️ R2DBC Backpressure-Risiken
5. ⚠️ Thread-Safety-Probleme in `StreamState`

---

## 🎯 Phase 1: call_id Handling klären & bereinigen

### Aktueller Zustand

**DB-Schema:**
```sql
CREATE TABLE tool_calls (
    id BIGINT PRIMARY KEY,
    conversation_id BIGINT,
    type VARCHAR(50),
    name VARCHAR(255),
    
    call_id VARCHAR(255),      -- ⚠️ Nicht in API-Spec!
    item_id VARCHAR(255),       -- ✅ In API-Spec
    
    arguments_json CLOB,
    result_json CLOB,
    status VARCHAR(50),
    output_index INT,
    created_at TIMESTAMP
);
```

**Backend-Code:**
```java
// ResponseStreamService.java:248-271
String callId = item.path("call_id").asText(null);
if (callId == null || callId.isEmpty()) {
    callId = itemId;  // Fallback
}
attributes.put("callId", callId);
```

### Entscheidung

**`call_id` existiert MÖGLICHERWEISE in REST API, aber:**
- Nicht in `events_responses_api.md` dokumentiert
- Realtime Audio API (WebSocket) hat es definitiv, aber das ist eine andere API!
- Der Fallback deutet darauf hin, dass es oft fehlt

**EMPFEHLUNG: `call_id` als optional behandeln, aber primär `item_id` verwenden**

### Migration V3: call_id als echtes Optional

```sql
-- V3__refactor_tool_call_ids.sql

-- call_id ist bereits nullable (seit V2), aber wir fügen Klarheit hinzu:
COMMENT ON COLUMN tool_calls.call_id IS 'Optional tool-specific ID from OpenAI. Falls nicht vorhanden, entspricht es item_id.';
COMMENT ON COLUMN tool_calls.item_id IS 'Primary identifier from OpenAI (unique per output item).';

-- Index auf item_id (wichtiger als call_id!):
CREATE INDEX IF NOT EXISTS idx_tool_calls_item_id ON tool_calls(conversation_id, item_id);

-- call_id Index bleibt für Kompatibilität, falls es doch genutzt wird:
-- CREATE INDEX idx_tool_calls_call_id ON tool_calls(call_id); -- Bereits vorhanden
```

### Code-Anpassung

```java
// ResponseStreamService.java
private Mono<Void> handleOutputItemAdded(JsonNode payload, StreamState state) {
    JsonNode item = payload.path("item");
    if (item.isMissingNode() || !item.hasNonNull("type")) {
        return Mono.empty();
    }

    String type = item.get("type").asText();
    Integer outputIndex = payload.path("output_index").isInt() 
        ? payload.get("output_index").asInt() 
        : null;
    String itemId = item.path("id").asText();

    if ("function_call".equals(type) || "mcp_call".equals(type)) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("name", item.path("name").asText(null));
        
        // call_id ist optional, item_id ist primärer Identifier
        String callId = item.path("call_id").asText(null);
        if (callId == null || callId.isEmpty()) {
            callId = itemId;  // Fallback ist OK
            log.debug("No call_id in payload, using item_id: {}", itemId);
        }
        attributes.put("callId", callId);
        
        attributes.put("status", ToolCallStatus.IN_PROGRESS);
        attributes.put("outputIndex", outputIndex);

        ToolCallType toolType = "mcp_call".equals(type) 
            ? ToolCallType.MCP 
            : ToolCallType.FUNCTION;

        // ✅ Verwende item_id als primary lookup!
        return conversationService.upsertToolCall(
            state.conversationId, 
            itemId,  // ← Primärer Identifier!
            toolType, 
            outputIndex, 
            attributes
        ).doOnNext(toolCall -> state.toolCalls.put(itemId, ToolCallTracker.from(toolCall)))
         .then();
    }

    // ... rest
}
```

### Repository-Anpassung

```java
public interface ToolCallRepository extends ReactiveCrudRepository<ToolCall, Long> {
    // ✅ Primary lookup via item_id
    Mono<ToolCall> findByConversationIdAndItemId(Long conversationId, String itemId);
    
    Flux<ToolCall> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
    
    // Optional: Falls call_id doch gebraucht wird
    Mono<ToolCall> findByConversationIdAndCallId(Long conversationId, String callId);
}
```

---

## 🎯 Phase 2: Lifecycle-Events behandeln

### Problem

```java
// Aktuell in handleEvent():
return switch (eventName) {
    case "response.output_text.delta" -> handleTextDelta(payload, state);
    case "response.output_text.done" -> handleTextDone(payload, state, data);
    // ❌ FEHLT: response.created, response.completed, response.incomplete
    default -> Mono.empty();
};
```

### Lösung

#### 1. StreamState erweitern

```java
private static final class StreamState {
    private final Long conversationId;
    private volatile String responseId;  // ← NEU: Von response.created
    private volatile ConversationStatus status = ConversationStatus.STREAMING;  // ← NEU
    
    private final Map<Integer, AtomicReference<String>> textByOutputIndex = new ConcurrentHashMap<>();
    private final Map<String, ToolCallTracker> toolCalls = new ConcurrentHashMap<>();

    private StreamState(Long conversationId) {
        this.conversationId = conversationId;
    }

    // Thread-safe text append
    void appendText(int outputIndex, String delta) {
        textByOutputIndex
            .computeIfAbsent(outputIndex, k -> new AtomicReference<>(""))
            .updateAndGet(current -> current + delta);
    }
    
    String getText(int outputIndex) {
        AtomicReference<String> ref = textByOutputIndex.get(outputIndex);
        return ref != null ? ref.get() : "";
    }

    private void clear() {
        textByOutputIndex.clear();
        toolCalls.clear();
    }
}
```

#### 2. Lifecycle-Handler implementieren

```java
// In handleEvent() ergänzen:
case "response.created" -> handleResponseCreated(payload, state);
case "response.in_progress" -> Mono.empty(); // Optional: nur Monitoring
case "response.completed" -> handleResponseCompleted(payload, state);
case "response.incomplete" -> handleResponseIncomplete(payload, state);

// Handler:
private Mono<Void> handleResponseCreated(JsonNode payload, StreamState state) {
    JsonNode response = payload.path("response");
    String responseId = response.path("id").asText();
    state.responseId = responseId;
    state.status = ConversationStatus.STREAMING;
    
    log.info("✅ Response created: {} for conversation: {}", responseId, state.conversationId);
    
    return conversationService.updateConversationResponseId(
        state.conversationId, 
        responseId
    ).then();
}

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

private Mono<Void> handleResponseIncomplete(JsonNode payload, StreamState state) {
    JsonNode response = payload.path("response");
    String reason = response.path("status_details").path("reason").asText("length");
    state.status = ConversationStatus.INCOMPLETE;
    
    log.warn("⚠️ Response incomplete ({}): {} for conversation: {}", 
             reason, state.responseId, state.conversationId);
    
    return conversationService.finalizeConversation(
        state.conversationId,
        state.responseId,
        ConversationStatus.INCOMPLETE,
        reason
    ).then();
}
```

#### 3. Conversation Entity erweitern

```java
@Table("conversations")
public class Conversation {
    @Id
    private Long id;
    
    private String title;
    
    @Column("response_id")
    private String responseId;  // ← NEU: Von response.created
    
    private ConversationStatus status = ConversationStatus.CREATED;  // ← NEU
    
    @Column("completion_reason")
    private String completionReason;  // ← NEU: Bei incomplete
    
    @Column("created_at")
    private Instant createdAt;
    
    @Column("updated_at")
    private Instant updatedAt;
}

public enum ConversationStatus {
    CREATED,      // Neu angelegt
    STREAMING,    // Stream läuft (nach response.created)
    COMPLETED,    // Erfolgreich beendet
    INCOMPLETE,   // Vorzeitig beendet (Token-Limit)
    FAILED        // Fehler aufgetreten
}
```

#### 4. Migration V4

```sql
-- V4__add_conversation_lifecycle.sql
ALTER TABLE conversations 
ADD COLUMN response_id VARCHAR(255),
ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
ADD COLUMN completion_reason VARCHAR(255);

CREATE INDEX idx_conversations_response_id ON conversations(response_id);
CREATE INDEX idx_conversations_status ON conversations(status);

COMMENT ON COLUMN conversations.response_id IS 'OpenAI response ID from response.created event';
COMMENT ON COLUMN conversations.status IS 'Lifecycle status: CREATED, STREAMING, COMPLETED, INCOMPLETE, FAILED';
COMMENT ON COLUMN conversations.completion_reason IS 'Reason for INCOMPLETE status (e.g., length, error)';
```

#### 5. ConversationService erweitern

```java
public Mono<Conversation> updateConversationResponseId(Long conversationId, String responseId) {
    return conversationRepository.findById(conversationId)
        .flatMap(conv -> {
            conv.setResponseId(responseId);
            conv.setStatus(ConversationStatus.STREAMING);
            conv.setUpdatedAt(Instant.now());
            return conversationRepository.save(conv);
        });
}

public Mono<Conversation> finalizeConversation(Long conversationId, 
                                               String responseId, 
                                               ConversationStatus status) {
    return finalizeConversation(conversationId, responseId, status, null);
}

public Mono<Conversation> finalizeConversation(Long conversationId, 
                                               String responseId, 
                                               ConversationStatus status,
                                               String completionReason) {
    return conversationRepository.findById(conversationId)
        .flatMap(conv -> {
            conv.setResponseId(responseId);
            conv.setStatus(status);
            conv.setCompletionReason(completionReason);
            conv.setUpdatedAt(Instant.now());
            return conversationRepository.save(conv);
        });
}
```

---

## 🎯 Phase 3: Error-Event-Handling

### Problem

```java
// Fehler-Events werden komplett ignoriert:
default -> Mono.empty();  // ❌
```

### Lösung

```java
// In handleEvent() ergänzen:
case "response.failed" -> handleResponseFailed(payload, state);
case "response.error" -> handleResponseError(payload, state);
case "error" -> handleCriticalError(payload, state);

// Handler:
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

private Mono<Void> handleResponseError(JsonNode payload, StreamState state) {
    JsonNode error = payload.path("error");
    String code = error.path("code").asText();
    String message = error.path("message").asText();
    
    // Rate-Limit spezielle Behandlung
    if ("rate_limit_exceeded".equals(code)) {
        log.warn("⚠️ Rate limit hit for conversation {}", state.conversationId);
        // Optional: Metrics, Retry-Queue
    }
    
    return conversationService.logError(
        state.conversationId,
        code,
        message
    ).then();
}

private Mono<Void> handleCriticalError(JsonNode payload, StreamState state) {
    JsonNode error = payload.path("error");
    String code = error.path("code").asText("unknown");
    String message = error.path("message").asText("");
    
    state.status = ConversationStatus.FAILED;
    
    log.error("❌ Critical error: {} - {} (conversation: {})", 
              code, message, state.conversationId);
    
    return conversationService.finalizeConversation(
        state.conversationId,
        state.responseId,
        ConversationStatus.FAILED,
        "CRITICAL: " + code
    ).then();
}
```

---

## 🎯 Phase 4: R2DBC Backpressure-Optimierung

### Problem

```java
// Aktuell: concatMap blockiert bei jedem DB-Write!
Flux<ServerSentEvent<String>> processed = upstream.concatMap(event -> 
    handleEvent(event, state)  // ❌ Blockiert Stream!
        .thenReturn(cloneEvent(event))
);
```

### Lösung

```java
Flux<ServerSentEvent<String>> processed = upstream
    .flatMap(event -> 
        handleEvent(event, state)
            .subscribeOn(Schedulers.boundedElastic())  // Separate Thread für DB
            .thenReturn(cloneEvent(event)),
        256  // Max 256 parallele DB-Writes
    )
    .doFinally(signal -> {
        log.info("Stream terminated: {} (conversation: {})", signal, state.conversationId);
        state.clear();
    });
```

### R2DBC Pool-Konfiguration

```properties
# application.properties

# H2 In-Memory (Development)
spring.r2dbc.pool.enabled=true
spring.r2dbc.pool.initial-size=5
spring.r2dbc.pool.max-size=20
spring.r2dbc.pool.max-idle-time=30m
spring.r2dbc.pool.max-acquire-time=PT3S
spring.r2dbc.pool.validation-query=SELECT 1

# Production (PostgreSQL) - auskommentiert
# spring.r2dbc.url=r2dbc:postgresql://localhost:5432/chatbot
# spring.r2dbc.pool.max-size=50
# spring.r2dbc.pool.max-acquire-time=PT3S
```

---

## 🎯 Phase 5: Zusätzliche Events (optional)

### MCP `executing` Event

```java
case "response.mcp_call.executing" -> updateToolCallStatus(
    payload, state, ToolCallStatus.EXECUTING, null
);

// ToolCallStatus erweitern:
public enum ToolCallStatus {
    IN_PROGRESS,
    EXECUTING,    // ← NEU: Tool läuft aktiv
    COMPLETED,
    FAILED
}
```

### Content-Part Events

```java
case "response.content_part.added" -> handleContentPartAdded(payload, state);
case "response.content_part.done" -> handleContentPartDone(payload, state);
case "response.output_item.done" -> handleOutputItemDone(payload, state);
```

---

## 📋 Migrations-Reihenfolge

```bash
V1__init_schema.sql               # Bereits vorhanden
V2__make_call_id_nullable.sql     # Bereits vorhanden
V3__refactor_tool_call_ids.sql    # ← NEU: Kommentare + Index
V4__add_conversation_lifecycle.sql # ← NEU: response_id, status, completion_reason
```

---

## 🧪 Testing-Strategie

### Unit Tests

```java
@Test
void shouldHandleResponseCreated() {
    // Verify state.responseId is set
}

@Test
void shouldHandleResponseCompleted() {
    // Verify conversation.status = COMPLETED
}

@Test
void shouldHandleResponseIncomplete() {
    // Verify conversation.status = INCOMPLETE
    // Verify completion_reason is set
}

@Test
void shouldHandleResponseFailed() {
    // Verify conversation.status = FAILED
}

@Test
void shouldFallbackCallIdToItemId() {
    // Test call_id = null → uses item_id
}

@Test
void shouldUseCallIdWhenPresent() {
    // Test call_id != null → uses call_id
}
```

### Integration Tests

```java
@Test
void shouldStreamFullResponseLifecycle() {
    // response.created → text.delta → text.done → response.completed
}

@Test
void shouldHandleBackpressureWithManyEvents() {
    // 1000 events rapid fire
}
```

---

## 🎯 Action Items Checklist

### Phase 1: call_id Bereinigung
- [ ] V3 Migration: Kommentare + Index auf item_id
- [ ] Code-Anpassung: item_id als Primary
- [ ] Repository: findByConversationIdAndItemId
- [ ] Tests: call_id Fallback-Logik

### Phase 2: Lifecycle-Events
- [ ] V4 Migration: response_id, status, completion_reason
- [ ] StreamState: responseId, status hinzufügen
- [ ] Conversation Entity erweitern
- [ ] ConversationService: Lifecycle-Methoden
- [ ] handleResponseCreated/Completed/Incomplete implementieren
- [ ] Tests: Lifecycle-Flow

### Phase 3: Error-Handling
- [ ] handleResponseFailed implementieren
- [ ] handleResponseError implementieren
- [ ] handleCriticalError implementieren
- [ ] ConversationService: logError implementieren
- [ ] Tests: Error-Flows

### Phase 4: Backpressure
- [ ] flatMap mit subscribeOn implementieren
- [ ] R2DBC Pool-Konfiguration
- [ ] Tests: Load-Testing

### Phase 5: Optional Features
- [ ] MCP executing Event (nice-to-have)
- [ ] Content-Part Events (nice-to-have)

---

**Ende des Implementation Plans**
