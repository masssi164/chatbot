# Anforderungsanalyse: MCP Async Architecture Migration

## 1. Geschäftliche Anforderungen

### 1.1 Primäres Problem
**Database Lock Timeouts**: Concurrent Requests zu MCP-Servern führen zu `QueryTimeoutException` durch pessimistic locking.

**Beispiel-Fehler**:
```
org.hibernate.exception.LockTimeoutException: could not obtain transaction-synchronized Session for current thread
```

### 1.2 Geschäftsziele
- ✅ **Verfügbarkeit**: Frontend kann mehrere MCP-Server gleichzeitig verifizieren ohne Timeouts
- ✅ **Performance**: Tools müssen innerhalb 2-3 Sekunden abrufbar sein für Chat-Integration
- ✅ **Skalierbarkeit**: System muss 10+ concurrent Chat-Requests mit MCP Tool-Calls verarbeiten
- ✅ **Datenkonsistenz**: Capabilities (Tools/Resources/Prompts) müssen gecached werden zur Reduktion von API-Calls

---

## 2. Technische Anforderungen

### 2.1 MCP SDK Integration
**Status Quo**:
- Verwendet `McpSyncClient` (blocking)
- Jede Operation (`listTools()`, `callTool()`) blockiert Thread
- SDK Version: `io.modelcontextprotocol.sdk:mcp:0.15.0`

**Ziel**:
- Migration zu `McpAsyncClient` mit native Project Reactor Support
- Alle Operationen returnen `Mono<T>` oder `Flux<T>` (non-blocking)
- Session-Reuse: Einmal initialisierte Clients werden gecached

**MCP SDK API (Ziel-Architektur)**:
```java
// Factory Method
McpAsyncClient client = McpClient.async(transport)
    .clientInfo(new Implementation(name, version))
    .requestTimeout(Duration.ofSeconds(14))
    .initializationTimeout(Duration.ofSeconds(2))
    .build();

// Reactive Operations
Mono<InitializeResult> initResult = client.initialize();
Mono<ListToolsResult> tools = client.listTools();
Mono<CallToolResult> result = client.callTool(request);
Mono<Void> closed = client.closeGracefully();
```

### 2.2 Datenbankschema: `mcp_servers` Tabelle

**Bestehende Felder**:
```java
@Entity
@Table(name = "mcp_servers")
public class McpServer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "server_id", nullable = false, unique = true, length = 64)
    private String serverId;
    
    @Column(nullable = false, length = 255)
    private String name;
    
    @Column(nullable = false, length = 512)
    private String baseUrl;
    
    @Column(length = 1024)
    private String apiKey; // encrypted
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private McpServerStatus status; // IDLE, CONNECTING, CONNECTED, ERROR
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private McpTransport transport; // SSE, STREAMABLE_HTTP
    
    @Column(nullable = false)
    private Instant lastUpdated;
}
```

**Neue Felder (erforderlich)**:
```java
// Optimistic Locking (Pflicht!)
@Version
private Long version;

// Capabilities Cache (JSON)
@Column(columnDefinition = "TEXT")
private String toolsCache; // JSON Array: [{"name": "...", "description": "...", "inputSchema": {...}}]

@Column(columnDefinition = "TEXT")
private String resourcesCache; // JSON Array: [{"uri": "...", "name": "...", ...}]

@Column(columnDefinition = "TEXT")
private String promptsCache; // JSON Array: [{"name": "...", "arguments": [...]}]

// Cache Metadata
@Column(name = "last_synced_at")
private Instant lastSyncedAt; // Wann wurde Cache zuletzt aktualisiert?

@Column(name = "sync_status")
@Enumerated(EnumType.STRING)
private SyncStatus syncStatus; // NEVER_SYNCED, SYNCING, SYNCED, SYNC_FAILED

// Client Metadata (optional, für Debugging)
@Column(columnDefinition = "TEXT")
private String clientMetadata; // JSON: {"protocolVersion": "2024-11-05", "capabilities": {...}}
```

**Migration SQL**:
```sql
-- V3__add_mcp_capabilities_cache.sql
ALTER TABLE mcp_servers ADD COLUMN version BIGINT DEFAULT 0;
ALTER TABLE mcp_servers ADD COLUMN tools_cache TEXT;
ALTER TABLE mcp_servers ADD COLUMN resources_cache TEXT;
ALTER TABLE mcp_servers ADD COLUMN prompts_cache TEXT;
ALTER TABLE mcp_servers ADD COLUMN last_synced_at TIMESTAMP;
ALTER TABLE mcp_servers ADD COLUMN sync_status VARCHAR(20) DEFAULT 'NEVER_SYNCED';
ALTER TABLE mcp_servers ADD COLUMN client_metadata TEXT;
```

### 2.3 Session Management: `McpSessionRegistry`

**Zweck**: Zentrale Verwaltung aller MCP-Client-Verbindungen mit Lifecycle-Management.

**Architektur**:
```java
@Component
public class McpSessionRegistry implements ApplicationListener<ContextClosedEvent> {
    
    private final ConcurrentHashMap<String, SessionHolder> sessions = new ConcurrentHashMap<>();
    
    // SessionHolder Internal Class
    private static class SessionHolder {
        private final McpAsyncClient client;
        private final AtomicReference<SessionState> state;
        private final Instant createdAt;
        private volatile Instant lastAccessedAt;
        
        // State: INITIALIZING → ACTIVE → (ERROR | CLOSED)
    }
    
    /**
     * Holt oder erstellt eine Session für einen MCP-Server.
     * Verwendet computeIfAbsent für Thread-Safety.
     */
    public Mono<McpAsyncClient> getOrCreateSession(String serverId) {
        // Implementierung mit reactive initialization
    }
    
    /**
     * Schließt eine Session und entfernt sie aus dem Cache.
     */
    public Mono<Void> closeSession(String serverId) {
        // Ruft client.closeGracefully() auf
    }
    
    /**
     * ApplicationListener für graceful shutdown aller Sessions.
     */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // Schließt alle sessions mit Flux.fromIterable().flatMap()
    }
}
```

**Anforderungen**:
- ✅ Thread-safe: `ConcurrentHashMap` + atomic operations
- ✅ Lazy Initialization: Client wird erst bei Bedarf erstellt
- ✅ State Tracking: INITIALIZING → ACTIVE → ERROR/CLOSED
- ✅ Graceful Shutdown: Alle Sessions werden beim App-Stop geschlossen
- ✅ Error Recovery: Bei Fehler wird Session aus Cache entfernt und neu erstellt

---

## 3. Service Layer Anforderungen

### 3.1 McpServerService (Haupt-Service)

**Bestehende Probleme**:
```java
@Transactional
public McpConnectionStatusDto verifyConnection(String serverId) {
    // ❌ PESSIMISTIC_WRITE Lock blockiert alle concurrent requests
    McpServer server = repository.findByServerIdWithLock(serverId).orElseThrow();
    // ❌ Blockierende Operationen während Lock gehalten wird
    VerificationResult result = connectionService.verify(server, decryptedApiKey);
    // ❌ 2000ms Timeout → QueryTimeoutException
}
```

**Neue Architektur**:
```java
// Reactive, non-blocking
public Mono<McpConnectionStatusDto> verifyConnectionAsync(String serverId) {
    return Mono.fromSupplier(() -> repository.findByServerId(serverId))
        .flatMap(serverOpt -> serverOpt
            .map(Mono::just)
            .orElseGet(() -> Mono.error(new ResponseStatusException(NOT_FOUND))))
        .flatMap(server -> sessionRegistry.getOrCreateSession(serverId)
            .flatMap(client -> client.listTools()
                .flatMap(tools -> saveCapabilitiesAndReturn(server, tools))))
        .retryWhen(Retry.backoff(3, Duration.ofMillis(50))
            .filter(ex -> ex instanceof OptimisticLockException))
        .timeout(Duration.ofSeconds(10));
}
```

**Methodensignatur-Änderungen**:

| Alt (Blocking) | Neu (Reactive) |
|----------------|----------------|
| `McpConnectionStatusDto verifyConnection(String serverId)` | `Mono<McpConnectionStatusDto> verifyConnectionAsync(String serverId)` |
| `void updateServerStatus(String serverId, McpServerStatus status)` | `Mono<Void> updateServerStatusAsync(String serverId, McpServerStatus status)` |
| `McpServer syncCapabilities(String serverId)` | `Mono<McpServer> syncCapabilitiesAsync(String serverId)` |

**Neue Methoden**:
```java
/**
 * Synchronisiert Tools/Resources/Prompts von MCP-Server und cached in DB.
 * Wird aufgerufen bei: 1) Manueller Trigger (POST /sync), 2) Scheduled Task
 */
public Mono<McpServer> syncCapabilitiesAsync(String serverId) {
    return sessionRegistry.getOrCreateSession(serverId)
        .flatMap(client -> Mono.zip(
            client.listTools(),
            client.listResources(),
            client.listPrompts()
        ))
        .flatMap(tuple -> {
            String toolsJson = objectMapper.writeValueAsString(tuple.getT1().tools());
            String resourcesJson = objectMapper.writeValueAsString(tuple.getT2().resources());
            String promptsJson = objectMapper.writeValueAsString(tuple.getT3().prompts());
            
            return updateServerWithRetry(serverId, server -> {
                server.setToolsCache(toolsJson);
                server.setResourcesCache(resourcesJson);
                server.setPromptsCache(promptsJson);
                server.setLastSyncedAt(Instant.now());
                server.setSyncStatus(SyncStatus.SYNCED);
            });
        });
}

/**
 * Hilfsmethode: Update mit Optimistic Lock Retry.
 */
private Mono<McpServer> updateServerWithRetry(String serverId, 
                                               Consumer<McpServer> updateFn) {
    return Mono.fromSupplier(() -> {
        McpServer server = repository.findByServerId(serverId).orElseThrow();
        updateFn.accept(server);
        return repository.save(server);
    })
    .retryWhen(Retry.backoff(3, Duration.ofMillis(50))
        .filter(ex -> ex instanceof OptimisticLockException));
}
```

### 3.2 McpClientService (Client-Verwaltung)

**Bestehende Architektur**:
```java
private final Map<String, McpSyncClient> activeConnections = new ConcurrentHashMap<>();

public McpSyncClient getOrCreateClient(McpServer server) {
    return activeConnections.computeIfAbsent(server.getServerId(), key -> {
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize(); // ❌ Blockiert Thread!
        return client;
    });
}

public List<McpSchema.Tool> listTools(McpServer server) {
    McpSyncClient client = getOrCreateClient(server);
    return client.listTools().tools(); // ❌ Blockiert Thread!
}
```

**Neue Architektur**:
```java
// Keine eigene Connection-Map mehr → Delegation an McpSessionRegistry
private final McpSessionRegistry sessionRegistry;

public Mono<List<McpSchema.Tool>> listToolsAsync(McpServer server) {
    return sessionRegistry.getOrCreateSession(server.getServerId())
        .flatMap(McpAsyncClient::listTools)
        .map(McpSchema.ListToolsResult::tools);
}

public Mono<List<McpSchema.Resource>> listResourcesAsync(McpServer server) {
    return sessionRegistry.getOrCreateSession(server.getServerId())
        .flatMap(McpAsyncClient::listResources)
        .map(McpSchema.ListResourcesResult::resources);
}

public Mono<CallToolResult> callToolAsync(McpServer server, String toolName, 
                                          Map<String, Object> arguments) {
    return sessionRegistry.getOrCreateSession(server.getServerId())
        .flatMap(client -> {
            CallToolRequest request = new CallToolRequest(toolName, arguments);
            return client.callTool(request);
        });
}
```

**Anforderung**: Alle Caller von `McpClientService` müssen auf Mono-Handling umgestellt werden.

---

## 4. Chat-Integration: Tool-Calls im Conversation Flow

### 4.1 Aktueller Flow (vereinfacht)
```
1. User sendet Nachricht
2. ChatConversationService.generateAssistantMessage()
3. Baut OpenAI Request mit Tools (McpToolContextBuilder.augmentPayload())
4. OpenAI Response API entscheidet: Tool-Call erforderlich?
5. JA → executeToolCall() wird aufgerufen
   - Routing: Welcher MCP-Server hat das Tool?
   - mcpClientService.callTool(server, toolName, arguments) ← BLOCKIERT!
   - Result wird in OpenAI-Format transformiert
   - Zweiter Request mit Tool-Result
6. NEIN → Final Response an User
```

### 4.2 Anforderungen für Tool-Integration

**A) McpToolContextBuilder muss Tools aus DB-Cache laden**:
```java
@Component
public class McpToolContextBuilder {
    
    /**
     * Lädt Tools aus DB-Cache und fügt sie ins OpenAI Request-Payload ein.
     * Format: {"tools": [{"type": "function", "function": {...}}]}
     */
    public void augmentPayload(ObjectNode payload) {
        List<McpServer> connectedServers = serverRepository.findAll().stream()
            .filter(s -> s.getStatus() == McpServerStatus.CONNECTED)
            .filter(s -> s.getToolsCache() != null) // Nur Server mit gecachten Tools
            .toList();
        
        ArrayNode toolsArray = payload.putArray("tools");
        
        for (McpServer server : connectedServers) {
            try {
                List<McpSchema.Tool> tools = objectMapper.readValue(
                    server.getToolsCache(), 
                    new TypeReference<List<McpSchema.Tool>>() {}
                );
                
                for (McpSchema.Tool tool : tools) {
                    ObjectNode toolNode = objectMapper.createObjectNode();
                    toolNode.put("type", "function");
                    
                    ObjectNode functionNode = toolNode.putObject("function");
                    functionNode.put("name", tool.name());
                    functionNode.put("description", tool.description());
                    functionNode.set("parameters", objectMapper.valueToTree(tool.inputSchema()));
                    
                    toolsArray.add(toolNode);
                }
            } catch (JsonProcessingException ex) {
                log.error("Failed to parse tools cache for server {}", server.getServerId(), ex);
            }
        }
        
        log.debug("Added {} tools to OpenAI request", toolsArray.size());
    }
}
```

**Anforderungen**:
- ✅ Nur CONNECTED Server berücksichtigen
- ✅ Nur Server mit gültigem `toolsCache` (nicht null, nicht expired)
- ✅ Cache-Expiry-Check: `lastSyncedAt > Instant.now().minus(cacheTtl)` (z.B. 5 Minuten)
- ✅ Error-Toleranz: Wenn ein Server fehlschlägt, andere trotzdem laden

**B) ChatConversationService Tool-Execution erweitern**:
```java
private String executeToolCall(ToolCall toolCall) {
    // 1. Finde Server mit diesem Tool (aus DB-Cache)
    List<McpServer> candidateServers = findServersWithTool(toolCall.name());
    
    if (candidateServers.isEmpty()) {
        throw new IllegalStateException("Kein Server gefunden für Tool: " + toolCall.name());
    }
    
    // 2. Versuche Tool auf jedem Server (Sequential Fallback)
    for (McpServer server : candidateServers) {
        try {
            CallToolResult result = mcpClientService.callToolAsync(server, 
                toolCall.name(), arguments)
                .block(Duration.ofSeconds(30)); // ← Blocking hier OK (innerhalb Transactional Context)
            
            if (result != null && !Boolean.TRUE.equals(result.isError())) {
                return renderToolResult(result);
            }
        } catch (Exception ex) {
            log.warn("Tool {} failed on server {}: {}", 
                toolCall.name(), server.getServerId(), ex.getMessage());
        }
    }
    
    throw new IllegalStateException("Tool execution failed on all servers");
}

/**
 * Findet alle Server die ein bestimmtes Tool haben (aus DB-Cache).
 */
private List<McpServer> findServersWithTool(String toolName) {
    return serverRepository.findAll().stream()
        .filter(s -> s.getStatus() == McpServerStatus.CONNECTED)
        .filter(s -> s.getToolsCache() != null)
        .filter(s -> {
            try {
                List<McpSchema.Tool> tools = objectMapper.readValue(
                    s.getToolsCache(), 
                    new TypeReference<List<McpSchema.Tool>>() {}
                );
                return tools.stream().anyMatch(t -> t.name().equals(toolName));
            } catch (JsonProcessingException ex) {
                return false;
            }
        })
        .toList();
}
```

**Anforderungen**:
- ✅ Tool-Routing aus Cache (nicht live-fetch)
- ✅ Fallback-Strategie: Wenn Tool auf Server A fehlschlägt, versuche Server B
- ✅ Timeout pro Tool-Call: 30 Sekunden
- ✅ Error-Tracking: `ToolCallInfo` speichert welcher Server erfolgreich war

---

## 5. REST-API für Frontend

### 5.1 GET `/api/mcp/servers/{serverId}/capabilities`

**Zweck**: Frontend lädt Capabilities (Tools/Resources/Prompts) eines Servers.

**Anforderungen**:
```java
@GetMapping("/servers/{serverId}/capabilities")
public Mono<ResponseEntity<McpCapabilitiesResponse>> getCapabilities(
        @PathVariable String serverId) {
    return serverService.getServerByIdAsync(serverId)
        .flatMap(server -> {
            // Prüfe Cache-Validität
            if (isCacheValid(server)) {
                // Aus DB-Cache lesen
                return Mono.just(buildResponseFromCache(server));
            } else {
                // Fallback: Live-Fetch + Cache-Update
                return serverService.syncCapabilitiesAsync(serverId)
                    .map(this::buildResponseFromCache);
            }
        })
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
}

private boolean isCacheValid(McpServer server) {
    if (server.getLastSyncedAt() == null) return false;
    Duration age = Duration.between(server.getLastSyncedAt(), Instant.now());
    return age.compareTo(cacheTtl) < 0; // z.B. 5 Minuten
}

private McpCapabilitiesResponse buildResponseFromCache(McpServer server) {
    List<ToolInfo> tools = parseCache(server.getToolsCache(), ToolInfo.class);
    List<ResourceInfo> resources = parseCache(server.getResourcesCache(), ResourceInfo.class);
    List<PromptInfo> prompts = parseCache(server.getPromptsCache(), PromptInfo.class);
    
    return McpCapabilitiesResponse.builder()
        .tools(tools)
        .resources(resources)
        .prompts(prompts)
        .serverInfo(ServerInfo.builder()
            .name(server.getName())
            .version("1.0")
            .build())
        .build();
}
```

**Response Format**:
```json
{
  "tools": [
    {
      "name": "get_weather",
      "description": "Fetches weather data",
      "inputSchema": {
        "type": "object",
        "properties": {
          "location": {"type": "string"}
        },
        "required": ["location"]
      }
    }
  ],
  "resources": [...],
  "prompts": [...],
  "serverInfo": {
    "name": "n8n MCP Server",
    "version": "1.0"
  }
}
```

### 5.2 POST `/api/mcp/servers/{serverId}/sync`

**Zweck**: Frontend kann manuell Cache-Refresh triggern.

```java
@PostMapping("/servers/{serverId}/sync")
public Mono<ResponseEntity<SyncStatusResponse>> syncCapabilities(
        @PathVariable String serverId) {
    return serverService.syncCapabilitiesAsync(serverId)
        .map(server -> ResponseEntity.ok(new SyncStatusResponse(
            server.getServerId(),
            server.getSyncStatus(),
            server.getLastSyncedAt(),
            "Sync completed successfully"
        )))
        .onErrorResume(ex -> Mono.just(ResponseEntity.status(500)
            .body(new SyncStatusResponse(serverId, SyncStatus.SYNC_FAILED, 
                null, ex.getMessage()))));
}

public record SyncStatusResponse(
    String serverId,
    SyncStatus status,
    Instant syncedAt,
    String message
) {}
```

**Response Format**:
```json
{
  "serverId": "n8n-server-1",
  "status": "SYNCED",
  "syncedAt": "2025-11-02T14:30:00Z",
  "message": "Sync completed successfully"
}
```

---

## 6. Background Jobs

### 6.1 Scheduled Capabilities Sync

**Anforderung**: Periodisches Update aller Server-Capabilities ohne Frontend-Interaktion.

```java
@Component
public class McpCapabilitiesSyncScheduler {
    
    private final McpServerRepository repository;
    private final McpServerService serverService;
    
    /**
     * Synchronisiert alle CONNECTED Server alle 5 Minuten.
     */
    @Scheduled(fixedDelay = 300_000) // 5 Minuten
    public void syncAllConnectedServers() {
        List<McpServer> connectedServers = repository.findAll().stream()
            .filter(s -> s.getStatus() == McpServerStatus.CONNECTED)
            .toList();
        
        log.info("Starting scheduled capabilities sync for {} servers", 
            connectedServers.size());
        
        Flux.fromIterable(connectedServers)
            .flatMap(server -> serverService.syncCapabilitiesAsync(server.getServerId())
                .doOnSuccess(s -> log.info("Synced server {}", s.getServerId()))
                .onErrorResume(ex -> {
                    log.error("Failed to sync server {}: {}", 
                        server.getServerId(), ex.getMessage());
                    return serverService.updateServerStatusAsync(
                        server.getServerId(), McpServerStatus.ERROR);
                })
            )
            .blockLast(Duration.ofMinutes(2)); // Timeout für gesamten Batch
    }
}
```

**Anforderungen**:
- ✅ Nur CONNECTED Server syncen
- ✅ Fehlertoleranz: Ein fehlgeschlagener Server stoppt nicht die anderen
- ✅ Bei Fehler: Server-Status auf ERROR setzen
- ✅ Logging: Success/Failure pro Server
- ✅ Timeout: Max. 2 Minuten für gesamten Batch

---

## 7. Testing-Anforderungen

### 7.1 Unit Tests

**McpSessionRegistryTest**:
```java
@Test
void shouldCacheClientForSameServerId() {
    McpAsyncClient client1 = registry.getOrCreateSession("server-1").block();
    McpAsyncClient client2 = registry.getOrCreateSession("server-1").block();
    assertSame(client1, client2); // Muss gleiche Instanz sein
}

@Test
void shouldHandleInitializationTimeout() {
    // Mock McpAsyncClient.initialize() mit Timeout
    StepVerifier.create(registry.getOrCreateSession("slow-server"))
        .expectError(TimeoutException.class)
        .verify();
}
```

**McpServerServiceTest**:
```java
@Test
void shouldRetryOnOptimisticLockException() {
    // Mock repository.save() wirft OptimisticLockException beim ersten Versuch
    when(repository.save(any())).thenThrow(OptimisticLockException.class)
                                 .thenReturn(server);
    
    StepVerifier.create(service.syncCapabilitiesAsync("server-1"))
        .expectNextMatches(s -> s.getSyncStatus() == SyncStatus.SYNCED)
        .verifyComplete();
    
    verify(repository, times(2)).save(any()); // Retry erfolgt
}
```

### 7.2 Integration Test: E2E Chat mit Tool-Calls

**Szenario**:
1. User sendet Nachricht: "What's the weather in Berlin?"
2. LLM fordert Tool `get_weather` an
3. Tool wird auf n8n Server ausgeführt
4. Result: "20°C, sunny"
5. LLM formuliert Antwort: "The weather in Berlin is 20°C and sunny"

**Test-Setup**:
```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ChatWithMcpToolsIntegrationTest {
    
    @Autowired MockMvc mockMvc;
    @Autowired McpServerRepository serverRepository;
    @MockBean OpenAiProxyService proxyService; // Mock OpenAI
    
    @Test
    void shouldExecuteToolAndReturnFinalResponse() throws Exception {
        // 1. Setup: n8n Server mit get_weather Tool
        McpServer server = createConnectedServer("n8n-server");
        server.setToolsCache("""
            [{"name": "get_weather", "description": "...", "inputSchema": {...}}]
        """);
        serverRepository.save(server);
        
        // 2. Mock OpenAI responses
        when(proxyService.createResponse(any(), any()))
            .thenReturn(mockToolCallResponse("get_weather", "Berlin"))
            .thenReturn(mockFinalResponse("The weather in Berlin is 20°C"));
        
        // 3. Send chat message
        mockMvc.perform(post("/api/chats/{chatId}/completions", chatId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "message": {"role": "user", "content": "Weather in Berlin?"},
                      "model": "gpt-4"
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("The weather in Berlin is 20°C"))
            .andExpect(jsonPath("$.toolCalls[0].name").value("get_weather"))
            .andExpect(jsonPath("$.toolCalls[0].success").value(true));
    }
}
```

---

## 8. Akzeptanzkriterien (Definition of Done)

### 8.1 Funktionale Kriterien
- ✅ **K1**: Frontend kann 5+ MCP-Server gleichzeitig verifizieren ohne Lock-Timeouts
- ✅ **K2**: Tools werden aus DB-Cache geladen (< 50ms Response-Zeit)
- ✅ **K3**: Scheduled Task synchronisiert Capabilities alle 5 Minuten
- ✅ **K4**: Chat-Flow mit Tool-Calls funktioniert End-to-End (mit echtem n8n oder Mock)
- ✅ **K5**: Bei Tool-Fehler auf Server A wird Server B als Fallback versucht
- ✅ **K6**: Optimistic Lock Conflicts werden automatisch mit 3 Retries behandelt
- ✅ **K7**: Alle MCP-Sessions werden beim Shutdown gracefully geschlossen

### 8.2 Nicht-Funktionale Kriterien
- ✅ **K8**: Alle bestehenden Tests bleiben grün (Regression Testing)
- ✅ **K9**: Code Coverage für neue Services: > 80%
- ✅ **K10**: Logging: DEBUG-Level für alle MCP-Operations mit Context (serverId, toolName, etc.)
- ✅ **K11**: Error-Handling: Alle Mono-Operations haben `.onErrorResume()` Fallback
- ✅ **K12**: Timeout-Konfiguration: Alle blocking Operations haben expliziten Timeout

### 8.3 Dokumentation
- ✅ **K13**: API-Dokumentation aktualisiert (OpenAPI/Swagger)
- ✅ **K14**: README.md enthält MCP-Setup-Anleitung
- ✅ **K15**: Architecture Decision Record (ADR) für Async Migration

---

## 9. Migration-Strategie (Reihenfolge)

### Phase 1: Foundation (Tasks 1-3)
1. ✅ Datenbank-Schema erweitern (McpServer Entity + Migration)
2. ✅ McpSessionRegistry implementieren mit McpAsyncClient
3. ✅ Unit Tests für McpSessionRegistry

### Phase 2: Service Layer (Tasks 4-6)
4. ✅ McpServerService auf Reactive Pattern migrieren
5. ✅ Tool/Resource/Prompt-Caching in Datenbank
6. ✅ McpClientService refactoring zu Async Client

### Phase 3: Integration (Tasks 7-8)
7. ✅ McpToolContextBuilder erweitern für Chat-Integration
8. ✅ ChatConversationService Tool-Execution anpassen

### Phase 4: API & Jobs (Tasks 9-11)
9. ✅ REST-API für Frontend: GET /capabilities, POST /sync
10. ✅ Background-Job für periodisches Capabilities-Sync
11. ✅ Error-Handling und Monitoring

### Phase 5: Testing & Rollout (Tasks 12-13)
12. ✅ Integration Tests: E2E Chat mit MCP Tool-Calls
13. ✅ Performance-Testing: Load Test mit 20 concurrent requests
14. ✅ Production Deployment

---

## 10. Risiken & Mitigationen

| Risiko | Wahrscheinlichkeit | Impact | Mitigation |
|--------|-------------------|--------|------------|
| **R1**: Optimistic Lock führt zu häufigen Conflicts | Mittel | Mittel | Retry-Mechanismus mit exponential backoff, TTL für Capabilities-Cache erhöhen |
| **R2**: McpAsyncClient blockiert bei falscher Verwendung | Niedrig | Hoch | Code-Review, StepVerifier Tests, Dokumentation |
| **R3**: Cache-Expiry führt zu unerwarteten Live-Fetches | Mittel | Niedrig | TTL konfigurierbar, Logging für Cache-Misses, Fallback-Strategie |
| **R4**: Migration bricht bestehende Chat-Funktionalität | Niedrig | Hoch | Feature-Flagging, Canary Deployment, umfassende Integration Tests |
| **R5**: Scheduled Task überlastet MCP-Server | Mittel | Mittel | Rate-Limiting, konfigurierbare Sync-Frequenz, Retry mit Backoff |

---

## 11. Offene Fragen

1. **Cache-TTL**: Wie lange sollen Capabilities gecached werden? (Vorschlag: 5 Minuten)
2. **Retry-Strategie**: 3 Retries mit 50ms/100ms/150ms Backoff ausreichend?
3. **WebFlux Migration**: Controller auch auf Mono<ResponseEntity> umstellen oder blocking erlauben?
4. **Error-Notification**: Soll Frontend bei fehlgeschlagener Sync benachrichtigt werden?
5. **Connection Pooling**: Soll McpAsyncClient pro Server oder global sein?

---

## 12. Metrics & Monitoring (Optional)

**Micrometer Metrics** (für Produktion):
```java
// Beispiel: MCP-Operation Timing
@Timed(value = "mcp.tool.call", description = "Time to execute MCP tool call")
public Mono<CallToolResult> callToolAsync(McpServer server, String toolName, 
                                          Map<String, Object> arguments) {
    // ...
}

// Cache-Hit-Rate
meterRegistry.counter("mcp.cache.hit", "server", serverId).increment();
meterRegistry.counter("mcp.cache.miss", "server", serverId).increment();
```

**Empfohlene Dashboards**:
- MCP Tool Call Latency (P50, P95, P99)
- Cache Hit/Miss Rate per Server
- Optimistic Lock Retry Count
- Session Lifecycle (Created, Active, Closed, Errors)
