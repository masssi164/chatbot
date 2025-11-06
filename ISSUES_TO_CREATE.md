# GitHub Issues to Create

This document contains all issues to be created from the code quality review. Please create these issues manually or use the GitHub CLI commands below.

---

## Group 1: Code Refactoring (Complexity Reduction)

### Issue 1: Refactor ResponseStreamService - Extract into focused services

**Labels**: `refactoring`, `high-priority`, `backend`, `maintainability`

**Description**:
```markdown
## Problem
**Location**: `chatbot-backend/src/main/java/app/chatbot/responses/ResponseStreamService.java`

The ResponseStreamService class has grown to 800+ lines with multiple responsibilities, making it hard to maintain, test, and debug.

**Impact**: High
- Increases onboarding time for new developers
- Difficult to add features without introducing bugs
- Hard to isolate failures in production

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 17-61
- chatbot-backend/src/main/java/app/chatbot/responses/AGENTS.md lines 1-700

## Proposed Solution
Extract into smaller, focused services:

```java
// Extract tool execution logic
class ToolExecutionOrchestrator {
  Mono<ToolResult> executeWithApproval(...)
  Mono<ToolResult> executeToolAsync(...)
}

// Extract SSE event handling
class SseEventParser {
  ServerSentEvent<String> parseOpenAiEvent(...)
  ServerSentEvent<String> mapToFrontendEvent(...)
}

// Extract state tracking
class ConversationStateTracker {
  Mono<Void> updateStatus(...)
  Mono<Void> finalizeConversation(...)
}

// Extract request building
class OpenAiRequestBuilder {
  ObjectNode buildRequest(...)
  void injectTools(ObjectNode payload)
}
```

## Acceptance Criteria
- [ ] Java builds successfully (`./gradlew build`)
- [ ] All existing tests pass
- [ ] No business logic changes
- [ ] Each extracted service has clear responsibility
- [ ] Code complexity reduced (maintainability improved)

**Effort**: 3-5 days
**Priority**: High
```

---

### Issue 2: Refactor Frontend chatStore - Split into focused stores

**Labels**: `refactoring`, `high-priority`, `frontend`, `maintainability`

**Description**:
```markdown
## Problem
**Location**: `chatbot/src/store/chatStore.ts`

The chatStore has grown to 1000+ lines with multiple responsibilities, making it hard to maintain and prone to re-render issues.

**Impact**: High
- Hard to understand data flow
- Difficult to test individual features
- Prone to re-render issues

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 64-106
- chatbot/src/store/AGENTS.md lines 1-750

## Proposed Solution
Split into multiple focused stores:

```typescript
// conversationStore.ts - CRUD for conversations
const useConversationStore = create<ConversationState>(...);

// messageStore.ts - Message management
const useMessageStore = create<MessageState>(...);

// streamingStore.ts - SSE state and event handling
const useStreamingStore = create<StreamingState>(...);

// toolCallStore.ts - Tool execution tracking
const useToolCallStore = create<ToolCallState>(...);

// configStore.ts - LLM configuration
const useConfigStore = create<ConfigStore>(...);
```

## Benefits
- Smaller, easier to understand modules
- Better testability
- Reduced re-renders (components only subscribe to what they need)
- Easier to add features

## Acceptance Criteria
- [ ] npm build succeeds (`npm run build`)
- [ ] All TypeScript compilation passes
- [ ] No business logic changes
- [ ] Each store has clear responsibility
- [ ] Components updated to use new stores

**Effort**: 2-3 days
**Priority**: High
```

---

## Group 2: Bug Fixes & Race Conditions

### Issue 3: Fix Race Condition in McpSessionRegistry

**Labels**: `bug`, `high-priority`, `backend`, `concurrency`

**Description**:
```markdown
## Problem
**Location**: `chatbot-backend/src/main/java/app/chatbot/mcp/McpSessionRegistry.java`

Session initialization might race between multiple concurrent requests. Two threads calling `getOrCreateSession(serverId)` simultaneously might both see INITIALIZING state and wait indefinitely if initialization fails.

**Impact**: Medium-High
- Can cause deadlocks in high-concurrency scenarios
- Affects user experience (timeout errors)

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 107-139
- chatbot-backend/src/main/java/app/chatbot/mcp/AGENTS.md lines 66-165

## Proposed Solution
Use `Mono.cache()` for initialization to ensure single execution:

```java
Mono<McpAsyncClient> initMono = initializeClient(serverId).cache();
holder.initializationMono = initMono;

return initMono.flatMap(client -> {
    holder.client = client;
    holder.state.set(ACTIVE);
    return Mono.just(client);
});
```

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] Concurrent access tests added
- [ ] No deadlocks under high concurrency
- [ ] Initialization happens exactly once per server

**Effort**: 1 day
**Priority**: High
```

---

### Issue 4: Fix Memory Leak in Failed MCP Sessions

**Labels**: `bug`, `medium-priority`, `backend`, `memory-leak`

**Description**:
```markdown
## Problem
**Location**: `chatbot-backend/src/main/java/app/chatbot/mcp/McpSessionRegistry.java`

If MCP server disconnects unexpectedly, session might remain in ACTIVE state and never be cleaned up, causing memory leak.

**Impact**: Medium
- Memory consumption increases over time
- Can cause OutOfMemoryError in long-running applications

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 142-171
- chatbot-backend/src/main/java/app/chatbot/mcp/AGENTS.md lines 50-64

## Proposed Solution
Add health check in scheduled cleanup:

```java
@Scheduled(fixedDelay = 60_000)
void cleanupStaleSessions() {
    sessions.values().stream()
        .filter(h -> h.state == ACTIVE && isStale(h))
        .forEach(h -> {
            log.warn("Cleaning up stale session: {}", h.serverId);
            closeSession(h.serverId).subscribe();
        });
}

private boolean isStale(SessionHolder holder) {
    return Duration.between(holder.lastAccessedAt, Instant.now())
        .compareTo(IDLE_TIMEOUT) > 0;
}
```

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] Scheduled cleanup job added
- [ ] Stale sessions are detected and closed
- [ ] Memory usage stays stable over time

**Effort**: 0.5 days
**Priority**: Medium
```

---

### Issue 5: Fix Tool Approval Race Condition

**Labels**: `bug`, `medium-priority`, `backend`, `concurrency`

**Description**:
```markdown
## Problem
**Location**: `chatbot-backend/src/main/java/app/chatbot/responses/ResponseStreamService.java`

If two users approve the same tool request simultaneously (unlikely but possible in shared sessions), tool might execute twice.

**Impact**: Medium
- Duplicate tool executions
- Inconsistent state

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 246-267

## Proposed Solution
Add idempotency check:

```java
AtomicBoolean processing = new AtomicBoolean(false);
if (!processing.compareAndSet(false, true)) {
    return Mono.error(new AlreadyProcessedException());
}
```

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] Idempotency tests added
- [ ] Tool executes exactly once per approval
- [ ] Second approval returns appropriate error

**Effort**: 0.5 days
**Priority**: Medium
```

---

## Group 3: Production Readiness

### Issue 6: Add Circuit Breaker for MCP Connections

**Labels**: `enhancement`, `medium-priority`, `backend`, `resilience`

**Description**:
```markdown
## Problem
**Location**: `chatbot-backend/src/main/java/app/chatbot/mcp/McpClientService.java`

Repeated failures to MCP servers can cascade and affect entire application. No circuit breaker protection.

**Impact**: Medium
- Cascading failures
- Poor resilience

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 172-207
- chatbot-backend/src/main/java/app/chatbot/mcp/AGENTS.md lines 14-151

## Proposed Solution
Add Resilience4j circuit breaker:

```java
@CircuitBreaker(name = "mcp-server", fallbackMethod = "fallbackListTools")
public Mono<List<McpSchema.Tool>> listToolsAsync(String serverId) {
    // Existing logic
}

private Mono<List<McpSchema.Tool>> fallbackListTools(String serverId, Exception ex) {
    log.warn("Circuit breaker activated for server {}", serverId);
    return Mono.just(List.of());
}
```

**Configuration**:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      mcp-server:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
```

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] Resilience4j dependency added
- [ ] Circuit breaker configured
- [ ] Fallback methods implemented
- [ ] Tests for circuit breaker behavior

**Effort**: 1 day
**Priority**: Medium
```

---

### Issue 7: Add In-Memory Caching for MCP Capabilities

**Labels**: `enhancement`, `medium-priority`, `backend`, `performance`

**Description**:
```markdown
## Problem
**Location**: `chatbot-backend/src/main/java/app/chatbot/mcp/McpServerService.java`

Every tool call loads capabilities from database, even though capabilities are already cached in DB. No in-memory cache layer.

**Impact**: Medium
- Unnecessary database load
- Slower response times

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 208-245
- chatbot-backend/src/main/java/app/chatbot/mcp/AGENTS.md lines 203-340

## Proposed Solution
Add Spring Cache for in-memory caching:

```java
@Cacheable(value = "mcp-capabilities", key = "#serverId")
public Mono<List<Tool>> getTools(String serverId) {
    return serverRepository.findByServerId(serverId)
        .map(server -> parseToolsCache(server.getToolsCache()));
}

@CacheEvict(value = "mcp-capabilities", key = "#serverId")
public Mono<Void> syncCapabilities(String serverId) {
    // Invalidate cache after sync
}
```

**Configuration**:
```java
@EnableCaching
@Configuration
public class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("mcp-capabilities");
    }
}
```

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] Spring Cache configured
- [ ] Cache invalidation on sync
- [ ] Performance improvement measured
- [ ] No business logic changes

**Effort**: 0.5 days
**Priority**: Medium
```

---

### Issue 8: Add Observability and Metrics

**Labels**: `enhancement`, `high-priority`, `backend`, `monitoring`

**Description**:
```markdown
## Problem
No metrics or observability in production. Hard to monitor application health and performance.

**Impact**: High for production monitoring
- Cannot track performance issues
- No alerting on failures
- Difficult to troubleshoot production issues

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 402-437

## Proposed Solution
Add Micrometer metrics:

```java
@Timed("mcp.tool.call")
public Mono<CallToolResult> callToolAsync(...) {
    // ...
}

// Metrics to track:
// - mcp.tool.call.duration (histogram)
// - mcp.session.active.count (gauge)
// - chat.message.streaming.duration (histogram)
// - streaming.errors (counter)
```

**Configuration**:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus, health, metrics
  metrics:
    export:
      prometheus:
        enabled: true
```

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] Micrometer dependency added
- [ ] Key metrics instrumented
- [ ] Prometheus endpoint available
- [ ] Documentation updated

**Effort**: 2 days
**Priority**: High (for production)
```

---

## Group 4: Code Quality & Standards

### Issue 9: Move Hardcoded Timeouts to Configuration

**Labels**: `technical-debt`, `low-priority`, `backend`, `configuration`

**Description**:
```markdown
## Problem
**Locations**: 
- `chatbot-backend/src/main/java/app/chatbot/mcp/McpSessionRegistry.java`
- `chatbot-backend/src/main/java/app/chatbot/mcp/McpClientService.java`
- `chatbot-backend/src/main/java/app/chatbot/responses/ResponseStreamService.java`

Timeouts are hardcoded (15s, 30s, etc.) instead of configurable.

**Impact**: Low
- Hard to adjust timeouts without code changes
- Different environments might need different values

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 268-289

## Proposed Solution
Move to configuration properties:

```java
@ConfigurationProperties("app.mcp")
public class McpProperties {
    Duration initializationTimeout = Duration.ofSeconds(10);
    Duration operationTimeout = Duration.ofSeconds(15);
    Duration idleTimeout = Duration.ofMinutes(30);
}
```

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] All timeouts externalized to application.properties
- [ ] No hardcoded timeout values remain
- [ ] Documentation updated

**Effort**: 0.5 days
**Priority**: Low
```

---

### Issue 10: Add Input Validation to Controllers

**Labels**: `technical-debt`, `low-priority`, `backend`, `validation`

**Description**:
```markdown
## Problem
**Locations**: Multiple controllers

Some endpoints don't validate input (e.g., tool arguments, server URLs).

**Impact**: Low
- Invalid data can reach service layer
- Poor error messages
- Potential security issues

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 290-317

## Proposed Solution
Add validation annotations:

```java
@PostMapping("/tools/execute")
public Mono<ToolResult> executeTool(
    @Valid @RequestBody ToolExecutionRequest request
) {
    // ...
}

public record ToolExecutionRequest(
    @NotBlank String serverId,
    @NotBlank String toolName,
    @NotNull Map<String, Object> arguments
) {}
```

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] Validation annotations added to all DTOs
- [ ] Proper error messages for validation failures
- [ ] Tests for validation

**Effort**: 1 day
**Priority**: Low
```

---

### Issue 11: Standardize Error Handling

**Labels**: `technical-debt`, `low-priority`, `backend`, `frontend`

**Description**:
```markdown
## Problem
**Locations**: Multiple services and controllers

Inconsistent error handling - some throw `ResponseStatusException`, others return `Mono.error()`, frontend sometimes catches, sometimes doesn't.

**Impact**: Low
- Inconsistent error responses
- Hard to debug errors
- Poor user experience

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 318-359

## Proposed Solution

**Backend**:
```java
@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(McpClientException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleMcpException(McpClientException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(new ErrorResponse(ex.getMessage(), "MCP_ERROR")));
    }
}
```

**Frontend**:
```typescript
// Global error handler
window.addEventListener("unhandledrejection", (event) => {
    console.error("Unhandled promise rejection:", event.reason);
    // Show toast notification
});

// Error boundary
<ErrorBoundary fallback={<ErrorScreen />}>
  <App />
</ErrorBoundary>
```

## Acceptance Criteria
- [ ] Java and npm builds succeed
- [ ] Global exception handler implemented
- [ ] Consistent error response format
- [ ] Frontend error boundary added
- [ ] No business logic changes

**Effort**: 2 days
**Priority**: Low
```

---

### Issue 12: Remove Duplicate SSE Event Parsing Logic

**Labels**: `technical-debt`, `low-priority`, `backend`, `frontend`

**Description**:
```markdown
## Problem
**Locations**: 
- `chatbot-backend/src/main/java/app/chatbot/responses/ResponseStreamService.java`
- `chatbot/src/store/chatStore.ts`

Both backend and frontend parse SSE events with similar validation logic, leading to duplication.

**Impact**: Low
- Code duplication
- Easy to get out of sync

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 360-380

## Proposed Solution
Create shared event types (use TypeScript codegen from OpenAPI spec):

```typescript
// Auto-generated from backend OpenAPI spec
export interface SseEvent {
  event: "init" | "message" | "tool_call_update" | "error" | "done";
  data: InitData | MessageData | ToolCallData | ErrorData | DoneData;
}
```

## Acceptance Criteria
- [ ] Builds succeed
- [ ] OpenAPI spec includes SSE event types
- [ ] Frontend types auto-generated
- [ ] No duplication in event definitions

**Effort**: 1 day
**Priority**: Low
```

---

### Issue 13: Remove Duplicate Conversation Status Enums

**Labels**: `technical-debt`, `low-priority`, `backend`, `frontend`

**Description**:
```markdown
## Problem
**Locations**: 
- `chatbot-backend/src/main/java/app/chatbot/conversation/ConversationStatus.java`
- `chatbot/src/services/apiClient.ts`

Status enum defined twice, easy to get out of sync.

**Impact**: Low
- Code duplication
- Type safety issues

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 381-399

## Proposed Solution
Generate frontend types from backend:

```bash
# Use openapi-typescript or similar
npm install -D openapi-typescript
npx openapi-typescript http://localhost:8080/v3/api-docs -o src/types/api.ts
```

## Acceptance Criteria
- [ ] Builds succeed
- [ ] Frontend types auto-generated from backend
- [ ] No duplicate type definitions
- [ ] CI/CD includes type generation

**Effort**: 0.5 days
**Priority**: Low
```

---

## Group 5: Documentation

### Issue 14: Add Architecture Decision Records (ADRs)

**Labels**: `documentation`, `medium-priority`

**Description**:
```markdown
## Problem
Key architectural decisions are not documented, making it hard for new developers to understand why certain choices were made.

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 511-523

## Proposed Solution
Document key decisions:
- Why MCP over custom tool system?
- Why Zustand over Redux?
- Why R2DBC over JPA?
- Why SSE over WebSocket?

Create ADR documents in `docs/adr/` directory.

## Acceptance Criteria
- [ ] ADR directory structure created
- [ ] Key decisions documented
- [ ] ADR template created for future decisions
- [ ] Referenced in main README

**Effort**: 1-2 days
**Priority**: Medium
```

---

### Issue 15: Add Sequence Diagrams for Complex Flows

**Labels**: `documentation`, `medium-priority`

**Description**:
```markdown
## Problem
Complex flows (chat with tool execution, MCP session initialization, approval workflow) are only described in text, making them hard to understand.

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 524-537

## Proposed Solution
Create diagrams for complex flows:
- Chat message with tool execution flow
- MCP session initialization flow
- Approval workflow flow

Use Mermaid, PlantUML, or draw.io.

## Acceptance Criteria
- [ ] Diagrams created for 3 main flows
- [ ] Embedded in AGENTS.md files
- [ ] Diagrams are clear and accurate
- [ ] Source files committed to repository

**Effort**: 1-2 days
**Priority**: Medium
```

---

## Group 6: Testing

### Issue 16: Add Frontend Unit Tests

**Labels**: `testing`, `high-priority`, `frontend`

**Description**:
```markdown
## Problem
**Location**: `chatbot/src/` (entire frontend)

Currently no frontend tests exist. This makes refactoring risky and increases chance of regressions.

**Impact**: High
- No safety net for refactoring
- Regressions can go unnoticed
- Low confidence in code changes

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 540-560

## Proposed Solution
Add unit tests with Vitest:

```typescript
import { render, screen } from "@testing-library/react";
import { ChatInput } from "./ChatInput";

test("renders input field", () => {
  render(<ChatInput onSubmit={() => {}} />);
  const input = screen.getByPlaceholderText("Type your message...");
  expect(input).toBeInTheDocument();
});
```

## Acceptance Criteria
- [ ] npm build succeeds
- [ ] Vitest configured
- [ ] Tests for key components (ChatInput, ChatHistory, etc.)
- [ ] Tests for stores (chatStore, mcpServerStore)
- [ ] CI/CD runs tests

**Effort**: 5+ days
**Priority**: High
```

---

### Issue 17: Add E2E Tests

**Labels**: `testing`, `high-priority`, `e2e`

**Description**:
```markdown
## Problem
No end-to-end tests exist. Manual testing is time-consuming and error-prone.

**Impact**: High
- No automated verification of user flows
- Regressions in critical paths can go unnoticed
- Time-consuming manual testing

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 561-580

## Proposed Solution
Add Playwright tests:

```typescript
test("user can send message and receive response", async ({ page }) => {
  await page.goto("http://localhost:5173");
  await page.fill('[placeholder="Type your message..."]', "Hello");
  await page.click("button:has-text('Send')");
  await page.waitForSelector(".message.assistant");
  expect(await page.textContent(".message.assistant")).toContain("Hi");
});
```

## Acceptance Criteria
- [ ] Playwright configured
- [ ] E2E tests for critical user flows
  - Send message and receive response
  - Create new conversation
  - Execute tool with approval
  - MCP server management
- [ ] CI/CD runs E2E tests

**Effort**: 3-5 days
**Priority**: High
```

---

## Group 7: Performance Optimization

### Issue 18: Add GraphQL API for Better Frontend Performance

**Labels**: `enhancement`, `medium-priority`, `backend`, `performance`

**Description**:
```markdown
## Problem
Frontend makes multiple REST calls to fetch nested data (conversation + messages + tool calls).

**Impact**: Medium-High
- Multiple network requests
- Over-fetching
- Poor performance

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 438-477

## Proposed Solution
Add Spring GraphQL:

```graphql
query GetConversation($id: ID!) {
  conversation(id: $id) {
    id
    title
    status
    messages {
      id
      content
      role
      createdAt
    }
    toolCalls {
      id
      name
      status
      result
    }
  }
}
```

**Benefits**:
- Single request for nested data
- Reduced over-fetching
- Better frontend performance

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] Spring GraphQL dependency added
- [ ] GraphQL schema defined
- [ ] Resolvers implemented
- [ ] Frontend updated to use GraphQL
- [ ] No business logic changes

**Effort**: 3-5 days
**Priority**: Medium
```

---

### Issue 19: Optimize Database Queries with Batch Loading

**Labels**: `enhancement`, `low-priority`, `backend`, `performance`

**Description**:
```markdown
## Problem
**Location**: `chatbot-backend/src/main/java/app/chatbot/conversation/ConversationService.java`

`getConversationWithDetails()` makes multiple queries instead of using batch loading or joins.

**Impact**: Low
- N+1 query problem
- Slower response times

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 581-602

## Proposed Solution
Use R2DBC batch queries or joins:

```java
public Mono<ConversationDetailDto> getConversationWithDetails(Long id) {
    return conversationRepository.findById(id)
        .zipWith(messageRepository.findByConversationId(id).collectList())
        .zipWith(toolCallRepository.findByConversationId(id).collectList())
        .map(tuple -> buildDetailDto(tuple.getT1().getT1(), tuple.getT1().getT2(), tuple.getT2()));
}
```

## Acceptance Criteria
- [ ] Java builds successfully
- [ ] Query count reduced
- [ ] Performance improvement measured
- [ ] No business logic changes

**Effort**: 1 day
**Priority**: Low
```

---

### Issue 20: Add Pagination to Conversation List

**Labels**: `enhancement`, `medium-priority`, `backend`, `frontend`

**Description**:
```markdown
## Problem
**Location**: `chatbot-backend/src/main/java/app/chatbot/conversation/ConversationController.java`

`GET /api/conversations` returns all conversations without pagination. Could be thousands of records.

**Impact**: Medium
- Poor performance with many conversations
- High memory usage
- Slow page load

**Reference**: 
- CODE_QUALITY_REVIEW.md lines 603-633

## Proposed Solution

**Backend**:
```java
@GetMapping
public Flux<ConversationSummaryDto> list(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size
) {
    return conversationService.listConversations(
        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"))
    );
}
```

**Frontend**:
```typescript
const { data, fetchNextPage } = useInfiniteQuery({
  queryKey: ["conversations"],
  queryFn: ({ pageParam = 0 }) => apiClient.getConversations(pageParam, 20),
  getNextPageParam: (lastPage, pages) => lastPage.hasNext ? pages.length : undefined
});
```

## Acceptance Criteria
- [ ] Java and npm builds succeed
- [ ] Pagination implemented on backend
- [ ] Infinite scroll on frontend
- [ ] Performance improvement with large datasets
- [ ] No business logic changes

**Effort**: 1-2 days
**Priority**: Medium
```

---

## Summary

**Total Issues**: 20 (excluding rate limiting per user request)

**Grouped by Priority**:
- High Priority: 6 issues (1, 2, 3, 8, 16, 17)
- Medium Priority: 8 issues (4, 5, 6, 7, 14, 15, 18, 20)
- Low Priority: 6 issues (9, 10, 11, 12, 13, 19)

**Grouped by Type**:
- Refactoring: 2 issues
- Bug Fixes: 3 issues
- Production Readiness: 3 issues
- Code Quality: 5 issues
- Documentation: 2 issues
- Testing: 2 issues
- Performance: 3 issues

**Total Estimated Effort**: 26-38 days

---

## Instructions to Create Issues

### Option 1: Manual Creation
Copy each issue's content and create manually on GitHub.

### Option 2: Using GitHub CLI (if GH_TOKEN available)
```bash
# Set token first
export GH_TOKEN="your-github-token"

# Then run commands for each issue
gh issue create --title "..." --label "..." --body "..."
```

### Option 3: Using Script
A script can be created to automate this if needed.
