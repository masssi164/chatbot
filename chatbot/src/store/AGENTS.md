# Store Package - Zustand State Management

## Overview

This package contains **Zustand stores** for global state management in the React application.

**Why Zustand?**
- Lightweight (no providers, no boilerplate)
- Simple API (`create()`, `useStore()`)
- TypeScript-friendly (full type inference)
- No re-render issues (granular selectors)
- DevTools support (Redux DevTools compatible)

---

## Store Architecture

### Pattern

```typescript
const useStore = create<State & Actions>((set, get) => ({
  // State
  items: [],
  loading: false,
  error: null,
  
  // Sync actions (mutations)
  setItems: (items) => set({ items }),
  addItem: (item) => set(state => ({
    items: [...state.items, item]
  })),
  
  // Async actions
  fetchItems: async () => {
    set({ loading: true, error: null });
    try {
      const items = await apiClient.getItems();
      set({ items, loading: false });
    } catch (error) {
      set({ error: error.message, loading: false });
    }
  }
}));
```

### Usage in Components

```typescript
// Select specific state (component only re-renders when this changes)
const items = useStore(state => state.items);
const loading = useStore(state => state.loading);

// Select actions (never causes re-render)
const fetchItems = useStore(state => state.fetchItems);

// Or use custom hooks for better organization
const { items, loading } = useChatState();
const { fetchItems } = useChatActions();
```

---

## Store: `chatStore.ts` ⭐

**File**: `store/chatStore.ts` (1000+ lines)

**Purpose**: Manages chat conversations, messages, streaming, and tool execution.

### State Interface

```typescript
interface ChatState {
  // Conversation
  conversationId: number | null;
  conversationTitle: string | null;
  conversationSummaries: ConversationSummary[];
  
  // Messages
  messages: ChatMessage[];
  
  // Tool execution
  toolCalls: ToolCallState[];
  toolCallIndex: Record<string, ToolCallState>; // Fast lookup
  pendingApprovalRequest: ApprovalRequest | null;
  
  // Streaming state
  isStreaming: boolean;
  streamError?: string;
  controller: AbortController | null;
  streamingOutputs: Record<number, {
    messageId: string;
    itemId?: string | null;
  }>;
  
  // Response lifecycle (matches backend ConversationStatus)
  responseId?: string | null;
  conversationStatus: ConversationStatus;
  completionReason?: string | null;
  
  // LLM configuration
  model: string;
  availableModels: string[];
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  presencePenalty?: number;
  frequencyPenalty?: number;
  systemPrompt?: string;
  
  // Actions (see below)
  ensureConversation: () => Promise<void>;
  loadConversations: () => Promise<void>;
  loadConversation: (id: number) => Promise<void>;
  sendMessage: (content: string) => Promise<void>;
  abortStreaming: () => void;
  reset: () => void;
  fetchModels: () => Promise<void>;
  setModel: (model: string) => void;
  // ... more actions
}
```

---

### Key Actions

#### `ensureConversation()`

**Purpose**: Ensures a conversation exists (creates new if none).

```typescript
ensureConversation: async () => {
  const { conversationId } = get();
  if (conversationId) return; // Already exists
  
  try {
    const conversation = await apiClient.createConversation();
    set({ 
      conversationId: conversation.id,
      conversationTitle: conversation.title,
      messages: [],
      toolCalls: []
    });
  } catch (error) {
    console.error("Failed to create conversation:", error);
  }
}
```

---

#### `loadConversations()`

**Purpose**: Fetches list of all conversations.

```typescript
loadConversations: async () => {
  try {
    const summaries = await apiClient.getConversations();
    set({ conversationSummaries: summaries });
  } catch (error) {
    console.error("Failed to load conversations:", error);
  }
}
```

---

#### `loadConversation(conversationId: number)`

**Purpose**: Loads a specific conversation with messages and tool calls.

```typescript
loadConversation: async (conversationId: number) => {
  try {
    const conversation = await apiClient.getConversation(conversationId);
    
    // Transform DTOs to UI models
    const messages: ChatMessage[] = conversation.messages.map(msg => ({
      id: msg.id.toString(),
      role: msg.role.toLowerCase() as ChatRole,
      content: msg.content || "",
      createdAt: new Date(msg.createdAt).getTime(),
      rawJson: msg.rawJson,
      outputIndex: msg.outputIndex,
      itemId: msg.itemId
    }));
    
    const toolCalls: ToolCallState[] = conversation.toolCalls.map(tc => ({
      itemId: tc.itemId,
      name: tc.name,
      type: tc.type.toLowerCase() as "function" | "mcp",
      status: tc.status.toLowerCase() as "in_progress" | "completed" | "failed",
      arguments: tc.arguments,
      result: tc.result,
      outputIndex: tc.outputIndex,
      error: tc.error,
      updatedAt: new Date(tc.updatedAt).getTime()
    }));
    
    set({
      conversationId: conversation.id,
      conversationTitle: conversation.title,
      messages,
      toolCalls,
      toolCallIndex: Object.fromEntries(
        toolCalls.map(tc => [tc.itemId, tc])
      )
    });
  } catch (error) {
    console.error("Failed to load conversation:", error);
  }
}
```

---

#### `sendMessage(content: string)` ⭐ **Most Complex**

**Purpose**: Sends user message and streams response from backend.

**Flow**:
```typescript
sendMessage: async (content: string) => {
  const {
    conversationId,
    conversationTitle,
    model,
    temperature,
    maxTokens,
    systemPrompt,
    messages
  } = get();
  
  // 1. Validate
  if (!content.trim()) return;
  if (get().isStreaming) {
    console.warn("Already streaming");
    return;
  }
  
  // 2. Optimistic update (add user message immediately)
  const userMessage: ChatMessage = {
    id: `temp-${Date.now()}`,
    role: "user",
    content: content.trim(),
    createdAt: Date.now()
  };
  set(state => ({
    messages: [...state.messages, userMessage],
    isStreaming: true,
    streamError: undefined
  }));
  
  // 3. Build OpenAI request payload
  const payload = {
    model,
    messages: [
      ...(systemPrompt ? [{ role: "system", content: systemPrompt }] : []),
      ...messages.map(m => ({ role: m.role, content: m.content })),
      { role: "user", content: content.trim() }
    ],
    temperature,
    max_tokens: maxTokens,
    stream: true
  };
  
  // 4. Start SSE connection
  const controller = new AbortController();
  set({ controller });
  
  try {
    await fetchEventSource("/api/responses/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        conversationId,
        title: conversationTitle,
        payload
      }),
      signal: controller.signal,
      
      // Event handlers
      onopen: async (response) => {
        if (response.ok) {
          console.log("SSE connection opened");
        } else {
          throw new Error(`HTTP ${response.status}: ${await response.text()}`);
        }
      },
      
      onmessage: (event) => {
        handleSseEvent(event, get, set);
      },
      
      onerror: (error) => {
        console.error("SSE error:", error);
        set({
          isStreaming: false,
          streamError: error.message || "Stream error",
          controller: null
        });
        throw error; // Stop retrying
      },
      
      onclose: () => {
        console.log("SSE connection closed");
        set({ isStreaming: false, controller: null });
      }
    });
  } catch (error) {
    console.error("Failed to send message:", error);
    set({
      isStreaming: false,
      streamError: error instanceof Error ? error.message : "Unknown error",
      controller: null
    });
  }
}
```

---

#### SSE Event Handling: `handleSseEvent()`

**Purpose**: Parses and handles SSE events from backend.

```typescript
function handleSseEvent(
  event: EventSourceMessage,
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void
) {
  const eventType = event.event;
  
  try {
    const data = JSON.parse(event.data);
    
    switch (eventType) {
      case "init":
        handleInitEvent(data, set);
        break;
      
      case "conversation_status":
        handleStatusEvent(data, set);
        break;
      
      case "message":
        handleMessageEvent(data, get, set);
        break;
      
      case "tool_call_update":
        handleToolCallEvent(data, get, set);
        break;
      
      case "approval_required":
        handleApprovalRequiredEvent(data, set);
        break;
      
      case "error":
        handleErrorEvent(data, set);
        break;
      
      case "done":
        handleDoneEvent(data, set);
        break;
      
      default:
        console.warn("Unknown event type:", eventType);
    }
  } catch (error) {
    console.error("Failed to parse SSE event:", error);
  }
}
```

---

#### Event Handlers

**`handleInitEvent`**:
```typescript
function handleInitEvent(data: any, set: SetState) {
  set({
    conversationId: data.conversationId,
    responseId: data.responseId,
    conversationStatus: "STREAMING"
  });
}
```

**`handleMessageEvent`** (text delta):
```typescript
function handleMessageEvent(
  data: { delta: string; itemId: string; outputIndex: number },
  get: GetState,
  set: SetState
) {
  const { messages, streamingOutputs } = get();
  
  // Find or create message
  let output = streamingOutputs[data.outputIndex];
  if (!output) {
    // Create new message
    const messageId = `msg-${Date.now()}-${data.outputIndex}`;
    const newMessage: ChatMessage = {
      id: messageId,
      role: "assistant",
      content: data.delta,
      createdAt: Date.now(),
      itemId: data.itemId,
      outputIndex: data.outputIndex,
      streaming: true
    };
    
    set({
      messages: [...messages, newMessage],
      streamingOutputs: {
        ...streamingOutputs,
        [data.outputIndex]: { messageId, itemId: data.itemId }
      }
    });
  } else {
    // Append to existing message
    const updatedMessages = messages.map(msg =>
      msg.id === output.messageId
        ? { ...msg, content: msg.content + data.delta }
        : msg
    );
    set({ messages: updatedMessages });
  }
}
```

**`handleToolCallEvent`**:
```typescript
function handleToolCallEvent(
  data: {
    itemId: string;
    name?: string;
    type: "function" | "mcp";
    status: "in_progress" | "completed" | "failed";
    result?: string;
    error?: string;
    outputIndex?: number;
  },
  get: GetState,
  set: SetState
) {
  const { toolCalls, toolCallIndex } = get();
  
  // Upsert tool call
  const existingIndex = toolCalls.findIndex(tc => tc.itemId === data.itemId);
  
  if (existingIndex >= 0) {
    // Update existing
    const updatedToolCalls = [...toolCalls];
    updatedToolCalls[existingIndex] = {
      ...updatedToolCalls[existingIndex],
      ...data,
      updatedAt: Date.now()
    };
    
    set({
      toolCalls: updatedToolCalls,
      toolCallIndex: {
        ...toolCallIndex,
        [data.itemId]: updatedToolCalls[existingIndex]
      }
    });
  } else {
    // Create new
    const newToolCall: ToolCallState = {
      itemId: data.itemId,
      name: data.name,
      type: data.type,
      status: data.status,
      result: data.result,
      error: data.error,
      outputIndex: data.outputIndex,
      updatedAt: Date.now()
    };
    
    set({
      toolCalls: [...toolCalls, newToolCall],
      toolCallIndex: {
        ...toolCallIndex,
        [data.itemId]: newToolCall
      }
    });
  }
}
```

**`handleApprovalRequiredEvent`**:
```typescript
function handleApprovalRequiredEvent(
  data: {
    approvalRequestId: string;
    serverLabel: string;
    toolName: string;
    arguments?: string;
  },
  set: SetState
) {
  set({
    pendingApprovalRequest: {
      approvalRequestId: data.approvalRequestId,
      serverLabel: data.serverLabel,
      toolName: data.toolName,
      arguments: data.arguments
    }
  });
}
```

**`handleDoneEvent`**:
```typescript
function handleDoneEvent(
  data: { status: ConversationStatus; completionReason?: string },
  set: SetState
) {
  set({
    conversationStatus: data.status,
    completionReason: data.completionReason,
    isStreaming: false,
    controller: null,
    // Clear streaming state
    streamingOutputs: {},
    // Mark all messages as non-streaming
    messages: get().messages.map(msg => ({ ...msg, streaming: false }))
  });
}
```

---

#### `approveToolExecution(approvalRequestId: string, approved: boolean)`

**Purpose**: Responds to tool approval request.

```typescript
approveToolExecution: async (approvalRequestId: string, approved: boolean) => {
  try {
    await apiClient.approveToolExecution(approvalRequestId, approved);
    
    // Clear pending request
    set({ pendingApprovalRequest: null });
    
    console.log(`Tool ${approved ? "approved" : "denied"}`);
  } catch (error) {
    console.error("Failed to approve tool:", error);
  }
}
```

---

#### `abortStreaming()`

**Purpose**: Cancels ongoing streaming.

```typescript
abortStreaming: () => {
  const { controller } = get();
  if (controller) {
    controller.abort();
    set({
      isStreaming: false,
      controller: null,
      streamError: "Streaming aborted by user"
    });
  }
}
```

---

#### `reset()`

**Purpose**: Resets chat state (for new conversation).

```typescript
reset: () => {
  set({
    conversationId: null,
    conversationTitle: null,
    messages: [],
    toolCalls: [],
    toolCallIndex: {},
    pendingApprovalRequest: null,
    isStreaming: false,
    streamError: undefined,
    controller: null,
    streamingOutputs: {},
    responseId: null,
    conversationStatus: "CREATED",
    completionReason: null
  });
}
```

---

### Complexity Analysis

**Why 1000+ lines?**
1. **Multiple responsibilities**:
   - Conversation management
   - Message handling
   - Streaming state
   - Tool execution tracking
   - Approval workflow
   - LLM configuration

2. **Complex SSE event handling**:
   - 7 different event types
   - Stateful message accumulation
   - Tool call upserts

3. **Error handling**:
   - Network errors
   - Parsing errors
   - Timeout handling

**Refactoring Suggestions**:

Split into multiple stores:
```typescript
// conversationStore.ts - CRUD for conversations
const useConversationStore = create<ConversationState>(...);

// messageStore.ts - Message management
const useMessageStore = create<MessageState>(...);

// streamingStore.ts - SSE state
const useStreamingStore = create<StreamingState>(...);

// toolCallStore.ts - Tool execution tracking
const useToolCallStore = create<ToolCallState>(...);

// configStore.ts - LLM configuration
const useConfigStore = create<ConfigState>(...);
```

**Benefits**:
- Smaller, focused stores (easier to understand)
- Better testability (unit test each store separately)
- Reduced re-renders (components subscribe to specific stores)
- Easier to add features (modify one store without affecting others)

---

## Store: `mcpServerStore.ts`

**File**: `store/mcpServerStore.ts` (300+ lines)

**Purpose**: Manages MCP server list and connection status.

### State Interface

```typescript
interface McpServerState {
  servers: McpServer[];
  selectedServer: McpServer | null;
  loading: boolean;
  error: string | null;
  statusStream: EventSource | null;
  
  // Actions
  loadServers: () => Promise<void>;
  selectServer: (server: McpServer | null) => void;
  createServer: (server: McpServerRequest) => Promise<void>;
  updateServer: (serverId: string, server: McpServerRequest) => Promise<void>;
  deleteServer: (serverId: string) => Promise<void>;
  verifyConnection: (serverId: string) => Promise<void>;
  syncCapabilities: (serverId: string) => Promise<void>;
  connectToStatusStream: () => void;
  disconnectFromStatusStream: () => void;
}
```

---

### Key Actions

#### `loadServers()`

```typescript
loadServers: async () => {
  set({ loading: true, error: null });
  try {
    const servers = await apiClient.getMcpServers();
    set({ servers, loading: false });
  } catch (error) {
    set({
      error: error instanceof Error ? error.message : "Failed to load servers",
      loading: false
    });
  }
}
```

---

#### `createServer(server: McpServerRequest)`

```typescript
createServer: async (server: McpServerRequest) => {
  set({ loading: true, error: null });
  try {
    const created = await apiClient.createMcpServer(server);
    set(state => ({
      servers: [...state.servers, created],
      loading: false
    }));
  } catch (error) {
    set({
      error: error instanceof Error ? error.message : "Failed to create server",
      loading: false
    });
    throw error;
  }
}
```

---

#### `verifyConnection(serverId: string)`

```typescript
verifyConnection: async (serverId: string) => {
  try {
    const status = await apiClient.verifyMcpConnection(serverId);
    
    // Update server status in state
    set(state => ({
      servers: state.servers.map(s =>
        s.serverId === serverId
          ? { ...s, status: status.status }
          : s
      )
    }));
  } catch (error) {
    console.error("Connection verification failed:", error);
    throw error;
  }
}
```

---

#### `connectToStatusStream()` ⭐

**Purpose**: Opens SSE connection for real-time MCP server status updates.

```typescript
connectToStatusStream: () => {
  const { statusStream } = get();
  
  // Close existing stream
  if (statusStream) {
    statusStream.close();
  }
  
  // Open new stream
  const eventSource = new EventSource("/api/mcp/servers/status/stream");
  
  eventSource.addEventListener("status_update", (event) => {
    const data = JSON.parse(event.data);
    
    // Update server status in state
    set(state => ({
      servers: state.servers.map(s =>
        s.serverId === data.serverId
          ? { ...s, status: data.status }
          : s
      )
    }));
  });
  
  eventSource.addEventListener("capabilities_synced", (event) => {
    const data = JSON.parse(event.data);
    
    // Update sync status
    set(state => ({
      servers: state.servers.map(s =>
        s.serverId === data.serverId
          ? { ...s, syncStatus: "SYNCED", lastSyncedAt: data.syncedAt }
          : s
      )
    }));
  });
  
  eventSource.onerror = (error) => {
    console.error("Status stream error:", error);
    eventSource.close();
    set({ statusStream: null });
  };
  
  set({ statusStream: eventSource });
}
```

---

## Store: `n8nStore.ts`

**File**: `store/n8nStore.ts` (150+ lines)

**Purpose**: Manages n8n workflow list and execution.

### State Interface

```typescript
interface N8nState {
  workflows: N8nWorkflow[];
  loading: boolean;
  error: string | null;
  
  // Actions
  loadWorkflows: () => Promise<void>;
  executeWorkflow: (workflowId: string, data: any) => Promise<any>;
}
```

---

### Key Actions

#### `loadWorkflows()`

```typescript
loadWorkflows: async () => {
  set({ loading: true, error: null });
  try {
    // Load workflows from n8n server via MCP
    const capabilities = await apiClient.getMcpCapabilities("n8n-server-1");
    
    // Transform tools to workflows
    const workflows = capabilities.tools.map(tool => ({
      id: tool.name,
      name: tool.name,
      description: tool.description,
      active: true
    }));
    
    set({ workflows, loading: false });
  } catch (error) {
    set({
      error: error instanceof Error ? error.message : "Failed to load workflows",
      loading: false
    });
  }
}
```

---

#### `executeWorkflow(workflowId: string, data: any)`

```typescript
executeWorkflow: async (workflowId: string, data: any) => {
  try {
    const result = await apiClient.executeMcpTool(
      "n8n-server-1",
      workflowId,
      data
    );
    
    console.log("Workflow executed:", result);
    return result;
  } catch (error) {
    console.error("Workflow execution failed:", error);
    throw error;
  }
}
```

---

## Custom Hooks

### `useChatState.ts`

**Purpose**: Provides granular selectors for chat state.

```typescript
export function useChatState() {
  return {
    conversationId: useChatStore(s => s.conversationId),
    conversationTitle: useChatStore(s => s.conversationTitle),
    conversationSummaries: useChatStore(s => s.conversationSummaries),
    messages: useChatStore(s => s.messages),
    toolCalls: useChatStore(s => s.toolCalls),
    pendingApprovalRequest: useChatStore(s => s.pendingApprovalRequest),
    isStreaming: useChatStore(s => s.isStreaming),
    streamError: useChatStore(s => s.streamError),
    conversationStatus: useChatStore(s => s.conversationStatus),
    model: useChatStore(s => s.model),
    availableModels: useChatStore(s => s.availableModels),
    temperature: useChatStore(s => s.temperature),
    maxTokens: useChatStore(s => s.maxTokens),
    systemPrompt: useChatStore(s => s.systemPrompt),
  };
}

export function useChatActions() {
  return {
    ensureConversation: useChatStore(s => s.ensureConversation),
    loadConversations: useChatStore(s => s.loadConversations),
    loadConversation: useChatStore(s => s.loadConversation),
    sendMessage: useChatStore(s => s.sendMessage),
    approveToolExecution: useChatStore(s => s.approveToolExecution),
    abortStreaming: useChatStore(s => s.abortStreaming),
    reset: useChatStore(s => s.reset),
    fetchModels: useChatStore(s => s.fetchModels),
    setModel: useChatStore(s => s.setModel),
    setTemperature: useChatStore(s => s.setTemperature),
    setMaxTokens: useChatStore(s => s.setMaxTokens),
    setSystemPrompt: useChatStore(s => s.setSystemPrompt),
  };
}
```

**Benefits**:
- Clear separation of state and actions
- Components only subscribe to what they need
- Better TypeScript inference
- Easier to refactor

---

### `useMcpState.ts`

**Purpose**: Similar to `useChatState` but for MCP state.

```typescript
export function useMcpState() {
  return {
    servers: useMcpServerStore(s => s.servers),
    selectedServer: useMcpServerStore(s => s.selectedServer),
    loading: useMcpServerStore(s => s.loading),
    error: useMcpServerStore(s => s.error),
  };
}

export function useMcpActions() {
  return {
    loadServers: useMcpServerStore(s => s.loadServers),
    selectServer: useMcpServerStore(s => s.selectServer),
    createServer: useMcpServerStore(s => s.createServer),
    updateServer: useMcpServerStore(s => s.updateServer),
    deleteServer: useMcpServerStore(s => s.deleteServer),
    verifyConnection: useMcpServerStore(s => s.verifyConnection),
    syncCapabilities: useMcpServerStore(s => s.syncCapabilities),
    connectToStatusStream: useMcpServerStore(s => s.connectToStatusStream),
    disconnectFromStatusStream: useMcpServerStore(s => s.disconnectFromStatusStream),
  };
}
```

---

## Known Issues & Improvements

### Critical Issues ⚠️

1. **chatStore Too Large** (1000+ lines)
   - **Problem**: Hard to maintain, test, debug
   - **Fix**: Split into multiple stores (see "Refactoring Suggestions")

2. **No Error Boundaries**
   - **Problem**: Errors in stores crash entire app
   - **Fix**: Add React error boundaries:
     ```tsx
     <ErrorBoundary fallback={<ErrorScreen />}>
       <App />
     </ErrorBoundary>
     ```

3. **Inconsistent Error Handling**
   - **Problem**: Some actions throw, some set error state
   - **Fix**: Standardize error handling pattern

### Minor Issues

4. **No Loading States for Many Actions**
   - **Problem**: UI doesn't show feedback during operations
   - **Fix**: Add loading flags for all async actions

5. **SSE Connection Not Always Closed**
   - **Problem**: Memory leak on component unmount
   - **Fix**: Ensure cleanup in `useEffect`:
     ```typescript
     useEffect(() => {
       mcpActions.connectToStatusStream();
       return () => {
         mcpActions.disconnectFromStatusStream();
       };
     }, []);
     ```

6. **No Persistence**
   - **Problem**: State lost on page refresh
   - **Fix**: Use `zustand/middleware` for persistence:
     ```typescript
     import { persist } from 'zustand/middleware';
     
     const useStore = create(
       persist(
         (set, get) => ({ /* state */ }),
         { name: 'chat-storage' }
       )
     );
     ```

---

## Future Enhancements

1. **Add Optimistic Updates**
   - Update UI immediately, revert on error
   - Better UX for slow networks

2. **Add Undo/Redo**
   - Use Zustand middleware for history
   - Allow undo for message edits, deletions

3. **Add Offline Support**
   - Queue messages when offline
   - Sync when reconnected

4. **Add State Persistence**
   - Save to localStorage
   - Restore on page load

---

## Contributing

When modifying stores:
1. Follow Zustand best practices
2. Add type annotations
3. Use custom hooks for selectors
4. Test async actions
5. Update this documentation

---

For related documentation:
- [Parent: Frontend AGENTS.md](../AGENTS.md)
- [Root: Project AGENTS.md](../../AGENTS.md)
- [Services: apiClient.ts documentation](../services/AGENTS.md)
