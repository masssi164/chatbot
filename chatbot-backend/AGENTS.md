# Backend Architecture - Spring Boot Reactive

## Overview

This backend is a **fully reactive Spring Boot application** that handles:
- Chat conversations with streaming responses
- MCP (Model Context Protocol) integration for dynamic tool execution
- OpenAI API integration via LiteLLM proxy
- Tool approval workflow
- Database operations with R2DBC (reactive JDBC)

**Key Technologies**:
- Spring Boot 3.4.0 + WebFlux
- Java 17
- R2DBC (Reactive Relational Database Connectivity)
- PostgreSQL 16 / H2 (dev)
- MCP Java SDK 0.15.0
- Project Reactor

---

## Package Structure

```
app.chatbot/
├── ChatbotBackendApplication.java  # Main application entry point
├── config/                         # Spring configuration beans
├── conversation/                   # Chat conversation domain
├── mcp/                            # Model Context Protocol integration
├── responses/                      # OpenAI Responses API (SSE streaming)
├── security/                       # Encryption utilities
├── connection/                     # Connection verification utilities
├── openai/                         # OpenAI-specific DTOs
└── utils/                          # Generic utilities
```

---

## Package: `conversation/`

**Purpose**: Manages chat conversations, messages, and tool execution tracking.

### Core Classes

#### `Conversation.java`
- **Entity**: Represents a chat conversation
- **Fields**:
  - `id`: Primary key
  - `title`: Conversation title (auto-generated or user-provided)
  - `status`: Lifecycle state (CREATED, STREAMING, COMPLETED, INCOMPLETE, FAILED)
  - `createdAt`, `updatedAt`: Timestamps
- **Lifecycle States**:
  - `CREATED`: Initial state
  - `STREAMING`: Response generation in progress
  - `COMPLETED`: Response successfully generated
  - `INCOMPLETE`: Streaming interrupted (e.g., user abort, timeout)
  - `FAILED`: Error during response generation

#### `Message.java`
- **Entity**: Represents a single message in a conversation
- **Fields**:
  - `id`: Primary key
  - `conversationId`: Foreign key to conversation
  - `role`: Message role (USER, ASSISTANT, TOOL)
  - `content`: Message text content
  - `rawJson`: Full OpenAI response (for debugging)
  - `outputIndex`: Order in streaming response
  - `itemId`: OpenAI response item ID
  - `createdAt`: Timestamp

#### `ToolCall.java`
- **Entity**: Tracks tool execution attempts
- **Fields**:
  - `id`: Primary key
  - `conversationId`: Foreign key to conversation
  - `itemId`: Unique identifier per conversation (from OpenAI response)
  - `type`: Tool type (FUNCTION, MCP)
  - `name`: Tool name
  - `status`: Execution status (IN_PROGRESS, COMPLETED, FAILED)
  - `arguments`: Tool input (JSON)
  - `result`: Tool output (JSON)
  - `error`: Error message if failed
  - `outputIndex`: Order in streaming response
  - `createdAt`, `updatedAt`: Timestamps

#### `ConversationService.java`
- **Service**: Core business logic for conversations
- **Key Methods**:
  - `ensureConversation(Long id, String title)`: Creates or retrieves conversation
  - `appendMessage(Long conversationId, MessageRole role, String content, ...)`: Adds message
  - `updateMessageContent(Long conversationId, String itemId, String content, ...)`: Updates existing message (for streaming)
  - `upsertToolCall(Long conversationId, String itemId, ToolCallType type, ...)`: Creates or updates tool execution record
  - `getConversationWithDetails(Long id)`: Retrieves conversation with messages and tool calls
- **Reactive Pattern**: All methods return `Mono<T>` or `Flux<T>` for non-blocking execution

#### `ConversationController.java`
- **REST Controller**: HTTP endpoints for conversation management
- **Endpoints**:
  - `GET /api/conversations` - List all conversations (sorted by updatedAt desc)
  - `GET /api/conversations/{id}` - Get conversation with messages and tool calls
  - `POST /api/conversations` - Create new conversation
  - `DELETE /api/conversations/{id}` - Delete conversation and related data

### Repositories

- `ConversationRepository`: R2DBC repository for `Conversation` entity
- `MessageRepository`: R2DBC repository for `Message` entity
- `ToolCallRepository`: R2DBC repository for `ToolCall` entity

### DTOs

- `ConversationDetailDto`: Full conversation with nested messages and tool calls
- `ConversationSummaryDto`: Lightweight conversation summary (for list view)
- `MessageDto`: Message with optional tool calls
- `ToolCallDto`: Tool execution details

---

## Package: `mcp/`

**Purpose**: Integration with Model Context Protocol (MCP) for dynamic tool discovery and execution.

### What is MCP?

**Model Context Protocol** is an open protocol that allows AI applications to:
1. **Discover tools** from external servers (e.g., n8n workflows, custom APIs)
2. **Execute tools** with type-safe parameters
3. **Access resources** (files, databases, etc.)
4. **Use prompts** (reusable prompt templates)

This application uses the **MCP Java SDK** to connect to MCP servers.

### Architecture

```
McpServerService
    ↓
McpClientService
    ↓
McpSessionRegistry (manages client lifecycle)
    ↓
McpAsyncClient (from MCP SDK)
    ↓
MCP Server (e.g., n8n, custom tools)
```

### Core Classes

#### `McpServer.java`
- **Entity**: Represents a configured MCP server
- **Fields**:
  - `id`: Primary key
  - `serverId`: Unique identifier (e.g., "n8n-server-1")
  - `name`: Display name
  - `baseUrl`: Server endpoint (SSE or HTTP)
  - `apiKey`: Encrypted API key
  - `transport`: Protocol type (SSE, STREAMABLE_HTTP)
  - `status`: Connection status (IDLE, CONNECTING, CONNECTED, ERROR)
  - `version`: Optimistic lock version
  - `toolsCache`: JSON cache of available tools
  - `resourcesCache`: JSON cache of available resources
  - `promptsCache`: JSON cache of available prompts
  - `lastSyncedAt`: Last capabilities sync timestamp
  - `syncStatus`: Sync state (NEVER_SYNCED, SYNCING, SYNCED, SYNC_FAILED)
  - `lastUpdated`: Last modification timestamp

#### `McpSessionRegistry.java` ⭐ **Key Component**
- **Service**: Manages lifecycle of `McpAsyncClient` instances
- **Responsibilities**:
  1. **Lazy initialization**: Creates client on first use
  2. **Session caching**: Reuses clients for same server
  3. **State tracking**: INITIALIZING → ACTIVE → ERROR/CLOSED
  4. **Idle timeout**: Closes sessions after 30 min of inactivity
  5. **Graceful shutdown**: Closes all sessions on app stop
  6. **Error recovery**: Removes failed sessions from cache
  
- **Key Methods**:
  - `getOrCreateSession(String serverId)`: Returns `Mono<McpAsyncClient>` (thread-safe)
  - `closeSession(String serverId)`: Gracefully closes session
  - `closeAllSessions()`: Called on app shutdown
  
- **Thread Safety**: Uses `ConcurrentHashMap` and atomic operations

- **Implementation Details**:
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
      ACTIVE,        // Client ready for use
      ERROR,         // Initialization failed
      CLOSED         // Session closed
  }
  ```

#### `McpClientService.java`
- **Service**: High-level MCP operations
- **Methods**:
  - `listToolsAsync(String serverId)`: Discover available tools
  - `listResourcesAsync(String serverId)`: Discover available resources
  - `listPromptsAsync(String serverId)`: Discover available prompts
  - `callToolAsync(String serverId, String toolName, Map<String, Object> args)`: Execute tool
  - `getServerCapabilities(String serverId)`: Get server capabilities snapshot
  
- **Reactive**: All methods return `Mono<T>`
- **Timeout**: Operations timeout after 15 seconds
- **Error Handling**: Translates MCP exceptions to domain exceptions

#### `McpServerService.java`
- **Service**: CRUD operations for MCP servers
- **Methods**:
  - `listServers()`: List all configured servers
  - `getServer(String serverId)`: Get server by ID
  - `createOrUpdate(McpServerRequest)`: Create or update server
  - `deleteServer(String serverId)`: Delete server and close session
  - `verifyConnection(String serverId)`: Test connection to server
  - `syncCapabilities(String serverId)`: Refresh tools/resources/prompts cache
  - `updateServerStatus(String serverId, McpServerStatus)`: Update connection status
  
- **Optimistic Locking**: Uses `@Version` field to handle concurrent updates
- **Encryption**: API keys are encrypted before storing in database

#### `McpToolContextBuilder.java`
- **Service**: Injects MCP tools into OpenAI requests
- **Method**: `augmentPayload(ObjectNode payload)`
- **Logic**:
  1. Load all CONNECTED servers from database
  2. Parse `toolsCache` for each server
  3. Transform MCP tool schemas to OpenAI function format
  4. Add to `tools` array in OpenAI request
  
- **OpenAI Function Format**:
  ```json
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
  ```

#### `ToolApprovalPolicy.java` / `ToolApprovalPolicyService.java`
- **Purpose**: Security mechanism for tool execution
- **Entity**: `ToolApprovalPolicy`
  - `serverId` + `toolName`: Unique key
  - `policy`: Approval policy (ALWAYS_ALLOW, ALWAYS_DENY, ASK_USER)
  
- **Service Methods**:
  - `requiresApproval(String serverId, String toolName)`: Check if approval needed
  - `setPolicy(String serverId, String toolName, ApprovalPolicy)`: Update policy
  - `getPolicy(String serverId, String toolName)`: Get policy (default: ASK_USER)

#### `McpCapabilitiesScheduler.java`
- **Purpose**: Background job to refresh MCP capabilities cache
- **Schedule**: Every 5 minutes (configurable)
- **Logic**:
  1. Find all CONNECTED servers
  2. For each server: call `syncCapabilities(serverId)`
  3. On success: update `lastSyncedAt`, set `syncStatus=SYNCED`
  4. On error: set `syncStatus=SYNC_FAILED`, log error

### Controllers

#### `McpServerController.java`
- **Endpoints**:
  - `GET /api/mcp/servers` - List servers
  - `POST /api/mcp/servers` - Create/update server
  - `GET /api/mcp/servers/{id}` - Get server
  - `DELETE /api/mcp/servers/{id}` - Delete server
  - `POST /api/mcp/servers/{id}/verify` - Test connection
  - `GET /api/mcp/servers/{id}/capabilities` - Get capabilities
  - `POST /api/mcp/servers/{id}/sync` - Refresh cache

#### `McpToolController.java`
- **Endpoints**:
  - `POST /api/mcp/tools/execute` - Execute MCP tool
  
- **Request**:
  ```json
  {
    "serverId": "n8n-server-1",
    "toolName": "send_email",
    "arguments": {
      "to": "user@example.com",
      "subject": "Hello",
      "body": "Test message"
    }
  }
  ```

- **Response**:
  ```json
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

#### `McpServerStatusStreamController.java`
- **Purpose**: SSE endpoint for real-time MCP server status updates
- **Endpoint**: `GET /api/mcp/servers/{id}/status/stream`
- **Events**: `status_update`, `connection_change`, `capabilities_synced`
- **Use Case**: Frontend subscribes to get live connection status

#### `ToolApprovalPolicyController.java`
- **Endpoints**:
  - `GET /api/mcp/approval-policies` - List all policies
  - `PUT /api/mcp/approval-policies` - Set policy
  - `DELETE /api/mcp/approval-policies` - Remove policy (revert to default)

### Events

#### `McpServerStatusEvent.java`
- **Purpose**: Application event for server status changes
- **Triggers**: Connection state changes, capability syncs
- **Listeners**: `McpServerStatusPublisher` (publishes to SSE clients)

---

## Package: `responses/`

**Purpose**: Integration with OpenAI Responses API for streaming chat completions.

### Core Classes

#### `ResponseStreamService.java` ⭐ **Most Complex Component**
- **Service**: Main orchestrator for chat streaming
- **Responsibilities**:
  1. Build OpenAI request with conversation history
  2. Inject MCP tools via `McpToolContextBuilder`
  3. Stream responses from OpenAI API (SSE)
  4. Parse SSE events and update conversation state
  5. Handle tool execution with approval workflow
  6. Forward events to frontend

- **Key Methods**:
  - `streamResponses(ResponseStreamRequest, String authHeader)`: Main entry point
  - `executeToolWithApproval(...)`: Handle tool execution with approval check
  - `processToolOutputs(...)`: Send tool results back to OpenAI
  
- **Internal State** (per stream):
  ```java
  class StreamState {
      Long conversationId;
      AtomicReference<String> currentMessageId;
      AtomicReference<String> currentToolCallId;
      Map<String, ToolCallInfo> toolCalls;
      AtomicBoolean waitingForApproval;
      Set<String> processedApprovals;
      // ... more
  }
  ```

- **SSE Event Types** (from OpenAI):
  - `response.created`: Response started
  - `response.output_item.added`: New output item (message or tool call)
  - `response.text.delta`: Text chunk
  - `response.function_call_arguments.delta`: Tool arguments chunk
  - `response.function_call_arguments.done`: Tool arguments complete
  - `response.output_item.done`: Output item finished
  - `response.done`: Response complete

- **Custom SSE Events** (to frontend):
  - `init`: Streaming started
  - `conversation_status`: Status update (STREAMING, COMPLETED, etc.)
  - `message`: Text delta
  - `tool_call_update`: Tool execution state change
  - `approval_required`: User approval needed
  - `error`: Error occurred
  - `done`: Stream complete

- **Tool Execution Flow**:
  ```
  1. Parse tool call from OpenAI event
  2. Check approval policy (ToolApprovalPolicyService)
  3. If ALWAYS_ALLOW: Execute immediately
  4. If ASK_USER: Emit approval_required event → Wait for approval
  5. If approved: Execute via McpClientService
  6. Send tool result back to OpenAI
  7. OpenAI generates final response
  ```

#### `ResponseStreamController.java`
- **Controller**: SSE endpoint for streaming
- **Endpoint**: `POST /api/responses/stream`
- **Request**:
  ```json
  {
    "conversationId": 123,
    "title": "New Chat",
    "payload": {
      "model": "gpt-4",
      "messages": [...],
      "tools": [...],
      "stream": true
    }
  }
  ```
  
- **Response**: SSE stream with events (see above)

#### `ApprovalResponseController.java`
- **Controller**: Tool approval endpoint
- **Endpoint**: `POST /api/responses/approval/{approvalRequestId}`
- **Request**:
  ```json
  {
    "approved": true
  }
  ```
  
- **Logic**: Resumes tool execution after user approval

#### `ToolDefinitionProvider.java` / `DefaultToolDefinitionProvider.java`
- **Interface**: Provides list of available tools
- **Implementation**: Loads MCP tools from database cache
- **Method**: `Flux<JsonNode> listTools()`
- **Format**: OpenAI function format (see `McpToolContextBuilder`)

### Complexity Analysis

**Why `ResponseStreamService` is 800+ lines**:
1. SSE event parsing (many event types)
2. State management (streaming, tool calls, approval)
3. Error handling (timeouts, retries, fallbacks)
4. Conversation lifecycle tracking
5. Tool execution orchestration
6. OpenAI API quirks handling

**Refactoring Suggestions**:
- Extract `ToolExecutionOrchestrator` service
- Extract `SseEventParser` utility
- Extract `ConversationStateTracker` service
- Use formal state machine for streaming states

---

## Package: `security/`

**Purpose**: Encryption utilities for sensitive data (API keys).

### Classes

#### `AesGcmSecretEncryptor.java`
- **Interface**: `SecretEncryptor`
- **Algorithm**: AES-256-GCM (Authenticated Encryption)
- **Key Derivation**: PBKDF2 with salt
- **Methods**:
  - `encrypt(String plaintext)`: Returns Base64-encoded ciphertext
  - `decrypt(String ciphertext)`: Returns plaintext
  
- **Configuration**: Master password from environment variable
- **Security**: Salt + IV stored with ciphertext (safe to store)

#### `EncryptionException.java`
- **Exception**: Thrown on encryption/decryption errors

---

## Package: `config/`

**Purpose**: Spring configuration beans.

### Classes

#### `OpenAiProperties.java`
- **Configuration**: OpenAI API settings
- **Properties**:
  - `baseUrl`: LiteLLM proxy URL (default: http://localhost:4000)
  - `apiKey`: OpenAI API key (from env)
  - `timeout`: Request timeout (default: 30s)

#### `WebClientConfig.java` (if exists)
- **Bean**: Configures `WebClient` for OpenAI API
- **Settings**: Timeout, connection pooling, error handling

#### `R2dbcConfig.java` (if exists)
- **Bean**: R2DBC connection factory
- **Settings**: Database URL, credentials, pool size

---

## Package: `connection/`

**Purpose**: Connection verification utilities.

### Classes

#### `ConnectionVerificationTemplate.java`
- **Utility**: Generic template for verifying external connections
- **Used by**: MCP server connection verification

---

## Package: `openai/`

**Purpose**: OpenAI-specific data transfer objects.

### Classes

#### `McpCall.java`
- **DTO**: Represents a tool call in OpenAI format
- **Fields**: `name`, `arguments`, `callId`

---

## Package: `utils/`

**Purpose**: Generic utilities.

### Classes

#### `GenericMapper.java`
- **Utility**: MapStruct-like object mapping
- **Methods**: `map(Object source, Class<T> target)`
- **Use Case**: Entity ↔ DTO conversion

---

## Database Schema

### Key Tables

#### `conversations`
```sql
CREATE TABLE conversations (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

#### `messages`
```sql
CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT,
    raw_json TEXT,
    output_index INTEGER,
    item_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL
);
```

#### `tool_calls`
```sql
CREATE TABLE tool_calls (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    item_id VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL,
    name VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    arguments TEXT,
    result TEXT,
    error TEXT,
    output_index INTEGER,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (conversation_id, item_id)
);
```

#### `mcp_servers`
```sql
CREATE TABLE mcp_servers (
    id BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    api_key VARCHAR(1024),
    transport VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT DEFAULT 0,
    tools_cache TEXT,
    resources_cache TEXT,
    prompts_cache TEXT,
    last_synced_at TIMESTAMP,
    sync_status VARCHAR(20) DEFAULT 'NEVER_SYNCED',
    last_updated TIMESTAMP NOT NULL
);
```

#### `tool_approval_policies`
```sql
CREATE TABLE tool_approval_policies (
    id BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    policy VARCHAR(20) NOT NULL,
    UNIQUE (server_id, tool_name)
);
```

---

## Testing

### Unit Tests

- **ConversationServiceTest**: Tests conversation CRUD operations
- **ResponseStreamServiceTest**: Tests streaming logic (mocked OpenAI API)
- **McpClientServiceTest** (if exists): Tests MCP operations

### Integration Tests

- **ConversationControllerTest**: Tests REST endpoints
- **E2E Tests**: Full flow from user input to response (with mocked MCP server)

### Test Coverage

- Target: 90%+ for core services
- Coverage tool: JaCoCo
- Command: `./gradlew test jacocoTestReport`

---

## Configuration

### Profiles

- **dev**: H2 in-memory database
- **prod**: PostgreSQL 16
- **prod-pg16**: PostgreSQL 16 with specific compatibility settings

### Environment Variables

See `application.properties` and `application-{profile}.properties`:

- `SPRING_PROFILES_ACTIVE`: Active profile (dev, prod)
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`: Database config
- `OPENAI_BASE_URL`, `OPENAI_API_KEY`: OpenAI API config
- `ENCRYPTION_MASTER_PASSWORD`: Master password for secret encryption

---

## Build & Run

### Build
```bash
./gradlew :chatbot-backend:build
```

### Run (dev profile with H2)
```bash
./gradlew :chatbot-backend:bootRun
```

### Run (prod profile with PostgreSQL)
```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew :chatbot-backend:bootRun
```

### Tests
```bash
./gradlew :chatbot-backend:test
```

### Docker Build
```bash
./gradlew :chatbot-backend:bootBuildImage
```

---

## API Documentation

### Swagger/OpenAPI

Access at: http://localhost:8080/swagger-ui.html (if configured)

### Key Endpoints

#### Conversations
- `GET /api/conversations` - List conversations
- `GET /api/conversations/{id}` - Get conversation details
- `POST /api/conversations` - Create conversation
- `DELETE /api/conversations/{id}` - Delete conversation

#### Streaming
- `POST /api/responses/stream` - Start SSE stream

#### MCP Servers
- `GET /api/mcp/servers` - List MCP servers
- `POST /api/mcp/servers` - Create/update server
- `DELETE /api/mcp/servers/{id}` - Delete server
- `POST /api/mcp/servers/{id}/verify` - Test connection
- `GET /api/mcp/servers/{id}/capabilities` - Get tools/resources/prompts
- `POST /api/mcp/servers/{id}/sync` - Refresh capabilities

#### MCP Tools
- `POST /api/mcp/tools/execute` - Execute tool

#### Approval Policies
- `GET /api/mcp/approval-policies` - List policies
- `PUT /api/mcp/approval-policies` - Set policy

---

## Known Issues & Improvements

See [../AGENTS.md](../AGENTS.md) for detailed analysis.

**Priority issues**:
1. ⚠️ `ResponseStreamService` is too complex (800+ lines) → Refactor
2. ⚠️ Missing in-memory cache for MCP capabilities → Add Spring Cache
3. ⚠️ Potential race condition in `McpSessionRegistry` → Use `Mono.cache()`
4. ⚠️ No circuit breaker for MCP connections → Add Resilience4j

---

## Contributing

When adding new features:
1. Follow reactive patterns (return `Mono`/`Flux`, avoid blocking)
2. Add unit tests (target 90%+ coverage)
3. Update DTOs in `dto/` packages
4. Add database migrations in `resources/db/migration/`
5. Update this documentation

---

For frontend documentation, see [../chatbot/AGENTS.md](../chatbot/AGENTS.md).
