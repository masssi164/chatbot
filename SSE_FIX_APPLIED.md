# SSE MCP Connection Fix Applied

**Datum:** 2. November 2025  
**Status:** ✅ Applied  
**Betroffene Datei:** `McpSessionRegistry.java`

---

## 🎯 Problem

Der n8n SSE MCP Server konnte nicht verbunden werden, weil:
1. Die URL wurde durch `McpEndpointResolver` unnötig aufgespalten und modifiziert
2. Für SSE EventStreams muss die URL **exakt so** verwendet werden, wie vom User eingegeben
3. Die URL `http://localhost:5678/mcp/uuid` wurde durch `/sse` Suffix o.ä. verändert

---

## ✅ Lösung

### Änderung 1: Direkte URL-Verwendung

**Vorher:**
```java
McpEndpointResolver.Endpoint endpoint = McpEndpointResolver
    .resolveCandidates(server, server.getTransport())
    .get(0);

McpClientTransport transport = createTransport(endpoint, decryptedApiKey, 
    server.getTransport());
```

**Nachher:**
```java
// For SSE: Use baseUrl directly from server without endpoint resolution
// This ensures we connect to the exact URL provided by the user
String targetUrl = server.getBaseUrl();

McpClientTransport transport = createTransport(targetUrl, decryptedApiKey, 
    server.getTransport());
```

**Effekt:** Die URL vom Frontend wird 1:1 übernommen, keine Modifikationen.

---

### Änderung 2: SSE Transport-Konstruktion vereinfacht

**Vorher:**
```java
private McpClientTransport createTransport(McpEndpointResolver.Endpoint endpoint,
                                           String apiKey,
                                           McpTransport transport) {
    // ... code ...
    
    // Verwendet endpoint.baseUri() und endpoint.relativePath()
    // Problem: Split führt zu falscher URL-Konstruktion
}
```

**Nachher:**
```java
private McpClientTransport createTransport(String targetUrl,
                                           String apiKey,
                                           McpTransport transport) {
    // ... code ...
    
    if (transport == McpTransport.STREAMABLE_HTTP) {
        // Parse URL nur für Streamable HTTP
        URI uri = URI.create(targetUrl);
        String baseUri = buildBaseUri(uri);
        String path = uri.getRawPath();
        // ... normale Konstruktion ...
    }
    
    // For SSE: Use the EXACT URL provided by the user
    // SSE EventStream requires the complete URL without modification
    log.debug("Creating SSE transport for URL: {}", targetUrl);
    return HttpClientSseClientTransport
        .builder(targetUrl)           // ✅ Komplette URL
        .clientBuilder(clientBuilder)
        .requestBuilder(requestBuilder)
        .sseEndpoint("/")             // ✅ Dummy-Pfad (wird vom SDK intern verarbeitet)
        .connectTimeout(properties.connectTimeout())
        .build();
}
```

**Effekt:** 
- SSE: Verwendet die komplette URL ohne Splitting
- Streamable HTTP: Parsed die URL korrekt in Base + Path

---

### Änderung 3: Import hinzugefügt

```java
import java.net.URI;  // ✅ Neu hinzugefügt für URL-Parsing
```

---

## 🧪 Erwartetes Verhalten

### Vorher (Fehler)
```
User gibt ein: http://localhost:5678/mcp/2714421f-0865-468b-b938-0d592153a235
Backend verbindet: http://localhost:5678/sse (FALSCH!)
→ Timeout nach 15s
```

### Nachher (Erfolgreich)
```
User gibt ein: http://localhost:5678/mcp/2714421f-0865-468b-b938-0d592153a235
Backend verbindet: http://localhost:5678/mcp/2714421f-0865-468b-b938-0d592153a235 (RICHTIG!)
→ SSE EventStream wird geöffnet
→ Verbindung steht innerhalb von 15s
```

---

## 🔍 Technische Details

### SSE EventStream Spezifikation
- Content-Type: `text/event-stream`
- Unidirektional: Server → Client
- Keep-Alive Connection
- **Wichtig:** URL muss exakt sein, keine Pfad-Anhängsel!

### HttpClientSseClientTransport SDK Verhalten
```java
HttpClientSseClientTransport
    .builder(fullUrl)       // Nimmt die komplette URL
    .sseEndpoint("/")       // Wird intern verarbeitet, überschreibt nicht
    .build();
```

Der SDK erwartet die **komplette URL** als `builder()`-Parameter.  
Der `.sseEndpoint("/")` Parameter wird intern für die SSE-Kommunikation verwendet, modifiziert aber nicht die Verbindungs-URL.

---

## 📝 Weitere Anmerkungen

### Timeout (15 Sekunden)
Das 15-Sekunden-Timeout in `McpConnectionService.java:153` ist **korrekt** für SSE-Handshakes:
- SSE Verbindungen sollten innerhalb von 15s stehen
- Längere Timeouts würden auf Netzwerk- oder Server-Probleme hinweisen
- EventStream muss schnell öffnen, sonst ist etwas falsch konfiguriert

### McpEndpointResolver
Der `McpEndpointResolver` wird jetzt nur noch für `verify()` Methoden verwendet:
- Test-Verbindungen mit Fallback-Strategien
- Endpoint-Discovery für unbekannte Server
- **Nicht mehr** für produktive Session-Verbindungen

---

## ✅ Testing Checklist

- [ ] Backend neu kompilieren: `./gradlew clean build`
- [ ] Backend starten: `./gradlew bootRun`
- [ ] Im Frontend SSE als Transport auswählen
- [ ] n8n MCP URL eingeben: `http://localhost:5678/mcp/{uuid}`
- [ ] Server speichern und verbinden
- [ ] Logs prüfen: `Creating SSE transport for URL: http://localhost:5678/mcp/...`
- [ ] Status sollte CONNECTED werden
- [ ] Tools sollten geladen werden

---

**Implementiert von:** GitHub Copilot  
**Review Status:** Pending Testing
