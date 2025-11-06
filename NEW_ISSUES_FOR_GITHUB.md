# GitHub Issues - Store Integration and Backend Improvements

This document contains new GitHub issues to be created following the frontend store refactoring work.

---

## Issue: Migrate Components to Use New Frontend Stores

**Labels**: `enhancement`, `high-priority`, `frontend`, `refactoring`

**Title**: Migrate components from chatStore to new focused stores (configStore, conversationStore, toolCallStore)

**Description**:

### Context
PR #XXX created new focused stores (`configStore`, `conversationStore`, `toolCallStore`) with >90% test coverage as preparation for improving frontend maintainability. However, components still use the original monolithic `chatStore.ts`. This issue tracks the migration work.

### Current State
- ✅ New stores created and tested (35 tests, >90% coverage)
- ✅ Testing infrastructure set up (Vitest + React Testing Library)
- ⏸️ Components still using original `chatStore` via hooks
- ⏸️ No breaking changes introduced yet

### Scope of Work
Migrate the following components to use new stores:

#### 1. Configuration Components
**Components to update:**
- `SettingsPanel.tsx` - Use `useConfigStore` for model settings
- `SystemPromptPanel.tsx` - Use `useConfigStore` for system prompt

**Changes:**
```typescript
// Before
import { useChatStore } from '../store/chatStore';
const model = useChatStore(state => state.model);
const setModel = useChatStore(state => state.setModel);

// After
import { useConfigStore } from '../store/configStore';
const model = useConfigStore(state => state.model);
const setModel = useConfigStore(state => state.setModel);
```

#### 2. Conversation Components
**Components to update:**
- `ChatSidebar.tsx` - Use `useConversationStore` for conversation list
- `App.tsx` - Use `useConversationStore` for current conversation

**Changes:**
```typescript
// Before
import { useChatStore } from '../store/chatStore';
const conversations = useChatStore(state => state.conversationSummaries);

// After
import { useConversationStore } from '../store/conversationStore';
const conversations = useConversationStore(state => state.conversationSummaries);
```

#### 3. Tool Execution Components
**Components to update:**
- `ToolCallList.tsx` - Use `useToolCallStore` for tool calls
- `ToolCallDetails.tsx` - Use `useToolCallStore` for tool details
- `UserApprovalDialog.tsx` - Use `useToolCallStore` for approvals

**Changes:**
```typescript
// Before
import { useChatStore } from '../store/chatStore';
const toolCalls = useChatStore(state => state.toolCalls);

// After
import { useToolCallStore } from '../store/toolCallStore';
const toolCalls = useToolCallStore(state => state.toolCalls);
```

#### 4. Update Hooks
**Files to update:**
- `hooks/useChatState.ts` - Refactor to use new stores
- `hooks/useChatActions.ts` - Refactor to use new stores

### Acceptance Criteria
- [ ] All components migrated to use new stores
- [ ] All hooks updated to use new stores
- [ ] Frontend builds successfully (`npm run build`)
- [ ] All TypeScript compilation passes
- [ ] All existing tests still pass
- [ ] No regression in functionality
- [ ] Performance improvement verified (reduced re-renders)
- [ ] Original `chatStore.ts` deprecated (add deprecation comments)

### Testing Plan
1. Run existing tests to ensure no regression
2. Manual testing of all affected components:
   - Settings panel (model selection, temperature)
   - Conversation list (create, load, switch)
   - Tool execution (approve/deny, view results)
3. Performance testing (check re-render counts)

### Migration Strategy
1. **Phase 1**: Migrate configuration components (low risk)
2. **Phase 2**: Migrate conversation components (medium risk)
3. **Phase 3**: Migrate tool execution components (medium risk)
4. **Phase 4**: Update hooks to consolidate
5. **Phase 5**: Deprecate old chatStore

### Effort Estimate
2-3 days

### Priority
High (completes the refactoring started in PR #XXX)

### Related
- Closes #2 (from ISSUES_TO_CREATE.md)
- Follows work from PR #XXX

---

## Issue: Fix Failing Backend Tests in ResponseStreamService

**Labels**: `bug`, `high-priority`, `backend`, `tests`

**Title**: Fix 4 failing tests in ResponseStreamServiceTest

**Description**:

### Problem
4 tests in `ResponseStreamServiceTest` are currently failing:
1. `shouldAppendToolOutputMessages()`
2. `shouldStreamEventsAndPersistUpdates()`
3. `shouldHandleMcpFailureEvent()`
4. `shouldHandleFunctionCallEvents()`

### Current Status
```
19 tests completed, 4 failed
BUILD FAILED
```

### Impact
- Backend build fails when running tests
- Cannot verify ResponseStreamService changes
- Blocks confidence in backend stability

### Root Cause Analysis Needed
The tests may be failing due to:
1. Changes in OpenAI Responses API event format
2. Outdated mock data in tests
3. Changes in conversation lifecycle tracking
4. MCP integration changes

### Investigation Steps
1. Run tests individually to see specific failures
2. Check test expectations vs current implementation
3. Review recent changes to ResponseStreamService
4. Verify mock SSE event formats match current API

### Acceptance Criteria
- [ ] All 4 failing tests fixed
- [ ] Tests pass consistently
- [ ] No business logic changes (only test updates)
- [ ] Test documentation updated if event format changed
- [ ] Backend builds successfully with tests

### Files to Review
- `chatbot-backend/src/test/java/app/chatbot/responses/ResponseStreamServiceTest.java`
- `chatbot-backend/src/main/java/app/chatbot/responses/ResponseStreamService.java`

### Effort Estimate
0.5-1 day

### Priority
High (blocking backend test suite)

---

## Issue: Create messageStore and streamingStore for Complete Store Decomposition

**Labels**: `enhancement`, `medium-priority`, `frontend`, `refactoring`

**Title**: Complete frontend store decomposition with messageStore and streamingStore

**Description**:

### Context
Following the pattern established in PR #XXX, we need to create the remaining stores to complete the decomposition of the monolithic `chatStore.ts`.

### Current State
✅ Created:
- `configStore.ts` (91.66% coverage)
- `conversationStore.ts` (78.72% coverage)
- `toolCallStore.ts` (comprehensive tests)

⏸️ Still in monolithic chatStore:
- Message management (add, update, streaming)
- SSE event handling
- Streaming state management

### Stores to Create

#### 1. messageStore.ts
**Responsibility**: Message management and display

**State:**
```typescript
interface MessageState {
  messages: ChatMessage[];
  messageIndex: Record<string, ChatMessage>; // Fast lookup
  loading: boolean;
  error: string | null;

  // Actions
  addMessage: (message: ChatMessage) => void;
  updateMessage: (id: string, updates: Partial<ChatMessage>) => void;
  appendMessageDelta: (id: string, delta: string) => void;
  clearMessages: () => void;
  loadMessagesForConversation: (conversationId: number) => Promise<void>;
}
```

**Features:**
- Add/update/delete messages
- Optimistic updates for user messages
- Message delta accumulation for streaming
- Fast O(1) lookups via index

**Tests to Include:**
- Add message
- Update message content
- Append delta during streaming
- Clear messages
- Load messages for conversation
- Error handling

**Expected Coverage:** >90%

#### 2. streamingStore.ts
**Responsibility**: SSE state and event handling

**State:**
```typescript
interface StreamingState {
  isStreaming: boolean;
  streamError?: string;
  controller: AbortController | null;
  responseId?: string | null;
  conversationStatus: ConversationStatus;
  completionReason?: string | null;
  streamingOutputs: Record<number, StreamingOutput>;

  // Actions
  startStreaming: (conversationId: number, payload: any) => Promise<void>;
  handleSseEvent: (event: ServerSentEvent) => void;
  abortStreaming: () => void;
  resetStreaming: () => void;
}
```

**Features:**
- SSE connection management
- Event parsing and routing
- Streaming lifecycle tracking
- Abort functionality
- Error handling and recovery

**Event Handlers:**
- `response.created`
- `response.text.delta`
- `response.function_call_arguments.done`
- `response.mcp_call.*`
- `response.done`
- `error`

**Tests to Include:**
- Start streaming
- Handle different SSE events
- Abort streaming
- Error handling
- State transitions

**Expected Coverage:** >90%

### Acceptance Criteria
- [ ] `messageStore.ts` created with >90% coverage
- [ ] `streamingStore.ts` created with >90% coverage
- [ ] All tests pass
- [ ] TypeScript compilation successful
- [ ] Documentation added to REFACTORING_SUMMARY.md
- [ ] Integration points with other stores documented

### Benefits
- Complete separation of concerns
- Full testability of streaming logic
- Better error isolation
- Improved performance (granular subscriptions)

### Dependencies
- Should be done after component migration (previous issue)
- Follows patterns from existing stores

### Effort Estimate
3-4 days

### Priority
Medium (completes the refactoring architecture)

---

## Issue: Backend Refactoring - Extract Services from ResponseStreamService

**Labels**: `refactoring`, `medium-priority`, `backend`, `maintainability`

**Title**: Refactor ResponseStreamService into focused services (backend equivalent of frontend work)

**Description**:

### Context
Similar to the frontend store refactoring, the backend `ResponseStreamService.java` has grown to 800+ lines with multiple responsibilities. This issue tracks breaking it down into focused services.

### Current State
**File**: `chatbot-backend/src/main/java/app/chatbot/responses/ResponseStreamService.java`
- **Size**: 800+ lines
- **Responsibilities**: 
  1. Request building
  2. SSE streaming
  3. Tool execution
  4. Conversation management
  5. Error handling

### Proposed Architecture

#### 1. ToolExecutionOrchestrator
**Responsibility**: Tool execution with approval workflow

```java
@Service
public class ToolExecutionOrchestrator {
    Mono<JsonNode> executeWithApproval(
        String serverId, 
        String toolName, 
        Map<String, Object> arguments,
        StreamState state
    );
    
    Mono<JsonNode> executeToolAsync(
        String serverId,
        String toolName, 
        Map<String, Object> arguments
    );
}
```

#### 2. SseEventParser
**Responsibility**: Parse and transform SSE events

```java
@Service
public class SseEventParser {
    ServerSentEvent<String> parseOpenAiEvent(ServerSentEvent<String> event);
    ServerSentEvent<String> mapToFrontendEvent(String eventType, JsonNode payload);
    JsonNode parseEventPayload(String data);
}
```

#### 3. ConversationStateTracker
**Responsibility**: Track and update conversation lifecycle

```java
@Service
public class ConversationStateTracker {
    Mono<Void> updateStatus(Long conversationId, ConversationStatus status);
    Mono<Void> finalizeConversation(Long conversationId, String reason);
    Mono<Void> markFailed(Long conversationId, String error);
}
```

#### 4. OpenAiRequestBuilder
**Responsibility**: Build OpenAI API requests

```java
@Service
public class OpenAiRequestBuilder {
    ObjectNode buildRequest(ResponseStreamRequest request, Conversation conversation);
    void injectTools(ObjectNode payload, List<JsonNode> tools);
    void injectConversationHistory(ObjectNode payload, List<Message> messages);
}
```

#### 5. ResponseStreamService (Refactored)
**Responsibility**: Orchestrate the streaming flow

```java
@Service
public class ResponseStreamService {
    private final ToolExecutionOrchestrator toolExecutor;
    private final SseEventParser eventParser;
    private final ConversationStateTracker stateTracker;
    private final OpenAiRequestBuilder requestBuilder;
    
    public Flux<ServerSentEvent<String>> streamResponses(
        ResponseStreamRequest request,
        String authorizationHeader
    ) {
        // Orchestrates using injected services
    }
}
```

### Benefits
- **Maintainability**: 200-line focused services vs 800-line monolith
- **Testability**: Each service can be unit tested independently
- **Reusability**: Services can be used by other controllers
- **Debugging**: Easier to isolate issues

### Acceptance Criteria
- [ ] All services extracted and created
- [ ] ResponseStreamService refactored to use new services
- [ ] All existing tests pass (or updated if needed)
- [ ] New unit tests for each service (>90% coverage)
- [ ] Backend builds successfully
- [ ] No business logic changes
- [ ] Performance benchmarks show no regression

### Testing Plan
1. Unit tests for each new service
2. Integration tests for ResponseStreamService
3. Existing ResponseStreamServiceTest should pass with minimal changes

### Migration Strategy
1. Create new service classes (start with simplest)
2. Add unit tests for each service
3. Refactor ResponseStreamService to use new services
4. Update existing tests
5. Remove old code

### Effort Estimate
4-5 days

### Priority
Medium (improves backend maintainability)

### Related
- Similar to frontend refactoring (PR #XXX)
- Addresses Issue #1 from ISSUES_TO_CREATE.md

---

## Issue: Add Integration Tests for Store Interactions

**Labels**: `testing`, `medium-priority`, `frontend`

**Title**: Add integration tests for interactions between new frontend stores

**Description**:

### Context
While individual stores have >90% unit test coverage, we need integration tests to verify stores work correctly together.

### Test Scenarios

#### 1. Create Conversation → Load Messages
```typescript
test('should create conversation and load initial messages', async () => {
  const conversationStore = useConversationStore.getState();
  const messageStore = useMessageStore.getState();
  
  // Create conversation
  const id = await conversationStore.createConversation('Test');
  
  // Load messages
  await messageStore.loadMessagesForConversation(id);
  
  expect(messageStore.messages).toHaveLength(0); // New conversation
});
```

#### 2. Send Message → Streaming → Tool Execution
```typescript
test('should handle message streaming with tool execution', async () => {
  const streamingStore = useStreamingStore.getState();
  const toolCallStore = useToolCallStore.getState();
  const messageStore = useMessageStore.getState();
  
  // Start streaming
  await streamingStore.startStreaming(1, payload);
  
  // Verify tool call appears
  await waitFor(() => {
    expect(toolCallStore.toolCalls).toHaveLength(1);
  });
  
  // Approve tool
  await toolCallStore.approveToolExecution(approvalId, true);
  
  // Verify final message
  await waitFor(() => {
    expect(messageStore.messages.at(-1)?.role).toBe('assistant');
  });
});
```

#### 3. Configuration Changes → Stream Restart
```typescript
test('should use new config when starting stream', async () => {
  const configStore = useConfigStore.getState();
  const streamingStore = useStreamingStore.getState();
  
  // Change model
  configStore.setModel('gpt-4-turbo');
  
  // Start stream
  await streamingStore.startStreaming(1, payload);
  
  // Verify request uses new model
  expect(mockFetch).toHaveBeenCalledWith(
    expect.anything(),
    expect.objectContaining({
      body: expect.stringContaining('gpt-4-turbo')
    })
  );
});
```

### Acceptance Criteria
- [ ] Integration tests for all major workflows
- [ ] Tests cover store interactions
- [ ] Tests use realistic scenarios
- [ ] All integration tests pass
- [ ] Documentation of test patterns

### Effort Estimate
2-3 days

### Priority
Medium (ensures stores work together correctly)

---

## Summary

**Total New Issues**: 5

**Priority Breakdown**:
- High: 2 issues (component migration, test fixes)
- Medium: 3 issues (remaining stores, backend refactoring, integration tests)

**Estimated Total Effort**: 12-17 days

**Recommended Order**:
1. Fix failing backend tests (blocker)
2. Migrate components to new stores (completes frontend refactoring)
3. Create messageStore and streamingStore (completes architecture)
4. Add integration tests (ensures quality)
5. Backend refactoring (parallel work)
