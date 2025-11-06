# Chatbot Application - Architecture & Developer Guide

## 📋 Table of Contents
- [Overview](#overview)
- [Architecture Summary](#architecture-summary)
- [Directory Structure](#directory-structure)
- [Key Business Logic](#key-business-logic)
- [Data Flow](#data-flow)
- [Development Setup](#development-setup)
- [Code Quality Notes](#code-quality-notes)

---

## Overview

This is a **full-stack reactive chatbot application** built with:
- **Backend**: Spring Boot 3.4 with WebFlux (reactive, non-blocking)
- **Frontend**: React 19 + Vite + TypeScript + Zustand
- **Database**: PostgreSQL 16 with R2DBC (reactive driver)
- **LLM Integration**: OpenAI-compatible API via LiteLLM proxy
- **Tool Execution**: Model Context Protocol (MCP) for dynamic tool integration
- **Workflow Automation**: n8n integration for complex automations

### Key Features
1. **Streaming Chat**: Server-Sent Events (SSE) for real-time message streaming
2. **MCP Integration**: Dynamic tool discovery and execution from external MCP servers
3. **Tool Approval System**: User can approve/deny tool executions with configurable policies
4. **n8n Workflows**: Execute automated workflows triggered from chat
5. **Reactive Architecture**: Fully non-blocking I/O for scalability

---

## Architecture Summary

### High-Level Flow
```
User Input → Frontend (React)
    ↓
Backend API (Spring WebFlux)
    ↓
OpenAI Responses API (via LiteLLM)
    ↓ (if tool call needed)
MCP Server (n8n, custom tools)
    ↓
Tool Result → OpenAI → Final Response → User
```

### Technology Stack

#### Backend (Spring Boot Reactive)
- **Framework**: Spring Boot 3.4.0 with WebFlux
- **Language**: Java 17
- **Database Access**: Spring Data R2DBC (reactive)
- **Database**: PostgreSQL 16 (with Flyway migrations)
- **MCP SDK**: `io.modelcontextprotocol.sdk:mcp:0.15.0`
- **Build Tool**: Gradle 8.x
- **Security**: AES-GCM encryption for API keys

#### Frontend (Vite + React)
- **Framework**: React 19.1
- **Build Tool**: Vite 7.1
- **Language**: TypeScript 5.9
- **State Management**: Zustand 5.0
- **HTTP Client**: Fetch API + SSE via `@microsoft/fetch-event-source`
- **Styling**: CSS with CSS variables for theming

#### Infrastructure
- **Containerization**: Docker + Docker Compose
- **LLM Gateway**: LiteLLM (supports multiple providers: OpenAI, Ollama, etc.)
- **Automation**: n8n workflow engine
- **Model Runtime**: Ollama (local LLM execution)

---

## Directory Structure

### Root Level
```
/
├── chatbot-backend/          # Spring Boot backend application
├── chatbot/                  # React frontend application
├── config/                   # Shared configuration (LiteLLM config)
├── gradle/                   # Gradle wrapper
├── .github/                  # GitHub Actions CI/CD
├── build.gradle              # Root Gradle build with Docker Compose tasks
├── docker-compose.yml        # Production deployment
├── docker-compose.dev.yml    # Development environment
└── *.md                      # Documentation files
```

### Backend Structure
```
chatbot-backend/src/main/java/app/chatbot/
├── ChatbotBackendApplication.java  # Main entry point
├── config/                         # Configuration classes
│   └── OpenAiProperties.java
├── conversation/                   # Chat conversation domain
│   ├── ConversationService.java    # Core chat logic
│   ├── ConversationController.java # REST endpoints
│   ├── Conversation.java           # Entity
│   ├── Message.java                # Message entity
│   └── ToolCall.java               # Tool execution tracking
├── mcp/                            # Model Context Protocol integration
│   ├── McpSessionRegistry.java     # MCP client lifecycle management
│   ├── McpClientService.java       # MCP operations (tools, resources, prompts)
│   ├── McpServerService.java       # MCP server CRUD + connection management
│   ├── McpServerController.java    # REST API for MCP servers
│   ├── McpToolController.java      # Tool execution endpoint
│   ├── McpToolContextBuilder.java  # Injects tools into OpenAI requests
│   ├── ToolApprovalPolicy*.java    # Approval policy management
│   └── events/                     # Server-Sent Events for MCP status
├── responses/                      # OpenAI Responses API integration
│   ├── ResponseStreamService.java  # Main streaming logic
│   ├── ResponseStreamController.java # SSE endpoint
│   └── ApprovalResponseController.java # Tool approval endpoint
├── security/                       # Encryption for sensitive data
│   └── AesGcmSecretEncryptor.java
└── utils/                          # Utility classes
    └── GenericMapper.java

chatbot-backend/src/main/resources/
├── application*.properties         # Spring configuration profiles
└── db/migration/                   # Flyway SQL migrations
    ├── V1__init_schema.sql
    ├── V2__make_call_id_nullable.sql
    ├── V3__refactor_tool_call_ids.sql
    ├── V4__add_conversation_lifecycle.sql
    ├── V5__make_tool_call_name_nullable.sql
    ├── V6__add_unique_constraint_tool_calls.sql
    └── V7__add_tool_approval_policies.sql
```

### Frontend Structure
```
chatbot/src/
├── main.tsx                        # Application entry point
├── App.tsx                         # Main component
├── components/                     # React components
│   ├── ChatHistory.tsx             # Message display
│   ├── ChatInput.tsx               # User input
│   ├── ChatSidebar.tsx             # Conversation list
│   ├── SettingsPanel.tsx           # App configuration
│   ├── SystemPromptPanel.tsx       # System prompt editor
│   ├── McpCapabilitiesPanel.tsx    # MCP server management
│   ├── N8nPanel.tsx                # n8n workflow management
│   ├── ToolCallList.tsx            # Tool execution list
│   ├── ToolCallDetails.tsx         # Tool execution details
│   ├── UserApprovalDialog.tsx      # Tool approval modal
│   └── Modal.tsx                   # Generic modal
├── store/                          # Zustand state management
│   ├── chatStore.ts                # Chat state + actions
│   ├── mcpServerStore.ts           # MCP server state + actions
│   └── n8nStore.ts                 # n8n workflow state
├── hooks/                          # Custom React hooks
│   ├── useChatState.ts             # Chat state hook
│   └── useMcpState.ts              # MCP state hook
├── services/                       # API client
│   └── apiClient.ts                # Typed HTTP client
├── types/                          # TypeScript types
│   └── mcp.ts                      # MCP-related types
└── utils/                          # Utility functions
    ├── format.ts                   # Formatting helpers
    └── logger.ts                   # Logging helpers
```

---

## Key Business Logic

### 1. Conversation Management (`conversation/`)

**Purpose**: Manage chat conversations, messages, and tool execution tracking.

**Key Classes**:
- `ConversationService`: Core service for conversation CRUD operations
  - `ensureConversation()`: Creates or retrieves conversation
  - `appendMessage()`: Adds new message to conversation
  - `upsertToolCall()`: Tracks tool execution state
  
- `ConversationController`: REST endpoints
  - `GET /api/conversations` - List conversations
  - `GET /api/conversations/{id}` - Get conversation with messages
  - `POST /api/conversations` - Create conversation
  - `DELETE /api/conversations/{id}` - Delete conversation

**Database Schema**:
```sql
conversations
├── id (PK)
├── title
├── status (CREATED, STREAMING, COMPLETED, INCOMPLETE, FAILED)
├── created_at
└── updated_at

messages
├── id (PK)
├── conversation_id (FK)
├── role (USER, ASSISTANT, TOOL)
├── content
├── raw_json (full OpenAI response)
├── output_index
├── item_id (OpenAI response item ID)
└── created_at

tool_calls
├── id (PK)
├── conversation_id (FK)
├── item_id (unique per conversation)
├── type (FUNCTION, MCP)
├── name
├── status (IN_PROGRESS, COMPLETED, FAILED)
├── arguments (JSON)
├── result (JSON)
├── error
├── output_index
├── created_at
└── updated_at
```

---

### 2. OpenAI Responses API Integration (`responses/`)

**Purpose**: Stream chat responses from OpenAI-compatible API using Server-Sent Events.

**Key Class**: `ResponseStreamService`

**Flow**:
1. **Request Preparation**:
   - Merge system prompt, conversation history, and user message
   - Inject available MCP tools via `McpToolContextBuilder`
   - Set `stream=true` for SSE
   
2. **Streaming**:
   - Connect to OpenAI `/v1/chat/completions` endpoint
   - Parse SSE events: `response.created`, `response.output_item.added`, `response.text.delta`, etc.
   - Update conversation status in database
   
3. **Tool Execution**:
   - When OpenAI requests tool call: `response.function_call_arguments.done`
   - Check approval policy (`ToolApprovalPolicyService`)
   - If approval needed: emit `approval_required` event, wait for user response
   - Execute tool via `McpClientService.callToolAsync()`
   - Send tool result back to OpenAI for final response

4. **Error Handling**:
   - Timeout handling (30s default)
   - Retry on transient errors
   - Mark conversation as `FAILED` on unrecoverable errors

**Endpoints**:
- `POST /api/responses/stream` - Start SSE stream
- `POST /api/responses/approval/{approvalRequestId}` - Approve/deny tool execution

**Key Methods**:
```java
// Main streaming method
Flux<ServerSentEvent<String>> streamResponses(
    ResponseStreamRequest request, 
    String authorizationHeader
)

// Tool execution with approval check
Mono<JsonNode> executeToolWithApproval(
    String serverId, 
    String toolName, 
    Map<String, Object> arguments
)
```

---

### 3. Model Context Protocol (MCP) Integration (`mcp/`)

**Purpose**: Dynamically discover and execute tools from external MCP servers (e.g., n8n workflows).

#### MCP Architecture

**MCP** is a protocol for exposing tools, resources, and prompts from external servers. This application uses the Java MCP SDK (`io.modelcontextprotocol.sdk`) to connect to MCP servers.

**Key Components**:

1. **McpSessionRegistry** (Session Lifecycle Management)
   - Manages `McpAsyncClient` instances (one per MCP server)
   - Lazy initialization: clients created on first use
   - Session caching: reuses clients for subsequent requests
   - State tracking: INITIALIZING → ACTIVE → ERROR/CLOSED
   - Idle timeout: closes sessions after 30 min of inactivity
   - Graceful shutdown: closes all sessions on app stop

2. **McpClientService** (MCP Operations)
   - `listToolsAsync(serverId)`: Discover available tools
   - `listResourcesAsync(serverId)`: Discover available resources
   - `listPromptsAsync(serverId)`: Discover available prompts
   - `callToolAsync(serverId, toolName, args)`: Execute a tool

3. **McpServerService** (Server Management)
   - CRUD operations for MCP servers
   - Connection verification
   - Capabilities caching (tools, resources, prompts)
   - Status tracking (IDLE, CONNECTING, CONNECTED, ERROR)

4. **McpToolContextBuilder** (OpenAI Integration)
   - Loads tools from connected MCP servers
   - Transforms MCP tool schemas to OpenAI function format
   - Injects tools into OpenAI request payload

5. **ToolApprovalPolicyService** (Security)
   - Manages approval policies per tool
   - Policies: `ALWAYS_ALLOW`, `ALWAYS_DENY`, `ASK_USER`
   - Default policy: `ASK_USER`

**Database Schema**:
```sql
mcp_servers
├── id (PK)
├── server_id (unique, e.g., "n8n-server-1")
├── name
├── base_url (SSE or Streamable HTTP endpoint)
├── api_key (encrypted)
├── transport (SSE, STREAMABLE_HTTP)
├── status (IDLE, CONNECTING, CONNECTED, ERROR)
├── version (optimistic locking)
├── tools_cache (JSON)
├── resources_cache (JSON)
├── prompts_cache (JSON)
├── last_synced_at
├── sync_status (NEVER_SYNCED, SYNCING, SYNCED, SYNC_FAILED)
└── last_updated

tool_approval_policies
├── id (PK)
├── server_id (FK)
├── tool_name
├── policy (ALWAYS_ALLOW, ALWAYS_DENY, ASK_USER)
└── UNIQUE(server_id, tool_name)
```

**Endpoints**:
- `GET /api/mcp/servers` - List all MCP servers
- `POST /api/mcp/servers` - Create/update MCP server
- `GET /api/mcp/servers/{id}` - Get server details
- `DELETE /api/mcp/servers/{id}` - Delete server
- `POST /api/mcp/servers/{id}/verify` - Test connection
- `GET /api/mcp/servers/{id}/capabilities` - Get tools/resources/prompts
- `POST /api/mcp/servers/{id}/sync` - Refresh capabilities cache
- `GET /api/mcp/servers/{id}/status/stream` - SSE for connection status
- `POST /api/mcp/tools/execute` - Execute a tool

**MCP Transports**:
- **SSE (Server-Sent Events)**: For n8n and other SSE-compatible servers
- **Streamable HTTP**: For HTTP-based MCP servers (using Java HttpClient)

---

### 4. n8n Integration

**Purpose**: Execute n8n workflows as MCP tools.

**How it works**:
1. n8n runs as a Docker container with embedded MCP server
2. Backend connects to n8n via MCP protocol (SSE transport)
3. n8n workflows are exposed as MCP tools
4. User can trigger workflows from chat via tool calls

**Configuration**:
- n8n base URL: `http://n8n:5678/api/mcp/sse` (or custom)
- Authentication via n8n API key
- Workflows must be active to be discoverable

**Frontend Component**: `N8nPanel.tsx`
- Displays n8n connection status
- Shows available workflows
- Allows triggering workflows manually

---

### 5. Frontend Architecture

**State Management**: Zustand stores
- `chatStore`: Conversation state, messages, streaming state
- `mcpServerStore`: MCP server list, connection status
- `n8nStore`: n8n workflow list

**Key Components**:

1. **ChatHistory** (`components/ChatHistory.tsx`)
   - Displays conversation messages
   - Renders markdown with `react-markdown`
   - Shows tool execution status

2. **ChatInput** (`components/ChatInput.tsx`)
   - Textarea with auto-resize
   - Submit on Enter (Shift+Enter for newline)
   - Disabled during streaming

3. **SettingsPanel** (`components/SettingsPanel.tsx`)
   - Model selection
   - Temperature, max tokens, etc.
   - System prompt editor
   - MCP server management

4. **UserApprovalDialog** (`components/UserApprovalDialog.tsx`)
   - Modal for tool approval requests
   - Displays tool name, arguments, server
   - Approve/deny buttons

**API Client** (`services/apiClient.ts`)
- Typed HTTP client using Fetch API
- Handles authentication
- Parses responses and errors

**SSE Handling**:
```typescript
fetchEventSource("/api/responses/stream", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(payload),
  onmessage: (event) => {
    // Parse SSE event
    // Update state
  },
  onerror: (err) => {
    // Handle error
  }
});
```

---

## Data Flow

### Chat Message Flow (Happy Path)

```
1. User types message in ChatInput
   ↓
2. Frontend: chatStore.sendMessage(content)
   ↓
3. POST /api/responses/stream (SSE)
   {
     conversationId: 123,
     payload: {
       model: "gpt-4",
       messages: [...],
       tools: [...MCP tools...],
       stream: true
     }
   }
   ↓
4. Backend: ResponseStreamService.streamResponses()
   - Ensure conversation exists
   - Load MCP tools via McpToolContextBuilder
   - Forward to OpenAI API
   ↓
5. OpenAI API returns SSE events:
   - response.created
   - response.output_item.added
   - response.text.delta (multiple)
   - response.done
   ↓
6. Backend forwards events to frontend
   ↓
7. Frontend updates ChatHistory in real-time
```

### Tool Call Flow (with Approval)

```
1. OpenAI requests tool call
   ↓
2. Backend: ResponseStreamService detects tool_call
   ↓
3. Check approval policy:
   - ALWAYS_ALLOW → Execute immediately
   - ALWAYS_DENY → Return error
   - ASK_USER → Emit approval_required event
   ↓
4. Frontend: Shows UserApprovalDialog
   ↓
5. User clicks Approve/Deny
   ↓
6. POST /api/responses/approval/{id}
   { approved: true }
   ↓
7. Backend: McpClientService.callToolAsync()
   - Get or create MCP session
   - Call tool on MCP server
   - Return result
   ↓
8. Send tool result to OpenAI
   ↓
9. OpenAI generates final response
   ↓
10. Backend forwards response to frontend
```

---

## Development Setup

### Prerequisites
- Java 17+
- Node.js 18+
- Docker + Docker Compose
- Gradle (wrapper included)

### Quick Start

1. **Clone repository**
   ```bash
   git clone <repo-url>
   cd chatbot
   ```

2. **Start infrastructure** (Ollama, LiteLLM, n8n, PostgreSQL)
   ```bash
   ./gradlew developmentUp
   ```
   This starts only infrastructure services in Docker.

3. **Run backend** (in separate terminal)
   ```bash
   ./gradlew :chatbot-backend:bootRun
   # Backend runs on http://localhost:8080
   ```

4. **Run frontend** (in separate terminal)
   ```bash
   cd chatbot
   npm install
   npm run dev
   # Frontend runs on http://localhost:5173
   ```

5. **Access application**
   - Frontend: http://localhost:5173
   - Backend API: http://localhost:8080
   - n8n: http://localhost:5678
   - LiteLLM: http://localhost:4000

### Production Deployment

```bash
# Build and start all services
./gradlew up

# Or use Docker Compose directly
docker compose up -d
```

### Environment Variables

See `.env.example` for all configuration options.

Key variables:
- `OPENAI_API_KEY`: OpenAI API key
- `OPENAI_BASE_URL`: Custom LLM endpoint (default: http://litellm:4000)
- `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`: Database config
- `N8N_HOST`, `N8N_PORT`: n8n configuration

---

## Code Quality Notes

### ✅ Strengths

1. **Reactive Architecture**: Fully non-blocking with Project Reactor
2. **Type Safety**: TypeScript on frontend, strong typing on backend
3. **Test Coverage**: Good coverage for core services (90%+ for conversation, responses)
4. **Error Handling**: Comprehensive error handling with fallbacks
5. **Documentation**: Extensive inline documentation and comments

### ⚠️ Areas for Improvement

#### 1. **Code Redundancy**

**Issue**: `ResponseStreamService` is very large (800+ lines) with complex nested logic.

**Suggestion**: Extract sub-services:
```java
// Extract tool execution logic
ToolExecutionService {
  Mono<ToolResult> executeWithApproval(...)
}

// Extract SSE event handling
SseEventMapper {
  ServerSentEvent<String> mapToFrontend(...)
}

// Extract OpenAI request building
OpenAiRequestBuilder {
  ObjectNode buildRequest(...)
}
```

**Files to refactor**: `chatbot-backend/src/main/java/app/chatbot/responses/ResponseStreamService.java`

---

#### 2. **Hard to Maintain: Complex State Machine**

**Issue**: `ResponseStreamService` has complex state tracking with multiple flags:
```java
StreamState {
  AtomicReference<String> currentMessageId
  AtomicReference<String> currentToolCallId
  Map<String, ToolCallInfo> toolCalls
  AtomicBoolean waitingForApproval
  // ... many more
}
```

**Suggestion**: Use formal state machine pattern with clear states and transitions:
```java
enum StreamingState {
  INITIALIZING,
  STREAMING_TEXT,
  WAITING_FOR_APPROVAL,
  EXECUTING_TOOL,
  FINALIZING,
  COMPLETED,
  FAILED
}

class StreamStateMachine {
  transition(Event event) -> State
}
```

**Files affected**: `chatbot-backend/src/main/java/app/chatbot/responses/ResponseStreamService.java`

---

#### 3. **Potential Race Condition in McpSessionRegistry**

**Issue**: Session initialization might race between multiple concurrent requests:
```java
// Two threads call getOrCreateSession(serverId) simultaneously
// Both might see INITIALIZING state and wait
// If init fails, both fail
```

**Suggestion**: Use `Mono.cache()` for initialization:
```java
Mono<McpAsyncClient> initMono = initializeClient(serverId).cache();
holder.initializationMono = initMono;
```

**Files affected**: `chatbot-backend/src/main/java/app/chatbot/mcp/McpSessionRegistry.java`

---

#### 4. **Missing Caching for MCP Capabilities**

**Issue**: Every tool call loads capabilities from database, but capabilities could be cached in-memory.

**Suggestion**: Add Spring Cache:
```java
@Cacheable("mcp-capabilities")
public Mono<List<Tool>> getTools(String serverId) {
  // Load from DB cache or refresh from server
}

@CacheEvict("mcp-capabilities")
public Mono<Void> syncCapabilities(String serverId) {
  // Invalidate cache after sync
}
```

**Files affected**: 
- `chatbot-backend/src/main/java/app/chatbot/mcp/McpServerService.java`
- `chatbot-backend/src/main/java/app/chatbot/mcp/McpToolContextBuilder.java`

---

#### 5. **Frontend: Zustand Store Complexity**

**Issue**: `chatStore.ts` is 1000+ lines with many responsibilities:
- Conversation management
- Message handling
- Streaming state
- Tool execution tracking
- Model configuration

**Suggestion**: Split into multiple stores:
```typescript
// conversationStore.ts - CRUD for conversations
// messageStore.ts - Message management
// streamingStore.ts - SSE state
// toolCallStore.ts - Tool execution tracking
// configStore.ts - Model configuration
```

**Files to refactor**: `chatbot/src/store/chatStore.ts`

---

#### 6. **Inconsistent Error Handling**

**Issue**: Some services throw `ResponseStatusException`, others return `Mono.error()`, frontend sometimes catches, sometimes doesn't.

**Suggestion**: Standardize error handling:
- Backend: Use `@ControllerAdvice` for global exception handling
- Frontend: Use error boundaries + global error handler

**Files affected**: Multiple controllers and services

---

#### 7. **Missing Input Validation**

**Issue**: Some endpoints don't validate input (e.g., tool arguments).

**Suggestion**: Add `@Validated` and custom validators:
```java
@PostMapping("/tools/execute")
public Mono<ToolResult> executeTool(
  @Valid @RequestBody ToolExecutionRequest request
) {
  // ...
}
```

**Files affected**: Controllers in `mcp/` and `responses/`

---

#### 8. **Hardcoded Timeouts**

**Issue**: Timeouts are hardcoded in multiple places (15s, 30s, etc.)

**Suggestion**: Move to configuration:
```java
@ConfigurationProperties("app.mcp")
public class McpProperties {
  Duration initializationTimeout = Duration.ofSeconds(10);
  Duration operationTimeout = Duration.ofSeconds(15);
  Duration idleTimeout = Duration.ofMinutes(30);
}
```

**Files affected**: `McpSessionRegistry`, `McpClientService`, `ResponseStreamService`

---

#### 9. **Database Migration Naming**

**Issue**: Migration files have non-sequential numbering (V1, V2, V3, V5, V6, V7 - missing V4).

**Finding**: Actually V4 exists (`V4__add_conversation_lifecycle.sql`), but the gap suggests possible confusion.

**Suggestion**: Use timestamp-based naming for new migrations:
```
V20250101_120000__add_new_feature.sql
```

---

#### 10. **Missing Observability**

**Issue**: No metrics or tracing for production monitoring.

**Suggestion**: Add Micrometer metrics:
```java
@Timed("mcp.tool.call")
public Mono<CallToolResult> callTool(...) {
  // ...
}

// Metrics:
// - mcp.tool.call.duration
// - mcp.session.active.count
// - chat.message.streaming.duration
```

**Files affected**: Core services

---

### 🐛 Potential Bugs

#### 1. **Memory Leak in MCP Sessions**

**Issue**: If MCP server disconnects unexpectedly, session might remain in `ACTIVE` state and never be cleaned up.

**Fix**: Add health check in idle timeout cleanup:
```java
@Scheduled(fixedDelay = 60_000)
void cleanupStaleSessions() {
  sessions.values().stream()
    .filter(h -> h.state == ACTIVE && h.lastAccessed < threshold)
    .forEach(h -> {
      // Ping server or close session
    });
}
```

**Files affected**: `McpSessionRegistry.java`

---

#### 2. **Race Condition in Tool Approval**

**Issue**: If two users approve the same tool request simultaneously, tool might execute twice.

**Fix**: Add idempotency key or mark request as "processing":
```java
AtomicBoolean processing = new AtomicBoolean(false);
if (!processing.compareAndSet(false, true)) {
  return Mono.error(new AlreadyProcessedException());
}
```

**Files affected**: `ApprovalResponseController.java`, `ResponseStreamService.java`

---

#### 3. **SSE Connection Not Closed on Error**

**Issue**: Frontend SSE connection might remain open if backend throws error.

**Fix**: Ensure cleanup in `onClose` callback:
```typescript
fetchEventSource("/api/responses/stream", {
  // ...
  onclose: () => {
    // Always cleanup
    controller.abort();
  }
});
```

**Files affected**: `chatStore.ts`

---

### 📈 Architecture Improvements

#### 1. **Implement Circuit Breaker**

For MCP server connections to prevent cascading failures:
```java
@CircuitBreaker(name = "mcp-server")
public Mono<McpAsyncClient> getOrCreateSession(String serverId) {
  // ...
}
```

#### 2. **Add Rate Limiting**

For OpenAI API calls to prevent quota exhaustion:
```java
@RateLimiter(name = "openai")
public Flux<ServerSentEvent<String>> streamResponses(...) {
  // ...
}
```

#### 3. **Implement Read-Through Cache**

For MCP capabilities to reduce database load:
```java
Cache<String, McpCapabilities> capabilitiesCache = Caffeine.newBuilder()
  .expireAfterWrite(5, TimeUnit.MINUTES)
  .build();
```

#### 4. **Add GraphQL API**

For frontend to fetch nested data more efficiently:
```graphql
query GetConversation($id: ID!) {
  conversation(id: $id) {
    id
    title
    messages {
      id
      content
      toolCalls {
        name
        status
      }
    }
  }
}
```

---

## Additional Documentation

- [REQUIREMENTS.md](./REQUIREMENTS.md) - Detailed requirements for MCP async migration
- [DEPLOYMENT_GUIDE.md](./DEPLOYMENT_GUIDE.md) - Production deployment instructions
- [DOCKER_COMPOSE_GUIDE.md](./DOCKER_COMPOSE_GUIDE.md) - Docker setup guide
- [OLLAMA_LITELLM_GUIDE.md](./OLLAMA_LITELLM_GUIDE.md) - LLM setup guide

For detailed package documentation, see:
- [chatbot-backend/AGENTS.md](./chatbot-backend/AGENTS.md) - Backend package details
- [chatbot/AGENTS.md](./chatbot/AGENTS.md) - Frontend structure details

---

## Summary

This application demonstrates a modern, reactive architecture for building AI-powered chatbots with:
- ✅ Non-blocking I/O for scalability
- ✅ Dynamic tool discovery via MCP
- ✅ Streaming responses for better UX
- ✅ Security (approval system, encrypted credentials)
- ✅ Extensibility (easy to add new MCP servers/tools)

**Priority improvements**:
1. Refactor `ResponseStreamService` into smaller services
2. Standardize error handling across backend and frontend
3. Add circuit breaker for MCP connections
4. Implement caching for MCP capabilities
5. Add comprehensive monitoring and metrics

For questions or contributions, see repository maintainers.
