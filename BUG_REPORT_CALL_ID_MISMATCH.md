# 🔴 KRITISCHER BUG: DB-Schema & Payload-Mapping-Fehler

**Datum:** 5. November 2025  
**Severity:** CRITICAL  
**Scope:** Entity-Design, API-Payload-Mapping

---

## Problem-Zusammenfassung

Das Backend erwartet und speichert ein `call_id`-Feld, das **in der OpenAI Responses API v1 Spec NICHT existiert!**

### API-Spec (events_responses_api.md) zeigt:

```json
// response.output_item.added Event
{
  "type": "response.output_item.added",
  "item_id": "msg_1",        // ✅ Existiert
  "output_index": 0
}

// response.function_call_arguments.delta
{
  "type": "response.function_call_arguments.delta",
  "delta": "{\"city\": \"Berlin",
  "item_id": "tool_call_1",  // ✅ Existiert
  "output_index": 0
}

// response.mcp_call.in_progress
{
  "type": "response.mcp_call.in_progress",
  "item_id": "ext_tool_1",   // ✅ Existiert
  "output_index": 0
}
```

**Nirgendwo wird `call_id` erwähnt!**

---

## Backend-Implementierung (ResponseStreamService.java:248-271)

```java
if ("function_call".equals(type)) {
    // ❌ Sucht nach call_id im item-Objekt
    String callId = item.path("call_id").asText(null);
    if (callId == null || callId.isEmpty()) {
        callId = itemId;  // Fallback
    }
    attributes.put("callId", callId);
    // ...
}

if ("mcp_call".equals(type)) {
    // ❌ Gleiches Problem
    String callId = item.path("call_id").asText(null);
    if (callId == null || callId.isEmpty()) {
        callId = itemId;
    }
    attributes.put("callId", callId);
    // ...
}
```

---

## DB-Schema (V1__init_schema.sql)

```sql
CREATE TABLE tool_calls (
    id BIGINT PRIMARY KEY,           -- Auto-increment
    conversation_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    
    call_id VARCHAR(255) NOT NULL,   -- ❌ NICHT IN API!
    
    item_id VARCHAR(255),             -- ✅ Von API
    arguments_json CLOB,
    result_json CLOB,
    status VARCHAR(50) NOT NULL,
    output_index INT,
    created_at TIMESTAMP NOT NULL,
    
    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);

CREATE INDEX idx_tool_calls_call_id ON tool_calls(call_id);  -- ❌ Unnötiger Index
```

**Migration V2:** `call_id` wurde nullable gemacht - wahrscheinlich weil ihr gemerkt habt, dass es nicht immer da ist!

```sql
-- V2__make_call_id_nullable.sql
ALTER TABLE tool_calls ALTER COLUMN call_id VARCHAR(255) NULL;
```

---

## ToolCall Entity (ToolCall.java)

```java
@Table("tool_calls")
public class ToolCall {
    @Id
    private Long id;              // DB auto-increment
    
    private Long conversationId;
    private ToolCallType type;
    private String name;
    
    @Column("call_id")
    private String callId;        // ❌ NICHT IN API
    
    @Column("item_id")
    private String itemId;        // ✅ Von API
    
    @Column("arguments_json")
    private String argumentsJson;
    
    @Column("result_json")
    private String resultJson;
    
    private ToolCallStatus status;
    
    @Column("output_index")
    private Integer outputIndex;
    
    @Column("created_at")
    private Instant createdAt;
}
```

---

## Analyse: Woher kommt `call_id`?

### Hypothese 1: Chat Completions API Verwechslung

Die **Chat Completions API** (nicht Responses API!) hat tatsächlich `tool_call_id`:

```json
// Chat Completions mit Function Calling
{
  "choices": [{
    "message": {
      "tool_calls": [{
        "id": "call_abc123",        // ← Das ist tool_call_id!
        "type": "function",
        "function": {
          "name": "get_weather",
          "arguments": "{\"city\": \"Berlin\"}"
        }
      }]
    }
  }]
}
```

**ABER:** Das ist ein **anderes API-Format**! Die Responses API v1 nutzt Streaming-Events mit `item_id`.

### Hypothese 2: Undokumentierte API-Erweiterung

Möglicherweise sendet OpenAI in `response.output_item.added` ein vollständiges `item`-Objekt mit mehr Feldern als dokumentiert:

```json
{
  "type": "response.output_item.added",
  "output_index": 0,
  "item": {              // ← Vielleicht vollständiges Item-Objekt?
    "id": "tool_call_1", // → wird zu item_id
    "call_id": "call_abc123",  // ← Möglicherweise hier?
    "type": "function_call",
    "name": "get_weather"
  }
}
```

**Problem:**Eure Dokumentation zeigt das nicht!

---

## Auswirkungen

### 1. Redundante Daten

Wenn `call_id` == `item_id` (was der Fallback macht), dann ist das Feld redundant:

```sql
-- Beispiel-Daten:
id | item_id      | call_id      | name
1  | tool_call_1  | tool_call_1  | get_weather  -- Redundant!
2  | ext_tool_2   | ext_tool_2   | fetch_data   -- Redundant!
```

### 2. Verwirrende Semantik

- Was ist der **fachliche Unterschied** zwischen `call_id` und `item_id`?
- Wann sind sie unterschiedlich?
- Welches Feld sollte für Lookups verwendet werden?

### 3. Unnötige Komplexität

```java
// Aktueller Code muss defensiv sein:
String callId = item.path("call_id").asText(null);
if (callId == null || callId.isEmpty()) {
    callId = itemId;  // Immer nötig?
}
```

### 4. Index-Overhead

```sql
CREATE INDEX idx_tool_calls_call_id ON tool_calls(call_id);
```

Wenn `call_id` ≈ `item_id`, dann ist ein separater Index überflüssig!

---

## Empfohlene Lösungen

### Option A: `call_id` komplett entfernen (EMPFOHLEN)

**Wenn `call_id` nicht in der API existiert, brauchen wir es nicht!**

#### 1. Migration erstellen:

```sql
-- V3__remove_call_id.sql
DROP INDEX IF EXISTS idx_tool_calls_call_id;
ALTER TABLE tool_calls DROP COLUMN call_id;
```

#### 2. Entity anpassen:

```java
@Table("tool_calls")
public class ToolCall {
    @Id
    private Long id;
    private Long conversationId;
    private ToolCallType type;
    private String name;
    
    // ❌ REMOVED: private String callId;
    
    @Column("item_id")
    private String itemId;  // ✅ Primary identifier von OpenAI
    
    @Column("arguments_json")
    private String argumentsJson;
    
    @Column("result_json")
    private String resultJson;
    
    private ToolCallStatus status;
    
    @Column("output_index")
    private Integer outputIndex;
    
    @Column("created_at")
    private Instant createdAt;
}
```

#### 3. Code-Cleanup:

```java
// In handleOutputItemAdded():
if ("function_call".equals(type)) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("name", item.path("name").asText(null));
    // ❌ REMOVED: callId handling
    attributes.put("status", ToolCallStatus.IN_PROGRESS);
    attributes.put("outputIndex", outputIndex);
    
    return conversationService.upsertToolCall(
        state.conversationId, 
        itemId,  // ✅ Direkt item_id verwenden
        ToolCallType.FUNCTION, 
        outputIndex, 
        attributes
    ).then();
}
```

#### 4. Repository anpassen:

```java
public interface ToolCallRepository extends ReactiveCrudRepository<ToolCall, Long> {
    Flux<ToolCall> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
    
    // ✅ Lookup via item_id
    Mono<ToolCall> findByConversationIdAndItemId(Long conversationId, String itemId);
    
    // ❌ REMOVED: findByCallId() falls vorhanden
}
```

---

### Option B: Tatsächliche API-Payload klären (Falls call_id doch existiert)

**Falls OpenAI tatsächlich `call_id` sendet, brauchen wir:**

1. **Vollständige Payload-Dokumentation** - Was ist in `item` bei `response.output_item.added`?
2. **Testdaten** - Real-World API-Response loggen
3. **Semantik klären** - Wann unterscheiden sich `call_id` und `item_id`?

#### Test-Ansatz:

```java
private Mono<Void> handleOutputItemAdded(JsonNode payload, StreamState state) {
    JsonNode item = payload.path("item");
    
    // 🔍 Debug-Logging
    log.info("📦 output_item.added payload: {}", payload.toPrettyString());
    log.info("📦 item fields: {}", item.toPrettyString());
    
    String itemId = item.path("id").asText();
    String callId = item.path("call_id").asText(null);
    
    if (callId != null && !callId.equals(itemId)) {
        log.warn("⚠️ call_id ≠ item_id! call_id={}, item_id={}", callId, itemId);
    }
    
    // ...
}
```

---

### Option C: Hybrid - call_id nur wenn vorhanden

**Falls `call_id` optional ist aber semantisch wichtig:**

```java
@Column("call_id")
private String callId;  // Nullable, falls OpenAI es sendet

// Getter mit Fallback:
public String getEffectiveCallId() {
    return callId != null ? callId : itemId;
}
```

**ABER:** Das erklärt nicht, wofür `call_id` dann gut ist!

---

## Empfehlung: ACTION PLAN

### Phase 1: Klärung (SOFORT)

1. ✅ Real-World API-Response loggen mit vollständigem `item`-Payload
2. ✅ Prüfen: Sendet OpenAI jemals `call_id` ≠ `item_id`?
3. ✅ Dokumentation vervollständigen (falls `call_id` existiert)

### Phase 2: Cleanup (falls call_id nicht existiert)

4. ✅ Migration: `call_id` entfernen
5. ✅ Entity: Feld löschen
6. ✅ Code: Fallback-Logik entfernen
7. ✅ Repository: Queries anpassen
8. ✅ Tests: `call_id`-Referenzen entfernen

### Phase 3: Index-Optimierung

9. ✅ `idx_tool_calls_call_id` entfernen
10. ✅ `idx_tool_calls_item_id` hinzufügen (falls noch nicht vorhanden)

```sql
CREATE INDEX idx_tool_calls_item_id ON tool_calls(conversation_id, item_id);
```

---

## Fragen zur Klärung

1. **Habt ihr jemals `call_id` ≠ `item_id` in Logs gesehen?**
2. **Woher kam die Idee für `call_id`?** (Chat Completions API verwechselt?)
3. **Wird `call_id` irgendwo im Frontend/Code verwendet?**
4. **Gibt es Use-Cases wo `call_id` benötigt wird?**

---

## Zusätzliches Problem: Andere Felder fehlen auch in Doku

Die `events_responses_api.md` zeigt **nur Event-Hüllen**, nicht vollständige Payloads:

### Was fehlt:
- ❌ Vollständige Struktur von `item` in `response.output_item.added`
- ❌ Vollständige Struktur von `item` in `response.output_item.done`
- ❌ Felder wie `name`, `type`, `output`, `error` in Tool-Items

### Empfehlung:
Echte OpenAI API-Responses loggen und vollständige Payload-Struktur dokumentieren!

```java
// In ResponseStreamService:
private Flux<ServerSentEvent<String>> upstream = ...
    .doOnNext(event -> {
        if (log.isDebugEnabled()) {
            log.debug("📨 SSE Event: type={}, data={}", 
                     event.event(), 
                     event.data());
        }
    });
```

---

**Ende des Zusatz-Reports**
