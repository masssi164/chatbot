# MCP Package - Model Context Protocol Integration

## Overview

This package implements the **Model Context Protocol (MCP)** integration, enabling the chatbot to discover and execute tools from external servers dynamically.

**What is MCP?**
- Open protocol for AI applications to interact with external tools
- Similar to OpenAI function calling, but standardized and extensible
- Supports tools, resources (files/data), and prompts (templates)
- Transport-agnostic (SSE, HTTP, stdio, etc.)

**Use Cases**:
- Execute n8n workflows as tools
- Access file systems, databases via MCP servers
- Integrate with APIs through MCP adapters
- Reuse prompt templates from external sources

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Chat Application                         │
├─────────────────────────────────────────────────────────────┤
│  McpToolContextBuilder  │  ResponseStreamService            │
│  (loads tools)          │  (executes tools)                 │
└─────────────┬───────────┴─────────────┬─────────────────────┘
              │                         │
              ▼                         ▼
    ┌─────────────────────────────────────────────┐
    │         McpServerService                    │
    │  (CRUD + Connection Management)             │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
    ┌─────────────────────────────────────────────┐
    │         McpClientService                    │
    │  (High-level MCP Operations)                │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
    ┌─────────────────────────────────────────────┐
    │       McpSessionRegistry                    │
    │  (Client Lifecycle Management)              │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
    ┌─────────────────────────────────────────────┐
    │         McpAsyncClient (SDK)                │
    │  (MCP Protocol Implementation)              │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
         ┌────────────────────┐
         │   MCP Server       │
         │  (e.g., n8n, etc.) │
         └────────────────────┘
```

---

## Key Components

### 1. McpSessionRegistry ⭐

**File**: `McpSessionRegistry.java`

**Purpose**: Manages lifecycle of MCP client connections with:
- Lazy initialization
- Session caching (reuse clients)
- State tracking (INITIALIZING → ACTIVE → ERROR/CLOSED)
- Idle timeout (30 min)
- Graceful shutdown

**Thread Safety**: Uses `ConcurrentHashMap` + atomic operations

**API**:
```java
// Get or create session (thread-safe)
Mono<McpAsyncClient> getOrCreateSession(String serverId);

// Close specific session
Mono<Void> closeSession(String serverId);

// Close all sessions (on app shutdown)
void closeAllSessions();
```

**Internal State**:
```java
private static class SessionHolder {
    final String serverId;
    final AtomicReference<SessionState> state;
    McpAsyncClient client;
    Instant createdAt;
    volatile Instant lastAccessedAt;
}

enum SessionState {
    INITIALIZING,  // Client creation in progress
    ACTIVE,        // Client ready
    ERROR,         // Init failed
    CLOSED         // Session closed
}
```

**Initialization Flow**:
```
1. Check if session exists in cache
2. If not: Create SessionHolder with INITIALIZING state
3. Load server config from database
4. Decrypt API key
5. Create MCP transport (SSE or HTTP)
6. Initialize McpAsyncClient
7. Call client.initialize() (MCP protocol handshake)
8. On success: Set state to ACTIVE, cache client
9. On error: Set state to ERROR, remove from cache
```

**Idle Cleanup** (scheduled every 5 min):
```java
@Scheduled(fixedDelay = 300_000)
void cleanupIdleSessions() {
    Instant threshold = Instant.now().minus(IDLE_TIMEOUT);
    sessions.values().stream()
        .filter(h -> h.lastAccessedAt.isBefore(threshold))
        .forEach(h -> closeSession(h.serverId).subscribe());
}
```

**Potential Issue** ⚠️:
- **Race condition**: If two threads call `getOrCreateSession()` simultaneously, both might see `INITIALIZING` state and wait indefinitely if init fails.
- **Fix**: Use `Mono.cache()` for initialization:
  ```java
  Mono<McpAsyncClient> initMono = initializeClient(serverId).cache();
  holder.initializationMono = initMono;
  return initMono.flatMap(client -> {
    holder.client = client;
    holder.state.set(ACTIVE);
    return Mono.just(client);
  });
  ```

---

### 2. McpClientService

**File**: `McpClientService.java`

**Purpose**: High-level MCP operations (tools, resources, prompts).

**API**:
```java
// Discover available tools
Mono<List<McpSchema.Tool>> listToolsAsync(String serverId);

// Discover available resources
Mono<List<McpSchema.Resource>> listResourcesAsync(String serverId);

// Discover available prompts
Mono<List<McpSchema.Prompt>> listPromptsAsync(String serverId);

// Execute a tool
Mono<McpSchema.CallToolResult> callToolAsync(
    String serverId, 
    String toolName, 
    Map<String, Object> arguments
);

// Get server capabilities
Mono<McpSchema.ServerCapabilities> getServerCapabilities(String serverId);
```

**Error Handling**:
- **Timeout**: 15 seconds per operation
- **Missing capability**: If server doesn't support feature, return empty list (not error)
- **Connection error**: Propagate as `McpClientException`

**Example Usage**:
```java
// List tools
mcpClientService.listToolsAsync("n8n-server-1")
    .subscribe(tools -> {
        tools.forEach(tool -> 
            log.info("Tool: {} - {}", tool.name(), tool.description())
        );
    });

// Execute tool
Map<String, Object> args = Map.of("location", "Berlin");
mcpClientService.callToolAsync("n8n-server-1", "get_weather", args)
    .subscribe(result -> {
        if (result.isError()) {
            log.error("Tool failed: {}", result.content());
        } else {
            log.info("Tool result: {}", result.content());
        }
    });
```

---

### 3. McpServerService

**File**: `McpServerService.java`

**Purpose**: CRUD operations for MCP servers + connection management.

**API**:
```java
// List all servers
Flux<McpServerDto> listServers();

// Get server by ID
Mono<McpServerDto> getServer(String serverId);

// Create or update server
Mono<McpServerDto> createOrUpdate(McpServerRequest request);

// Update existing server
Mono<McpServerDto> update(String serverId, McpServerRequest request);

// Delete server
Mono<Void> deleteServer(String serverId);

// Test connection
Mono<McpConnectionStatusDto> verifyConnection(String serverId);

// Refresh capabilities cache
Mono<McpServer> syncCapabilities(String serverId);

// Update server status
Mono<McpServer> updateServerStatus(String serverId, McpServerStatus status);
```

**Capabilities Caching**:

**Why cache?**
- Avoid repeated API calls to MCP servers
- Reduce latency (DB cache is faster than network)
- Tools can be loaded synchronously during OpenAI request building

**Flow**:
```
1. On server creation/connection: Fetch capabilities
2. Store as JSON in database:
   - tools_cache: [{"name": "...", "description": "...", "inputSchema": {...}}]
   - resources_cache: [{"uri": "...", "name": "..."}]
   - prompts_cache: [{"name": "...", "arguments": [...]}]
3. Set last_synced_at timestamp
4. Background job refreshes every 5 minutes
5. Manual refresh via POST /api/mcp/servers/{id}/sync
```

**Optimistic Locking**:
- Uses `@Version` field to handle concurrent updates
- Automatic retry on `OptimisticLockException` (3 attempts)

**Example**:
```java
// Sync capabilities
mcpServerService.syncCapabilities("n8n-server-1")
    .subscribe(server -> {
        log.info("Synced {} tools, {} resources, {} prompts",
            server.getToolsCache() != null ? "cached" : "0",
            server.getResourcesCache() != null ? "cached" : "0",
            server.getPromptsCache() != null ? "cached" : "0"
        );
    });
```

---

### 4. McpToolContextBuilder

**File**: `McpToolContextBuilder.java`

**Purpose**: Injects MCP tools into OpenAI request payload.

**When called**: Before sending request to OpenAI API in `ResponseStreamService`

**Logic**:
```java
public void augmentPayload(ObjectNode payload) {
    // 1. Load all CONNECTED servers
    List<McpServer> servers = serverRepository.findAll()
        .filter(s -> s.getStatus() == CONNECTED)
        .filter(s -> s.getToolsCache() != null)
        .collectList()
        .block();
    
    // 2. Parse tools from cache
    ArrayNode toolsArray = payload.putArray("tools");
    for (McpServer server : servers) {
        List<McpSchema.Tool> tools = parseToolsCache(server.getToolsCache());
        
        // 3. Transform to OpenAI format
        for (McpSchema.Tool tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("type", "function");
            
            ObjectNode functionNode = toolNode.putObject("function");
            functionNode.put("name", tool.name());
            functionNode.put("description", tool.description());
            functionNode.set("parameters", objectMapper.valueToTree(tool.inputSchema()));
            
            toolsArray.add(toolNode);
        }
    }
    
    log.debug("Added {} tools to OpenAI request", toolsArray.size());
}
```

**OpenAI Function Format**:
```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "Get weather for a location",
        "parameters": {
          "type": "object",
          "properties": {
            "location": {"type": "string"}
          },
          "required": ["location"]
        }
      }
    }
  ]
}
```

**Performance**: Loads from DB cache (fast), not from MCP servers (slow)

---

### 5. Tool Approval System

**Files**:
- `ToolApprovalPolicy.java` (entity)
- `ToolApprovalPolicyService.java` (service)
- `ToolApprovalPolicyController.java` (REST API)
- `ApprovalPolicy.java` (enum)

**Purpose**: Security mechanism to control tool execution.

**Approval Policies**:
```java
enum ApprovalPolicy {
    ALWAYS_ALLOW,  // Auto-approve (for trusted tools)
    ALWAYS_DENY,   // Block (for dangerous tools)
    ASK_USER       // Prompt user (default)
}
```

**Database Schema**:
```sql
CREATE TABLE tool_approval_policies (
    id BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    policy VARCHAR(20) NOT NULL,
    UNIQUE (server_id, tool_name)
);
```

**API**:
```java
// Check if approval required
boolean requiresApproval(String serverId, String toolName);

// Get policy (default: ASK_USER)
ApprovalPolicy getPolicy(String serverId, String toolName);

// Set policy
void setPolicy(String serverId, String toolName, ApprovalPolicy policy);

// Remove policy (revert to default)
void removePolicy(String serverId, String toolName);
```

**Integration with Streaming**:

In `ResponseStreamService`:
```java
// When OpenAI requests tool call
if (toolApprovalService.requiresApproval(serverId, toolName)) {
    // Emit approval_required event
    String approvalRequestId = UUID.randomUUID().toString();
    pendingApprovals.put(approvalRequestId, toolCallInfo);
    
    ServerSentEvent<String> approvalEvent = ServerSentEvent.builder()
        .event("approval_required")
        .data(serializeApprovalRequest(approvalRequestId, serverId, toolName, args))
        .build();
    
    // Wait for user response
    return Flux.just(approvalEvent)
        .concatWith(waitForApproval(approvalRequestId));
} else {
    // Auto-approve, execute immediately
    return executeToolAsync(serverId, toolName, args);
}
```

**Frontend Flow**:
1. Receives `approval_required` SSE event
2. Shows modal with tool details
3. User clicks Approve/Deny
4. Sends `POST /api/responses/approval/{id}` with decision
5. Backend resumes tool execution

---

### 6. MCP Capabilities Scheduler

**File**: `McpCapabilitiesScheduler.java`

**Purpose**: Background job to refresh capabilities cache periodically.

**Schedule**: Every 5 minutes (configurable via `@Scheduled`)

**Logic**:
```java
@Scheduled(fixedDelay = 300_000) // 5 minutes
public void syncAllConnectedServers() {
    List<McpServer> servers = serverRepository.findAll()
        .filter(s -> s.getStatus() == CONNECTED)
        .collectList()
        .block();
    
    log.info("Starting scheduled sync for {} servers", servers.size());
    
    Flux.fromIterable(servers)
        .flatMap(server -> serverService.syncCapabilities(server.getServerId())
            .doOnSuccess(s -> log.info("Synced {}", s.getServerId()))
            .onErrorResume(ex -> {
                log.error("Sync failed for {}: {}", server.getServerId(), ex.getMessage());
                return serverService.updateServerStatus(server.getServerId(), McpServerStatus.ERROR);
            })
        )
        .blockLast(Duration.ofMinutes(2)); // Timeout for entire batch
}
```

**Error Handling**:
- One server failure doesn't stop others
- Failed servers marked as ERROR status
- Logs all errors for debugging

---

## REST API

### MCP Server Management

#### List Servers
```http
GET /api/mcp/servers
Response: 200 OK
[
  {
    "serverId": "n8n-server-1",
    "name": "n8n Production",
    "baseUrl": "http://n8n:5678/api/mcp/sse",
    "transport": "SSE",
    "status": "CONNECTED",
    "syncStatus": "SYNCED",
    "lastSyncedAt": "2025-11-06T10:30:00Z",
    "lastUpdated": "2025-11-06T10:30:00Z"
  }
]
```

#### Create/Update Server
```http
POST /api/mcp/servers
Content-Type: application/json
{
  "serverId": "n8n-server-1",
  "name": "n8n Production",
  "baseUrl": "http://n8n:5678/api/mcp/sse",
  "apiKey": "secret-key",
  "transport": "SSE"
}

Response: 200 OK
{
  "serverId": "n8n-server-1",
  "name": "n8n Production",
  "status": "IDLE",
  ...
}
```

#### Verify Connection
```http
POST /api/mcp/servers/{serverId}/verify
Response: 200 OK
{
  "serverId": "n8n-server-1",
  "status": "CONNECTED",
  "serverVersion": "1.0.0",
  "capabilities": {
    "tools": true,
    "resources": true,
    "prompts": false
  }
}
```

#### Sync Capabilities
```http
POST /api/mcp/servers/{serverId}/sync
Response: 200 OK
{
  "serverId": "n8n-server-1",
  "syncStatus": "SYNCED",
  "lastSyncedAt": "2025-11-06T10:35:00Z",
  "toolCount": 5,
  "resourceCount": 2,
  "promptCount": 0
}
```

#### Get Capabilities
```http
GET /api/mcp/servers/{serverId}/capabilities
Response: 200 OK
{
  "tools": [
    {
      "name": "send_email",
      "description": "Send email via SMTP",
      "inputSchema": {
        "type": "object",
        "properties": {
          "to": {"type": "string"},
          "subject": {"type": "string"},
          "body": {"type": "string"}
        },
        "required": ["to", "subject", "body"]
      }
    }
  ],
  "resources": [
    {
      "uri": "file:///data/config.json",
      "name": "Configuration",
      "mimeType": "application/json"
    }
  ],
  "prompts": [],
  "serverInfo": {
    "name": "n8n MCP Server",
    "version": "1.0.0"
  }
}
```

### Tool Execution

#### Execute Tool
```http
POST /api/mcp/tools/execute
Content-Type: application/json
{
  "serverId": "n8n-server-1",
  "toolName": "send_email",
  "arguments": {
    "to": "user@example.com",
    "subject": "Test",
    "body": "Hello!"
  }
}

Response: 200 OK
{
  "content": [
    {
      "type": "text",
      "text": "Email sent successfully"
    }
  ],
  "isError": false
}
```

### Approval Policies

#### List Policies
```http
GET /api/mcp/approval-policies
Response: 200 OK
[
  {
    "serverId": "n8n-server-1",
    "toolName": "send_email",
    "policy": "ASK_USER"
  },
  {
    "serverId": "n8n-server-1",
    "toolName": "read_file",
    "policy": "ALWAYS_ALLOW"
  }
]
```

#### Set Policy
```http
PUT /api/mcp/approval-policies
Content-Type: application/json
{
  "serverId": "n8n-server-1",
  "toolName": "delete_database",
  "policy": "ALWAYS_DENY"
}

Response: 200 OK
```

---

## Database Schema

### mcp_servers
```sql
CREATE TABLE mcp_servers (
    id BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    api_key VARCHAR(1024),              -- Encrypted
    transport VARCHAR(20) NOT NULL,     -- SSE, STREAMABLE_HTTP
    status VARCHAR(20) NOT NULL,        -- IDLE, CONNECTING, CONNECTED, ERROR
    version BIGINT DEFAULT 0,           -- Optimistic locking
    tools_cache TEXT,                   -- JSON array
    resources_cache TEXT,               -- JSON array
    prompts_cache TEXT,                 -- JSON array
    last_synced_at TIMESTAMP,
    sync_status VARCHAR(20) DEFAULT 'NEVER_SYNCED',
    client_metadata TEXT,               -- JSON (debugging info)
    last_updated TIMESTAMP NOT NULL
);

CREATE INDEX idx_mcp_servers_status ON mcp_servers(status);
CREATE INDEX idx_mcp_servers_sync_status ON mcp_servers(sync_status);
```

### tool_approval_policies
```sql
CREATE TABLE tool_approval_policies (
    id BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    policy VARCHAR(20) NOT NULL,        -- ALWAYS_ALLOW, ALWAYS_DENY, ASK_USER
    UNIQUE (server_id, tool_name)
);

CREATE INDEX idx_approval_server_tool ON tool_approval_policies(server_id, tool_name);
```

---

## Configuration

### Application Properties

```properties
# MCP Configuration
mcp.initialization-timeout=10s
mcp.operation-timeout=15s
mcp.idle-timeout=30m
mcp.scheduler.sync-interval=5m
mcp.cache.ttl=5m

# Encryption
encryption.master-password=${ENCRYPTION_MASTER_PASSWORD}
```

### MCP Properties Bean

```java
@ConfigurationProperties("mcp")
@Data
public class McpProperties {
    private Duration initializationTimeout = Duration.ofSeconds(10);
    private Duration operationTimeout = Duration.ofSeconds(15);
    private Duration idleTimeout = Duration.ofMinutes(30);
    
    private SchedulerConfig scheduler = new SchedulerConfig();
    private CacheConfig cache = new CacheConfig();
    
    @Data
    public static class SchedulerConfig {
        private Duration syncInterval = Duration.ofMinutes(5);
    }
    
    @Data
    public static class CacheConfig {
        private Duration ttl = Duration.ofMinutes(5);
    }
}
```

---

## Error Handling

### Exception Hierarchy

```
RuntimeException
└── McpClientException
    ├── McpConnectionException      (connection failed)
    ├── McpTimeoutException         (operation timeout)
    ├── McpProtocolException        (protocol error)
    └── McpAuthenticationException  (auth failed)
```

### Error Responses

```json
{
  "error": "MCP_CONNECTION_FAILED",
  "message": "Failed to connect to MCP server: Connection timeout",
  "serverId": "n8n-server-1",
  "timestamp": "2025-11-06T10:40:00Z"
}
```

---

## Testing

### Unit Tests

**McpSessionRegistryTest**:
```java
@Test
void shouldCacheClientForSameServerId() {
    McpAsyncClient client1 = registry.getOrCreateSession("server-1").block();
    McpAsyncClient client2 = registry.getOrCreateSession("server-1").block();
    assertSame(client1, client2);
}

@Test
void shouldHandleInitializationTimeout() {
    // Mock slow server
    StepVerifier.create(registry.getOrCreateSession("slow-server"))
        .expectError(TimeoutException.class)
        .verify();
}

@Test
void shouldCloseIdleSessions() {
    registry.getOrCreateSession("server-1").block();
    // Fast-forward time
    registry.cleanupIdleSessions();
    // Verify session closed
}
```

**McpClientServiceTest**:
```java
@Test
void shouldListToolsAsync() {
    when(sessionRegistry.getOrCreateSession("server-1"))
        .thenReturn(Mono.just(mockClient));
    when(mockClient.listTools())
        .thenReturn(Mono.just(new ListToolsResult(List.of(mockTool))));
    
    StepVerifier.create(clientService.listToolsAsync("server-1"))
        .expectNextMatches(tools -> tools.size() == 1)
        .verifyComplete();
}
```

### Integration Tests

**McpServerControllerTest**:
```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpServerControllerTest {
    
    @Autowired WebTestClient webClient;
    
    @Test
    void shouldCreateServer() {
        McpServerRequest request = new McpServerRequest(
            null, "Test Server", "http://localhost:9999", 
            "api-key", McpTransport.SSE
        );
        
        webClient.post().uri("/api/mcp/servers")
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk()
            .expectBody(McpServerDto.class)
            .value(dto -> assertEquals("Test Server", dto.name()));
    }
}
```

---

## Known Issues & Improvements

### Issues

1. **Race Condition in Session Init** ⚠️
   - **Problem**: Multiple threads might wait forever if init fails
   - **Fix**: Use `Mono.cache()` for initialization

2. **Memory Leak in Failed Sessions** ⚠️
   - **Problem**: Failed sessions remain in map
   - **Fix**: Add health check in cleanup scheduler

3. **No Circuit Breaker** ⚠️
   - **Problem**: Repeated failures can cascade
   - **Fix**: Add Resilience4j `@CircuitBreaker`

### Improvements

1. **Add Caching Layer**
   - Use Spring Cache for in-memory capabilities
   - Reduce database load

2. **Add Metrics**
   - Track tool execution latency
   - Monitor session lifecycle
   - Alert on high error rates

3. **Add Health Checks**
   - Periodic ping to MCP servers
   - Auto-reconnect on disconnect

---

## Contributing

When modifying MCP integration:
1. Update capabilities cache schema if changing fields
2. Add database migration
3. Update DTOs in `dto/` package
4. Add unit tests for new services
5. Update this documentation

---

For related documentation:
- [Parent: Backend AGENTS.md](../AGENTS.md)
- [Root: Project AGENTS.md](../../AGENTS.md)
- [MCP Protocol Spec](https://spec.modelcontextprotocol.io/)
