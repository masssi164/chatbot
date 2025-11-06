# Bug Report: OpenAI Responses API v1 Compliance & SSE Stream Handling

**Report erstellt:** 5. November 2025  
**Reviewer:** Senior Backend Developer (Spring Boot, Spring Reactor, R2DBC, SSE)  
**Severity:** HIGH (Kritische Inkonsistenzen gefunden)  
**Scope:** MCP Tool Events + Core Response Lifecycle (keine built-in Tools wie web_search/file_search)

---

## Executive Summary

Die Backend-Implementierung (`ResponseStreamService`, `ResponseStreamController`) zeigt **kritische Abweichungen** von der OpenAI Responses API v1 Spezifikation. Als SSE-Proxy zwischen OpenAI und Frontend werden wichtige Lifecycle-Events nicht korrekt weitergeleitet, was zu inkonsistentem Client-Verhalten, fehlender Fehlerbehandlung und potenziellen Stream-Leaks führt.

**Hauptprobleme:**
1. ❌ **Fehlende Lifecycle-Events** (`response.created`, `response.completed`, `response.incomplete`)
2. ❌ **Unvollständige Error-Event-Behandlung** 
3. ⚠️ **R2DBC Backpressure-Risiken** bei hoher Last
4. ⚠️ **Fehlende Stream-Cleanup-Garantien**
5. ⚠️ **Unvollständige MCP Tool-Event-Abdeckung**

---

## 🔴 KRITISCH: Fehlende Lifecycle-Events

### Problem

Die API-Spec definiert obligatorische Stream-Lifecycle-Events:

```
response.created       → Stream startet (IMMER erstes Event)
response.in_progress   → Stream läuft (bei jedem Chunk)
response.completed     → Stream erfolgreich beendet
response.incomplete    → Stream vorzeitig abgebrochen (length limit)
```

**Backend-Implementierung (`ResponseStreamService.handleEvent`)**:
```java
return switch (eventName) {
    case "response.output_text.delta" -> handleTextDelta(payload, state);
    case "response.output_text.done" -> handleTextDone(payload, state, data);
    case "response.output_item.added" -> handleOutputItemAdded(payload, state);
    // ... nur Tool-Events
    default -> Mono.empty();  // ❌ ALLE Lifecycle-Events werden ignoriert!
};
```

**Was fehlt:**
```java
case "response.created" -> handleResponseCreated(payload, state);
case "response.in_progress" -> handleResponseInProgress(payload, state);
case "response.completed" -> handleResponseCompleted(payload, state);
case "response.incomplete" -> handleResponseIncomplete(payload, state);
```

### Auswirkungen

1. **Frontend hat keine Stream-State-Awareness**
   - Keine Information wann Stream wirklich startet (`response.created`)
   - Keine Unterscheidung zwischen "läuft" und "beendet"
   - Client weiß nicht, ob Response vollständig oder abgebrochen wurde

2. **Conversation-Persistierung unzuverlässig**
   - Ohne `response.completed` kein definierter Zeitpunkt für finale DB-Speicherung
   - Response-ID aus `response.created` wird nicht erfasst (benötigt für `previous_response_id` in Folge-Requests)

3. **Fehlerhafte UX für Token-Limits**
   - `response.incomplete` (finish_reason: "length") wird nicht behandelt
   - User bekommt keine Info über abgeschnittene Antworten

### Lösungsstrategie

```java
// In ResponseStreamService.handleEvent() ergänzen:

case "response.created" -> handleResponseCreated(payload, state);
case "response.in_progress" -> Mono.empty(); // Optional: für Monitoring
case "response.completed" -> handleResponseCompleted(payload, state);
case "response.incomplete" -> handleResponseIncomplete(payload, state);

// Neue Handler:
private Mono<Void> handleResponseCreated(JsonNode payload, StreamState state) {
    String responseId = payload.path("response").path("id").asText();
    state.responseId = responseId; // StreamState erweitern!
    
    return conversationService.updateConversationResponseId(
        state.conversationId, 
        responseId
    ).then();
}

private Mono<Void> handleResponseCompleted(JsonNode payload, StreamState state) {
    return conversationService.finalizeConversation(
        state.conversationId,
        state.responseId
    ).then();
}

private Mono<Void> handleResponseIncomplete(JsonNode payload, StreamState state) {
    JsonNode response = payload.path("response");
    String reason = response.path("status_details").path("reason").asText("unknown");
    
    return conversationService.markConversationIncomplete(
        state.conversationId,
        reason
    ).then();
}
```

---

## 🔴 KRITISCH: Unvollständige Error-Event-Behandlung

### Problem

API-Spec definiert 3 Fehler-Event-Typen:

1. **`response.failed`** - Stream-Fehler nach Start (inkl. error-Details)
2. **`response.error`** - Fehler während Generierung (z.B. rate limit)
3. **`error`** - Kritische Fehler (z.B. ungültiger API-Key)

**Backend-Implementierung:**
```java
// ResponseStreamService.buildErrorEvent() - nur bei lokalen Exceptions
private ServerSentEvent<String> buildErrorEvent(Throwable error) {
    // ...
    return ServerSentEvent.<String>builder(errorNode.toString())
            .event("response.failed")  // ❌ Nur EIGENE Fehler, nicht von OpenAI!
            .build();
}
```

**handleEvent() behandelt KEINE Error-Events:**
```java
return switch (eventName) {
    // ... nur Success-Events
    default -> Mono.empty();  // ❌ Fehler-Events werden nicht erkannt!
};
```

### Auswirkungen

1. **OpenAI-Fehler werden blind weitergeleitet**
   - Client erhält raw `response.failed`/`error` Events ohne Backend-Kenntnis
   - Keine DB-Persistierung von Fehler-Status
   - Conversation bleibt in "in_progress"-State hängen

2. **Keine Retry-Logik möglich**
   - Rate-Limit-Fehler (`response.error` mit `rate_limit_exceeded`) werden nicht erkannt
   - Transiente Fehler nicht von permanenten unterscheidbar

3. **Memory-Leaks bei Fehlern**
   - `StreamState.clear()` wird nur bei erfolgreicher Completion aufgerufen (via `doFinally`)
   - Bei upstream-Errors könnte State nicht aufgeräumt werden

### Lösungsstrategie

```java
// In handleEvent() ergänzen:
case "response.failed" -> handleResponseFailed(payload, state);
case "response.error" -> handleResponseError(payload, state);
case "error" -> handleCriticalError(payload, state);

// Handler implementieren:
private Mono<Void> handleResponseFailed(JsonNode payload, StreamState state) {
    JsonNode response = payload.path("response");
    JsonNode error = response.path("error");
    
    String errorCode = error.path("code").asText("unknown");
    String errorMessage = error.path("message").asText("");
    
    return conversationService.markConversationFailed(
        state.conversationId,
        errorCode,
        errorMessage
    ).then();
}

private Mono<Void> handleResponseError(JsonNode payload, StreamState state) {
    JsonNode error = payload.path("error");
    String code = error.path("code").asText();
    
    // Rate-Limit spezielle Behandlung
    if ("rate_limit_exceeded".equals(code)) {
        log.warn("Rate limit hit for conversation {}", state.conversationId);
        // Optional: Signal für Retry-Queue
    }
    
    return conversationService.logError(
        state.conversationId,
        code,
        error.path("message").asText()
    ).then();
}

private Mono<Void> handleCriticalError(JsonNode payload, StreamState state) {
    // Auth-Fehler etc. - Stream wird abgebrochen
    return conversationService.markConversationFailed(
        state.conversationId,
        "critical_error",
        payload.path("error").path("message").asText()
    ).then();
}
```

**Wichtig:** Error-Handler müssen **VOR** `cloneEvent()` ausgeführt werden, damit Client nicht Fehler-Event empfängt bevor Backend reagiert hat!

```java
// Aktuell:
Flux<ServerSentEvent<String>> processed = upstream.concatMap(event -> 
    handleEvent(event, state).thenReturn(cloneEvent(event))  // ❌ Event wird IMMER weitergeleitet
);

// Besser:
Flux<ServerSentEvent<String>> processed = upstream.concatMap(event -> 
    handleEvent(event, state)
        .then(shouldForwardEvent(event)  // 🔧 Optionale Filterung
            ? Mono.just(cloneEvent(event))
            : Mono.empty()
        )
);
```

---

## ⚠️ WARNUNG: Fehlende Content-Part Events

### Problem

API-Spec definiert feingranulare Content-Events:

```
response.content_part.added    → Neuer Content-Teil beginnt
response.content_part.done     → Content-Teil beendet
response.output_item.done      → Gesamtes Output-Item fertig
```

**Backend behandelt nur:**
- ✅ `response.output_item.added`
- ❌ `response.content_part.added` - fehlt
- ❌ `response.content_part.done` - fehlt
- ❌ `response.output_item.done` - fehlt

### Auswirkungen

**Bei Multi-Content-Responses** (z.B. Text + Reasoning Summary):
```
OpenAI sendet:
1. response.output_item.added (item_id: "msg_1", type: "output_text")
2. response.content_part.added (content_index: 0)
3. response.output_text.delta (mehrfach)
4. response.content_part.done (content_index: 0)  ❌ NICHT BEHANDELT
5. response.output_item.done (item_id: "msg_1")   ❌ NICHT BEHANDELT
```

**Problem:** Backend persistiert Text bereits bei `output_text.done`, **ABER:**
- Kein Signal dass das gesamte Item komplett ist
- Bei Multi-Part Items (z.B. Reasoning + Text) wird möglicherweise zu früh gespeichert

### Lösungsstrategie

```java
case "response.content_part.added" -> handleContentPartAdded(payload, state);
case "response.content_part.done" -> handleContentPartDone(payload, state);
case "response.output_item.done" -> handleOutputItemDone(payload, state);

private Mono<Void> handleOutputItemDone(JsonNode payload, StreamState state) {
    JsonNode item = payload.path("item");
    String itemId = item.path("id").asText();
    
    // Finale Validierung & Persistierung
    return conversationService.finalizeOutputItem(
        state.conversationId,
        itemId
    ).then();
}
```

---

## ⚠️ WARNUNG: Unvollständige MCP Tool-Event-Abdeckung

### Problem

API-Spec definiert für MCP-Tools folgende Events:

```
response.mcp_call.in_progress          → MCP Tool startet
response.mcp_call_arguments.delta      → Argumente werden gestreamt  
response.mcp_call_arguments.done       → Argumente vollständig
response.mcp_call.completed            → MCP Tool fertig
response.mcp_call.failed               → MCP Tool fehlgeschlagen
response.mcp_list_tools.in_progress    → Tool-Liste wird abgerufen
response.mcp_list_tools.completed      → Tool-Liste verfügbar
response.mcp_list_tools.failed         → Tool-Liste Fehler
```

**Backend behandelt:**
- ✅ `response.mcp_call_arguments.delta`
- ✅ `response.mcp_call_arguments.done`
- ✅ `response.mcp_call.in_progress`
- ✅ `response.mcp_call.completed`
- ✅ `response.mcp_call.failed`
- ✅ `response.mcp_list_tools.in_progress`
- ✅ `response.mcp_list_tools.completed`
- ✅ `response.mcp_list_tools.failed`

**ABER:** Es gibt Probleme bei der ID-Behandlung und fehlende `executing` Events.

### Auswirkungen

#### 1. call_id vs. item_id Verwirrung

**DB-Schema:**
```sql
CREATE TABLE tool_calls (
    id BIGINT PRIMARY KEY,          -- Interne DB-ID (auto-increment)
    item_id VARCHAR(255),            -- OpenAI Item-ID (z.B. "msg_1")
    call_id VARCHAR(255),            -- Tool Call-ID (z.B. "call_abc123")
    name VARCHAR(255),               -- Tool-Name (z.B. "get_weather")
    ...
);
```

**OpenAI sendet bei MCP-Calls:**
```json
{
  "type": "response.output_item.added",
  "item": {
    "id": "msg_1",              // → item_id (unique per output item)
    "type": "mcp_call",
    "call_id": "call_abc123",   // → call_id (tool-spezifisch, kann fehlen!)
    "name": "get_weather"
  }
}
```

**Backend-Logik (ResponseStreamService.java:266-271):**
```java
if ("mcp_call".equals(type)) {
    String callId = item.path("call_id").asText(null);
    if (callId == null || callId.isEmpty()) {
        callId = itemId;  // ⚠️ Fallback auf item_id
    }
    attributes.put("callId", callId);
}
```

**Problem:** 
- `call_id` kann laut API optional sein (V2__make_call_id_nullable.sql bestätigt das)
- Der Fallback ist OK, **ABER** nicht dokumentiert warum
- Es ist unklar ob `call_id` und `item_id` immer gleich sind wenn `call_id` fehlt

**Klärung:** Sind `item_id` und `call_id` bei MCP-Calls immer identisch wenn keine explizite `call_id` gesendet wird?

#### 2. Fehlende `executing` Event-Behandlung

Die API-Spec erwähnt:
```
response.mcp_call.in_progress   ✅ Behandelt
response.mcp_call.executing     ❌ Fehlt (optional, zeigt aktive Ausführung)
response.mcp_call.completed     ✅ Behandelt
```

**Impact:** Niedrig - `executing` ist optional, aber für UX hilfreich (zeigt dass Tool aktiv arbeitet vs. nur gestartet).

#### 3. MCP List Tools Events sind "No-Op"

```java
private Mono<Void> handleMcpListToolsEvent(JsonNode payload, String status) {
    log.debug("MCP list tools event: {} for item {}", status, itemId);
    // ❌ These events are informational - no persistence needed yet
    return Mono.empty();
}
```

**Mögliche Verbesserung:** Diese Events könnten für Debugging/Audit-Log genutzt werden.

### Lösungsstrategie

#### A) Optionales `executing` Event

```java
case "response.mcp_call.executing" -> updateToolCallStatus(
    payload, state, ToolCallStatus.EXECUTING, null
);

// ToolCallStatus Enum erweitern:
public enum ToolCallStatus {
    IN_PROGRESS,
    EXECUTING,    // 🔧 Neu: Tool läuft aktiv (optional)
    COMPLETED,
    FAILED
}
```

**Alternative:** Wenn Status-Granularität nicht benötigt wird, `executing` weiter ignorieren (aktuelles Verhalten OK).

#### B) call_id Dokumentation

```java
// Dokumentation im Code ergänzen:
if ("mcp_call".equals(type)) {
    // call_id ist optional laut API-Spec.
    // Fallback auf item_id ist valide, da beide denselben Call identifizieren.
    String callId = item.path("call_id").asText(null);
    if (callId == null || callId.isEmpty()) {
        callId = itemId;  
    }
    attributes.put("callId", callId);
}
```

#### C) MCP List Tools Tracking (optional, nice-to-have)

```java
private Mono<Void> handleMcpListToolsEvent(JsonNode payload, String status) {
    if ("completed".equals(status)) {
        log.info("MCP tools listed for conversation {}", state.conversationId);
        // Optional: Audit-Log oder Metrics
    }
    return Mono.empty();
}
```

**Hinweis:** Built-in Tools (web_search, file_search) sind out-of-scope, da nur MCP-Tools relevant sind.

---

## 🔴 KRITISCH: R2DBC Backpressure & Concurrency Issues

### Problem

**Streaming + R2DBC = Backpressure-Risiko**

Aktueller Flow:
```java
Flux<ServerSentEvent<String>> processed = upstream.concatMap(event -> 
    handleEvent(event, state)  // ❌ Kann DB-Writes triggern!
        .thenReturn(cloneEvent(event))
);
```

**Was passiert:**
1. SSE-Event kommt von OpenAI (schnell, ~100ms/token)
2. `handleEvent()` macht R2DBC-Write (langsamer, ~10-50ms)
3. `concatMap` wartet auf DB-Write **BEVOR** nächstes Event verarbeitet wird

**Risiken:**

### 1. Upstream-Timeout bei langsamen DB-Writes

```
OpenAI sendet:  Event1 → Event2 → Event3 → ...
                  ↓       ↓        ↓
Backend DB:     [Write1-50ms] → [Write2-50ms] → ...
                      ↓
Upstream-Buffer voll → OpenAI schließt Stream!
```

### 2. StreamState-Corruption bei parallelen Requests

`StreamState` ist **NICHT thread-safe genug:**

```java
private static final class StreamState {
    private final Long conversationId;
    private final Map<Integer, StringBuilder> textByOutputIndex = new ConcurrentHashMap<>();
    private final Map<String, ToolCallTracker> toolCalls = new ConcurrentHashMap<>();
    // ❌ Fehlt: responseId, completionStatus, errorState
}
```

**Problem:** 
- `ConcurrentHashMap` schützt nur Map-Operationen
- `StringBuilder` in `textByOutputIndex` ist **NICHT thread-safe!**

```java
// Race Condition möglich:
private Mono<Void> handleTextDelta(JsonNode payload, StreamState state) {
    state.textByOutputIndex
        .computeIfAbsent(outputIndex, ignored -> new StringBuilder())
        .append(delta);  // ❌ StringBuilder nicht synchronized!
}
```

### 3. Memory Leaks bei Stream-Abbruch

```java
Flux<ServerSentEvent<String>> processed = upstream.concatMap(...)
    .doFinally(signal -> state.clear());  // ⚠️ Nur bei NORMALEM Abschluss!
```

**Problem:** Bei Client-Disconnect oder Upstream-Fehler:
- `doFinally` wird aufgerufen
- **ABER:** Laufende DB-Operationen werden nicht gecancelt!
- R2DBC-Connections könnten leak'en

### Lösungsstrategie

#### A) Backpressure-Safe Processing

```java
// Option 1: Entkopplung via Buffer + flatMap
Flux<ServerSentEvent<String>> processed = upstream
    .flatMap(event -> 
        handleEvent(event, state)
            .subscribeOn(Schedulers.boundedElastic())  // Separate DB-Thread
            .thenReturn(cloneEvent(event)),
        256  // Concurrency limit
    )
    .doFinally(signal -> state.clear());

// Option 2: Fire-and-Forget für unkritische Writes
Flux<ServerSentEvent<String>> processed = upstream
    .doOnNext(event -> 
        handleEvent(event, state)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe()  // ❌ Gefährlich: Fehler gehen verloren!
    )
    .map(this::cloneEvent);
```

**Empfehlung:** **Option 1 mit bounded concurrency**
- Verhindert Upstream-Blockierung
- Erhält Fehler-Propagierung
- Limitiert parallele DB-Writes (wichtig bei H2!)

#### B) Thread-Safe StreamState

```java
private static final class StreamState {
    private final Long conversationId;
    private volatile String responseId;  // Volatile für Visibility
    private volatile StreamStatus status = StreamStatus.ACTIVE;
    
    // Thread-safe Text-Akkumulation
    private final Map<Integer, AtomicReference<String>> textByOutputIndex = 
        new ConcurrentHashMap<>();
    
    // Thread-safe Append
    void appendText(int outputIndex, String delta) {
        textByOutputIndex
            .computeIfAbsent(outputIndex, k -> new AtomicReference<>(""))
            .updateAndGet(current -> current + delta);
    }
    
    String getText(int outputIndex) {
        AtomicReference<String> ref = textByOutputIndex.get(outputIndex);
        return ref != null ? ref.get() : "";
    }
}
```

#### C) R2DBC Connection Pool Tuning

**Aktuell:** Keine Pool-Konfiguration in `application.properties`!

```properties
# ❌ Fehlt komplett:
spring.r2dbc.pool.enabled=true
spring.r2dbc.pool.initial-size=10
spring.r2dbc.pool.max-size=50
spring.r2dbc.pool.max-idle-time=30m
spring.r2dbc.pool.max-acquire-time=PT3S
```

**Problem bei H2 In-Memory:**
- H2 unterstützt nur ~1024 parallele Connections
- Bei Streaming-Last könnten Connections erschöpft werden

**Lösung:**
```properties
# Für H2 In-Memory (Development):
spring.r2dbc.pool.enabled=true
spring.r2dbc.pool.initial-size=5
spring.r2dbc.pool.max-size=20  # H2 Limit beachten!
spring.r2dbc.pool.validation-query=SELECT 1

# Für Production (PostgreSQL):
spring.r2dbc.pool.max-size=50
spring.r2dbc.pool.max-acquire-time=PT3S
```

---

## ⚠️ WARNUNG: Fehlende Refusal & Reasoning Events

### Problem

API-Spec definiert spezielle Content-Typen:

**Refusal (Moderation):**
```
response.refusal.delta → Ablehnung wird gestreamt
response.refusal.done  → Ablehnung komplett
```

**Reasoning (O1-Modelle):**
```
response.reasoning_summary.delta → Denkschritte werden gestreamt
response.reasoning_summary.done  → Reasoning fertig
```

**Backend:** Beide werden **komplett ignoriert**.

### Auswirkungen

1. **Moderation-Responses gehen verloren**
   - User sieht leeren Response wenn Content moderiert wird
   - Keine DB-Persistierung von Refusal-Reasons

2. **O1-Reasoning unsichtbar**
   - Bei O1-Modellen mit Reasoning-Output fehlt dieser komplett
   - Wichtig für Transparenz (OpenAI's "Chain-of-Thought")

### Lösungsstrategie

```java
case "response.refusal.delta" -> handleRefusalDelta(payload, state);
case "response.refusal.done" -> handleRefusalDone(payload, state);
case "response.reasoning_summary.delta" -> handleReasoningDelta(payload, state);
case "response.reasoning_summary.done" -> handleReasoningDone(payload, state);

private Mono<Void> handleRefusalDone(JsonNode payload, StreamState state) {
    String itemId = payload.path("item_id").asText();
    String refusalText = payload.path("refusal").asText();
    
    return conversationService.appendMessage(
        state.conversationId,
        MessageRole.ASSISTANT,
        refusalText,
        payload.toString(),
        payload.path("output_index").asInt(0),
        itemId
    ).then();
}
```

---

## 🔧 Zusätzliche Verbesserungsvorschläge

### 1. Enhanced Logging für Debugging

```java
private Mono<Void> handleEvent(ServerSentEvent<String> event, StreamState state) {
    String eventName = event.event();
    
    // Metric-Tracking für unbekannte Events
    if (!KNOWN_EVENTS.contains(eventName)) {
        log.warn("⚠️ Unknown event type received: {} (conv_id: {})", 
                 eventName, state.conversationId);
        // Optional: Metric export für Monitoring
    }
    
    return switch (eventName) {
        // ...
    };
}
```

### 2. Circuit Breaker für DB-Writes

```java
@Bean
public ReactiveResilience4JCircuitBreakerFactory circuitBreakerFactory() {
    return new ReactiveResilience4JCircuitBreakerFactory();
}

// In ResponseStreamService:
private Mono<Void> handleTextDone(JsonNode payload, StreamState state, String rawJson) {
    return circuitBreaker.run(
        conversationService.updateMessageContent(...).then(),
        throwable -> Mono.fromRunnable(() -> 
            log.error("DB write failed, message queued for retry: {}", itemId)
        )
    );
}
```

### 3. Conversation State Machine

```java
public enum ConversationState {
    CREATED,
    STREAMING,
    COMPLETED,
    INCOMPLETE,
    FAILED,
    CANCELLED
}

// In Conversation Entity:
@Column
private ConversationState state = ConversationState.CREATED;

// Lifecycle-Methoden mit State-Validierung:
public void markCompleted() {
    if (this.state != ConversationState.STREAMING) {
        throw new IllegalStateException("Cannot complete conversation in state: " + state);
    }
    this.state = ConversationState.COMPLETED;
}
```

### 4. Structured Event Audit Log

```java
@Entity
@Table(name = "event_audit")
public class EventAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private Long conversationId;
    private String eventType;
    private String payload;
    private Instant timestamp;
    
    // Für Debugging unbekannter Events & Compliance
}
```

---

## 📊 Priorisierung

| Prio | Issue | Aufwand | Impact | Risiko |
|------|-------|---------|--------|--------|
| 🔴 P0 | Lifecycle-Events (`response.created`, `completed`) | Medium | Critical | Data Loss |
| 🔴 P0 | Error-Event-Handling | Medium | Critical | Silent Failures |
| 🟠 P1 | Backpressure-Strategie | High | High | Timeouts |
| 🟠 P1 | StreamState Thread-Safety | Low | High | Corruption |
| 🟡 P2 | MCP `executing` Event (optional) | Low | Low | UX Enhancement |
| 🟡 P2 | Content-Part Events | Low | Low | Edge Cases |
| 🟢 P3 | Refusal/Reasoning Events | Low | Low | Nice-to-Have |

**Hinweis:** Built-in Tool-Events (web_search, file_search) sind out-of-scope, da nur MCP-Tools relevant sind.

---

## 🎯 Empfohlener Action Plan

### Phase 1: Critical Fixes (Woche 1)
1. ✅ Lifecycle-Events implementieren (`response.created`, `completed`, `incomplete`)
2. ✅ Error-Event-Handler (`response.failed`, `response.error`, `error`)
3. ✅ StreamState um `responseId` und Status erweitern
4. ✅ Conversation State Machine einführen

### Phase 2: Stability (Woche 2)
5. ✅ Thread-safe StreamState (AtomicReference statt StringBuilder)
6. ✅ Backpressure-Strategie mit `flatMap` + bounded concurrency
7. ✅ R2DBC Pool-Konfiguration
8. ✅ Enhanced Error-Logging für unbekannte Events

### Phase 3: Polish & Optional Features (Woche 3)
9. ⚙️ MCP `executing` Event (optional, für bessere UX)
10. ⚙️ Content-Part Events (für Multi-Content-Responses)
11. ⚙️ Refusal/Reasoning Events (für O1-Modelle)
12. ⚙️ Event Audit Log (optional, für Debugging)

**Hinweis:** Phase 3 kann niedrig priorisiert werden, da MCP-Core-Funktionalität bereits in Phase 1+2 abgedeckt ist.

---

## 🧪 Testabdeckung

**Fehlende Tests:**
```java
@Test
void shouldHandleResponseLifecycleEvents() {
    // response.created → response.in_progress → response.completed
}

@Test
void shouldPersistResponseIdFromCreatedEvent() {
    // Verify conversation.responseId is set
}

@Test
void shouldHandleIncompleteResponses() {
    // response.incomplete mit finish_reason: "length"
}

@Test
void shouldHandleUpstreamErrors() {
    // response.failed, response.error, error
}

@Test
void shouldNotCorruptStreamStateUnderConcurrency() {
    // Parallel text deltas
}

@Test
void shouldHandleClientDisconnectGracefully() {
    // Cancel stream mid-flight
}

@Test
void shouldHandleUnknownToolEvents() {
    // response.web_search_call.in_progress
}
```

---

## 📚 Referenzen

- OpenAI Responses API v1: https://v03.api.js.langchain.com/
- Masaic AI Mintlify: https://masaic-ai.mintlify.app/
- Spring Reactor Backpressure: https://projectreactor.io/docs/core/release/reference/#backpressure
- R2DBC Connection Pool: https://r2dbc.io/spec/1.0.0.RELEASE/spec/html/#connections.pooling

---

## 🤝 Kontakt für Rückfragen

Bei Unklarheiten zur Priorisierung oder technischen Details bitte via Pull-Request-Kommentar melden.

---

**Ende des Reports**
