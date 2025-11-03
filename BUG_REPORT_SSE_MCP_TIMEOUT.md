# Bug Report: SSE MCP Server Connection Timeout

**Datum:** 2. November 2025  
**Schweregrad:** 🔴 CRITICAL  
**Status:** Open  
**Betroffene Komponente:** MCP Connection Service (Backend)

---

## 📋 Zusammenfassung

Die Verbindung zu einem selbst-gehosteten n8n SSE MCP Server schlägt mit einem Timeout-Fehler fehl. Der Backend versucht, eine MCP-Session zu öffnen, wartet jedoch 15 Sekunden auf eine Antwort und läuft dann in einen Timeout.

---

## 🐛 Problem-Beschreibung

### Symptome
1. **Timeout während Session-Initialisierung**: Der MCP-Client wartet auf eine Antwort vom n8n SSE-Server, erhält jedoch keine Daten innerhalb von 15 Sekunden
2. **Wiederholte Verbindungsversuche**: Das System versucht mehrfach, die Verbindung herzustellen, alle schlagen fehl
3. **Status bleibt auf ERROR**: Der MCP-Server wird als "ERROR" markiert und ist nicht nutzbar

### Fehlermeldungen aus Logs

```
2025-11-02T22:24:12.082+01:00  INFO 59408 --- [io-8080-exec-10] app.chatbot.mcp.McpConnectionService     : Opening MCP session for server b38fde28-beac-4daa-8224-6d7e8afc1e03

2025-11-02T22:24:27.095+01:00 ERROR 59408 --- [io-8080-exec-10] app.chatbot.mcp.McpConnectionService     : Connect and sync failed for server b38fde28-beac-4daa-8224-6d7e8afc1e03

java.lang.IllegalStateException: Timeout on blocking read for 15000000000 NANOSECONDS
        at reactor.core.publisher.BlockingSingleSubscriber.blockingGet(BlockingSingleSubscriber.java:129)
        at reactor.core.publisher.Mono.block(Mono.java:1807)
        at app.chatbot.mcp.McpConnectionService.connectAndSync(McpConnectionService.java:153)
```

**Fehler-Kette:**
1. `Timeout on blocking read for 15000000000 NANOSECONDS` (15 Sekunden)
2. Wrapped in: `java.lang.IllegalStateException`
3. Wrapped in: `java.lang.RuntimeException: Connect/sync failed`

---

## 🔍 Root Cause Analysis

### 1. **SSE Transport Implementierung: Diskrepanz**

#### Problem im Backend (`McpSessionRegistry.java`)

**Zeilen 248-256:**
```java
// For SSE: Use full URL as baseUri and "/" as sseEndpoint
// This prevents the SDK from incorrectly splitting the URL
return HttpClientSseClientTransport
    .builder(endpoint.fullUrl())  // ❌ Verwendet VOLLSTÄNDIGE URL als baseUri
    .clientBuilder(clientBuilder)
    .requestBuilder(requestBuilder)
    .sseEndpoint("/")             // ❌ Überschreibt den Pfad mit "/"
    .connectTimeout(properties.connectTimeout())
    .build();
```

**Das Problem:**
- Der Code verwendet `endpoint.fullUrl()` (z.B. `http://localhost:5678/mcp/uuid`) als `baseUri`
- Dann wird `.sseEndpoint("/")` aufgerufen, was den Pfad überschreibt
- **Resultat:** Der MCP-SDK versucht, sich mit `http://localhost:5678/mcp/uuid/` zu verbinden (trailing slash!)
- **n8n erwartet aber:** `http://localhost:5678/mcp/uuid` (ohne trailing slash)

#### Vergleich: `McpConnectionService.java` (funktioniert korrekt)

**Zeilen 515-521:**
```java
return HttpClientSseClientTransport
    .builder(endpoint.baseUri())      // ✅ Verwendet nur Basis-URL
    .clientBuilder(clientBuilder)
    .requestBuilder(requestBuilder)
    .sseEndpoint(endpoint.relativePath()) // ✅ Verwendet relativen Pfad
    .connectTimeout(properties.connectTimeout())
    .build();
```

**Unterschied:**
- `McpConnectionService` trennt korrekt `baseUri` und `relativePath`
- `McpSessionRegistry` mischt beide und überschreibt dann mit `"/"`

---

### 2. **Timeout-Konfiguration zu kurz?**

**Aktuelle Timeouts (`application.properties`):**
```properties
mcp.request-timeout=PT300S           # 300 Sekunden (5 Min)
mcp.initialization-timeout=PT300S    # 300 Sekunden (5 Min)
mcp.connect-timeout=PT60S            # 60 Sekunden (1 Min)
```

**Aber im Code (`McpConnectionService.java:153`):**
```java
var client = sessionMono.block(Duration.ofSeconds(15));  // ❌ Hart kodierte 15s!
```

**Problem:**
- Die konfigurierten Timeouts (60s, 300s) werden ignoriert
- Ein hart kodierter Timeout von **nur 15 Sekunden** wird verwendet
- Das ist zu kurz für SSE-Handshakes, besonders bei langsamen Netzwerken

---

### 3. **n8n SSE Endpoint-Spezifikation**

**n8n MCP Server URL:**
```
http://localhost:5678/mcp/2714421f-0865-468b-b938-0d592153a235
```

**Laut Microsoft Docs (Azure API Management MCP):**

| Transport Type | Endpoints | Notes |
|----------------|-----------|-------|
| SSE (deprecated) | `/sse` - SSE connection<br>`/messages` - bidirectional | Deprecated as of 2024-11-05 |
| Streamable HTTP | `/mcp` | Replaces HTTP + SSE |

**n8n verwendet einen benutzerdefinierten Pfad mit UUID!**
- Standard wäre: `http://localhost:5678/sse`
- n8n nutzt: `http://localhost:5678/mcp/{uuid}`

**`McpEndpointResolver` Logik:**
```java
// Zeilen 56-62: Session URL detection
if (normalizedPath.matches(".*[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}.*")) {
    return endpoints; // Session URL - use ONLY as provided
}
```

✅ **Der Resolver erkennt die UUID korrekt!**  
❌ **ABER:** `McpSessionRegistry` baut die URL falsch zusammen

---

## 🧪 Tests & Validierung

### Was funktioniert (wahrscheinlich)
- `McpConnectionService.verify()` mit SSE (verwendet korrekte URL-Konstruktion)
- Streamable HTTP Transport

### Was nicht funktioniert
- `McpConnectionService.connectAndSync()` → Ruft `McpSessionRegistry.getOrCreateSession()` auf
- `McpSessionRegistry` baut SSE URL falsch zusammen
- Timeout nach 15 Sekunden

---

## 💡 Lösungsstrategien

### **Strategie 1: Fix SSE Transport in McpSessionRegistry** (EMPFOHLEN)

**Änderung in `McpSessionRegistry.java`, Zeilen 248-256:**

```java
// VORHER (FALSCH):
return HttpClientSseClientTransport
    .builder(endpoint.fullUrl())      // ❌ Falsch
    .clientBuilder(clientBuilder)
    .requestBuilder(requestBuilder)
    .sseEndpoint("/")                 // ❌ Überschreibt
    .connectTimeout(properties.connectTimeout())
    .build();

// NACHHER (RICHTIG):
return HttpClientSseClientTransport
    .builder(endpoint.baseUri())      // ✅ Nur Basis-URL
    .clientBuilder(clientBuilder)
    .requestBuilder(requestBuilder)
    .sseEndpoint(endpoint.relativePath()) // ✅ Relativer Pfad
    .connectTimeout(properties.connectTimeout())
    .build();
```

**Vorteile:**
- Minimal invasiv
- Konsistent mit `McpConnectionService`
- Behebt URL-Konstruktionsproblem

---

### **Strategie 2: Timeout-Konfiguration anpassen**

**Änderung in `McpConnectionService.java`, Zeile 153:**

```java
// VORHER:
var client = sessionMono.block(Duration.ofSeconds(15));

// NACHHER:
var client = sessionMono.block(properties.initializationTimeout());
```

**Zusätzlich in `application.properties`:**
```properties
# Für selbst-gehostete Server ggf. erhöhen
mcp.initialization-timeout=${MCP_INITIALIZATION_TIMEOUT:PT60S}
```

**Vorteile:**
- Nutzt konfigurierbare Timeouts
- Flexibler für verschiedene Szenarien
- Gibt langsamen Servern mehr Zeit

---

### **Strategie 3: Detailliertes Logging hinzufügen**

**Temporär zur Diagnose in `McpSessionRegistry.java`:**

```java
private McpClientTransport createTransport(...) {
    // ... existing code ...
    
    if (transport == McpTransport.SSE) {
        String finalUrl = endpoint.baseUri() + endpoint.relativePath();
        log.info("SSE Transport - BaseUri: {}, RelativePath: {}, Final URL: {}", 
                 endpoint.baseUri(), endpoint.relativePath(), finalUrl);
        
        return HttpClientSseClientTransport
            .builder(endpoint.baseUri())
            .clientBuilder(clientBuilder)
            .requestBuilder(requestBuilder)
            .sseEndpoint(endpoint.relativePath())
            .connectTimeout(properties.connectTimeout())
            .build();
    }
    // ...
}
```

**Vorteile:**
- Hilft bei der Diagnose
- Zeigt die tatsächlich verwendete URL
- Kann nach Fix entfernt werden

---

## 🎯 Empfohlene Lösung (Kombination)

### Phase 1: Sofort-Fix (Critical)
1. **Fix SSE URL-Konstruktion** (Strategie 1)
   - `McpSessionRegistry.createTransport()` korrigieren
   - Von `endpoint.fullUrl() + "/" ` zu `endpoint.baseUri() + endpoint.relativePath()`

2. **Timeout erhöhen** (Strategie 2)
   - Hart kodierte 15s ersetzen durch `properties.initializationTimeout()`
   - Default auf 60s setzen

### Phase 2: Verbesserungen (High Priority)
3. **Logging hinzufügen** (Strategie 3)
   - URL-Konstruktion loggen
   - HTTP-Status-Codes loggen
   - SSE Event-Stream loggen

4. **Error Handling verbessern**
   - Unterschiedliche Fehlermeldungen für:
     - Netzwerk-Timeouts
     - HTTP-Fehler (4xx, 5xx)
     - SSL/TLS-Probleme
     - URL-Parsing-Fehler

### Phase 3: Testing (Medium Priority)
5. **Integration Tests**
   - Test mit n8n SSE Server
   - Test mit Standard `/sse` Endpoint
   - Test mit UUIDs im Pfad
   - Timeout-Szenarien

---

## 📚 Relevante Dokumentation

### Microsoft Docs
1. **MCP Server Endpoints** (Azure API Management)
   - SSE Endpoint: `/sse` (deprecated)
   - Streamable HTTP: `/mcp` (recommended)
   - [Quelle](https://learn.microsoft.com/en-us/azure/api-management/mcp-server-overview#mcp-server-endpoints)

2. **Server-Sent Events in Azure**
   - Content-Type: `text/event-stream`
   - Unidirectional: Server → Client
   - [Quelle](https://learn.microsoft.com/en-us/azure/application-gateway/for-containers/server-sent-events)

### Model Context Protocol (MCP)
1. **Architecture Overview**
   - Client-Server model
   - JSON-RPC 2.0 messaging
   - [Quelle](https://modelcontextprotocol.io/docs/concepts/architecture)

2. **Transport Types**
   - `stdio` (local)
   - `sse` (deprecated)
   - `streamable-http` (recommended)
   - [Quelle](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)

---

## 🔗 Betroffene Dateien

### Backend
1. `chatbot-backend/src/main/java/app/chatbot/mcp/McpSessionRegistry.java`
   - **Zeilen 248-256**: SSE Transport Implementierung ❌
   
2. `chatbot-backend/src/main/java/app/chatbot/mcp/McpConnectionService.java`
   - **Zeile 153**: Hart kodierter 15s Timeout ❌
   - **Zeilen 515-521**: Korrekte SSE Implementierung ✅

3. `chatbot-backend/src/main/java/app/chatbot/mcp/McpEndpointResolver.java`
   - **Zeilen 56-62**: UUID-Erkennung ✅

4. `chatbot-backend/src/main/resources/application.properties`
   - **Zeilen 24-27**: MCP Timeout-Konfiguration

### Frontend
- Frontend scheint korrekt zu sein (verwendet `SSE` als Transport-Type)

---

## 🚀 Nächste Schritte

1. ✅ **Bug Report erstellt**
2. ⏳ **Code-Änderungen implementieren** (Strategie 1 + 2)
3. ⏳ **Lokale Tests durchführen**
4. ⏳ **Mit n8n Server testen**
5. ⏳ **Dokumentation aktualisieren**

---

## 📝 Notizen

- **n8n SSE Server URL:** `http://localhost:5678/mcp/2714421f-0865-468b-b938-0d592153a235`
- **Server ID:** `b38fde28-beac-4daa-8224-6d7e8afc1e03`
- **Transport:** SSE (im Frontend ausgewählt)
- **Fehler tritt auf:** Beim initialen Verbindungsaufbau (nicht beim Handshake-Test)

---

**Erstellt von:** GitHub Copilot  
**Basierend auf:** Backend Logs, Code-Analyse, Microsoft Docs
