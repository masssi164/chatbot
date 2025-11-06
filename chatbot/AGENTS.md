# Frontend Architecture - React + Vite + TypeScript

## Overview

This frontend is a **modern React application** built with:
- React 19.1 (latest with concurrent features)
- TypeScript 5.9 (strict mode)
- Vite 7.1 (fast build tool)
- Zustand 5.0 (lightweight state management)
- Server-Sent Events (SSE) for real-time streaming

**Key Features**:
- Real-time chat with streaming responses
- MCP server management UI
- n8n workflow integration
- Tool execution with approval workflow
- Markdown rendering for messages
- Responsive design

---

## Directory Structure

```
src/
├── main.tsx                        # Application entry point
├── App.tsx                         # Main application component
├── App.css                         # Global styles
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
├── store/                          # Zustand stores
│   ├── chatStore.ts                # Chat state + actions
│   ├── mcpServerStore.ts           # MCP server state + actions
│   └── n8nStore.ts                 # n8n workflow state
├── hooks/                          # Custom React hooks
│   ├── useChatState.ts             # Chat state selector
│   └── useMcpState.ts              # MCP state selector
├── services/                       # API client
│   └── apiClient.ts                # Typed HTTP client
├── types/                          # TypeScript types
│   └── mcp.ts                      # MCP-related types
└── utils/                          # Utility functions
    ├── format.ts                   # Formatting helpers
    └── logger.ts                   # Logging helpers
```

---

## State Management (Zustand)

### Why Zustand?

- **Lightweight**: No boilerplate, no providers
- **Simple API**: `create()` and `useStore()`
- **TypeScript-friendly**: Full type inference
- **DevTools support**: Redux DevTools compatible
- **No re-render issues**: Granular selectors

### Store Structure

```typescript
// Pattern for all stores
const useStore = create<State & Actions>((set, get) => ({
  // State
  items: [],
  loading: false,
  
  // Actions (mutations)
  addItem: (item) => set(state => ({
    items: [...state.items, item]
  })),
  
  // Async actions
  fetchItems: async () => {
    set({ loading: true });
    const items = await apiClient.getItems();
    set({ items, loading: false });
  }
}));
```

---

## Store: `chatStore.ts` ⭐ **Core State**

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
  pendingApprovalRequest: ApprovalRequest | null;
  
  // Streaming
  isStreaming: boolean;
  streamError?: string;
  
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
  
  // Actions
  ensureConversation: () => Promise<void>;
  loadConversations: () => Promise<void>;
  loadConversation: (conversationId: number) => Promise<void>;
  sendMessage: (content: string) => Promise<void>;
  abortStreaming: () => void;
  reset: () => void;
  fetchModels: () => Promise<void>;
  setModel: (model: string) => void;
  // ... more actions
}
```

### Key Actions

#### `sendMessage(content: string)`

**Flow**:
1. Validate input (not empty, not streaming)
2. Add user message to state (optimistic update)
3. Start SSE connection to `/api/responses/stream`
4. Handle SSE events:
   - `init`: Initialize response
   - `message`: Append text delta to current message
   - `tool_call_update`: Update tool execution state
   - `approval_required`: Show approval dialog
   - `conversation_status`: Update status
   - `error`: Show error
   - `done`: Finalize response
5. Update conversation status based on events

**SSE Event Handling**:
```typescript
fetchEventSource("/api/responses/stream", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(payload),
  
  onopen: async (response) => {
    if (response.ok) {
      console.log("SSE connection opened");
    } else {
      throw new Error(`HTTP ${response.status}`);
    }
  },
  
  onmessage: (event) => {
    if (event.event === "message") {
      const data = JSON.parse(event.data);
      // Append text delta to current message
      appendMessageDelta(data.delta);
    } else if (event.event === "tool_call_update") {
      // Update tool call state
    } else if (event.event === "approval_required") {
      // Show approval dialog
      setPendingApprovalRequest(data);
    }
  },
  
  onerror: (err) => {
    console.error("SSE error:", err);
    setStreamError(err.message);
  },
  
  onclose: () => {
    console.log("SSE connection closed");
    setIsStreaming(false);
  }
});
```

#### `approveToolExecution(approvalRequestId: string, approved: boolean)`

**Flow**:
1. Send approval decision to backend
2. If approved: Tool executes, response continues
3. If denied: Tool skipped, response continues with error message

#### `loadConversation(conversationId: number)`

**Flow**:
1. Fetch conversation details from `/api/conversations/{id}`
2. Parse messages and tool calls
3. Update state with conversation data

#### `ensureConversation()`

**Flow**:
1. If `conversationId` is null: create new conversation
2. Otherwise: do nothing (conversation already exists)

### Internal State

The store maintains private state for streaming:

```typescript
interface PrivateState {
  streamingOutputs: Record<number, {
    messageId: string;
    itemId?: string | null;
  }>;
  toolCallIndex: Record<string, ToolCallState>;
}
```

**Purpose**:
- `streamingOutputs`: Maps output index to message ID (for incremental updates)
- `toolCallIndex`: Maps tool call item ID to tool state (for fast lookup)

### Complexity Notes

**Why `chatStore.ts` is 1000+ lines**:
1. SSE event handling (multiple event types)
2. Conversation CRUD operations
3. Message management (optimistic updates, streaming deltas)
4. Tool execution tracking
5. Approval workflow
6. LLM configuration
7. Error handling

**Refactoring Suggestions**:
- Extract `conversationStore.ts` (CRUD)
- Extract `messageStore.ts` (message management)
- Extract `streamingStore.ts` (SSE state)
- Extract `toolCallStore.ts` (tool execution tracking)
- Extract `configStore.ts` (LLM configuration)

---

## Store: `mcpServerStore.ts`

**Purpose**: Manages MCP server list and connection status.

### State Interface

```typescript
interface McpServerState {
  servers: McpServer[];
  selectedServer: McpServer | null;
  loading: boolean;
  error: string | null;
  statusStream: EventSource | null;
  
  loadServers: () => Promise<void>;
  createServer: (server: McpServerRequest) => Promise<void>;
  updateServer: (serverId: string, server: McpServerRequest) => Promise<void>;
  deleteServer: (serverId: string) => Promise<void>;
  verifyConnection: (serverId: string) => Promise<void>;
  syncCapabilities: (serverId: string) => Promise<void>;
  connectToStatusStream: () => void;
  disconnectFromStatusStream: () => void;
}
```

### Key Actions

#### `loadServers()`
- Fetches MCP servers from `/api/mcp/servers`
- Updates `servers` state

#### `verifyConnection(serverId: string)`
- Tests connection to MCP server
- Updates server status in state

#### `syncCapabilities(serverId: string)`
- Refreshes tools/resources/prompts cache
- Updates `lastSyncedAt` timestamp

#### `connectToStatusStream()`
- Opens SSE connection to `/api/mcp/servers/status/stream`
- Listens for real-time status updates
- Updates server status in state when events arrive

**SSE Event Types**:
- `status_update`: Server status changed (CONNECTING, CONNECTED, ERROR)
- `capabilities_synced`: Capabilities cache refreshed
- `connection_change`: Connection state changed

---

## Store: `n8nStore.ts`

**Purpose**: Manages n8n workflow list and execution.

### State Interface

```typescript
interface N8nState {
  workflows: N8nWorkflow[];
  loading: boolean;
  error: string | null;
  
  loadWorkflows: () => Promise<void>;
  executeWorkflow: (workflowId: string, data: any) => Promise<void>;
}
```

### Key Actions

#### `loadWorkflows()`
- Fetches workflows from n8n server via MCP
- Displays in UI as available tools

#### `executeWorkflow(workflowId: string, data: any)`
- Triggers workflow execution
- Returns result or error

---

## Hooks

### `useChatState.ts`

**Purpose**: Provides granular selectors for chat state to minimize re-renders.

```typescript
export function useChatState() {
  return {
    conversationId: useChatStore(s => s.conversationId),
    conversationTitle: useChatStore(s => s.conversationTitle),
    messages: useChatStore(s => s.messages),
    toolCalls: useChatStore(s => s.toolCalls),
    isStreaming: useChatStore(s => s.isStreaming),
    // ... more selectors
  };
}

export function useChatActions() {
  return {
    sendMessage: useChatStore(s => s.sendMessage),
    loadConversation: useChatStore(s => s.loadConversation),
    reset: useChatStore(s => s.reset),
    // ... more actions
  };
}
```

**Benefits**:
- Component only re-renders when selected state changes
- Clear separation of state and actions
- Better TypeScript inference

### `useMcpState.ts`

**Purpose**: Similar to `useChatState` but for MCP state.

```typescript
export function useMcpState() {
  return {
    servers: useMcpServerStore(s => s.servers),
    selectedServer: useMcpServerStore(s => s.selectedServer),
    loading: useMcpServerStore(s => s.loading),
  };
}

export function useMcpActions() {
  return {
    loadServers: useMcpServerStore(s => s.loadServers),
    createServer: useMcpServerStore(s => s.createServer),
    // ... more actions
  };
}
```

---

## Components

### `App.tsx` ⭐ **Main Component**

**Purpose**: Root component that composes all UI elements.

**Structure**:
```tsx
<div className="app-shell">
  <header className="app-header">
    <h1>Chat Console</h1>
    <button onClick={() => setShowSidebar(true)}>
      Chat History
    </button>
    <button onClick={() => setShowSettings(true)}>
      Settings
    </button>
  </header>
  
  <main className="app-content">
    <ChatHistory messages={messages} />
    <ToolCallList toolCalls={toolCalls} />
    <ChatInput onSubmit={sendMessage} />
  </main>
  
  {showSidebar && (
    <Modal onClose={() => setShowSidebar(false)}>
      <ChatSidebar conversations={conversations} />
    </Modal>
  )}
  
  {showSettings && (
    <Modal onClose={() => setShowSettings(false)}>
      <SettingsPanel />
    </Modal>
  )}
  
  {pendingApprovalRequest && (
    <UserApprovalDialog request={pendingApprovalRequest} />
  )}
</div>
```

**Effects**:
```tsx
useEffect(() => {
  // On mount:
  void chatActions.ensureConversation();
  void chatActions.loadConversations();
  void chatActions.fetchModels();
  void mcpActions.loadServers();
  mcpActions.connectToStatusStream();
  
  return () => {
    // On unmount:
    mcpActions.disconnectFromStatusStream();
  };
}, []);
```

---

### `ChatHistory.tsx`

**Purpose**: Displays conversation messages with markdown rendering.

**Features**:
- Renders markdown with `react-markdown`
- Displays user and assistant messages
- Shows tool execution status inline
- Auto-scrolls to bottom on new message

**Structure**:
```tsx
<div className="chat-history">
  {messages.map(message => (
    <div key={message.id} className={`message ${message.role}`}>
      <div className="message-content">
        <ReactMarkdown>{message.content}</ReactMarkdown>
      </div>
      <div className="message-meta">
        {formatTimestamp(message.createdAt)}
      </div>
    </div>
  ))}
  <div ref={endRef} /> {/* Auto-scroll target */}
</div>
```

---

### `ChatInput.tsx`

**Purpose**: User input field with auto-resize and submit handling.

**Features**:
- Textarea with auto-resize (based on content)
- Submit on Enter (Shift+Enter for newline)
- Disabled during streaming
- Clear after submit

**Implementation**:
```tsx
export interface ChatInputHandle {
  focus: () => void;
}

const ChatInput = forwardRef<ChatInputHandle, Props>((props, ref) => {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  
  useImperativeHandle(ref, () => ({
    focus: () => textareaRef.current?.focus()
  }));
  
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  };
  
  const handleSubmit = () => {
    if (value.trim() && !disabled) {
      onSubmit(value);
      setValue("");
    }
  };
  
  return (
    <div className="chat-input">
      <textarea
        ref={textareaRef}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        placeholder="Type your message..."
      />
      <button onClick={handleSubmit} disabled={disabled}>
        Send
      </button>
    </div>
  );
});
```

---

### `ChatSidebar.tsx`

**Purpose**: List of conversations with search and filters.

**Features**:
- Displays conversation summaries
- Click to load conversation
- Delete conversation
- Search by title
- Sort by date

---

### `SettingsPanel.tsx`

**Purpose**: App configuration UI.

**Tabs**:
1. **Model Settings**:
   - Model selection (dropdown)
   - Temperature slider (0-2)
   - Max tokens input
   - Top P slider
   - Presence/frequency penalty sliders

2. **System Prompt**:
   - Textarea for system prompt
   - Save/reset buttons

3. **MCP Servers**:
   - Embedded `McpCapabilitiesPanel`

4. **n8n Workflows**:
   - Embedded `N8nPanel`

---

### `SystemPromptPanel.tsx`

**Purpose**: Edit system prompt for conversations.

**Features**:
- Large textarea for prompt
- Save button
- Reset to default button
- Character count

---

### `McpCapabilitiesPanel.tsx` ⭐ **MCP Management UI**

**Purpose**: Manage MCP servers and view capabilities.

**Sections**:

1. **Server List**:
   - Table with server name, status, transport, actions
   - Status badge (CONNECTED, ERROR, etc.)
   - Actions: Verify, Sync, Edit, Delete

2. **Add/Edit Server Form**:
   - Server ID (auto-generated or custom)
   - Name
   - Base URL
   - API Key (password field)
   - Transport (SSE, Streamable HTTP)
   - Save button

3. **Capabilities View** (when server selected):
   - **Tools**: List of available tools with name, description
   - **Resources**: List of resources
   - **Prompts**: List of prompt templates

4. **Approval Policies**:
   - Table with tool name, server, policy
   - Policy dropdown: ALWAYS_ALLOW, ALWAYS_DENY, ASK_USER
   - Save button

**Implementation Highlights**:
```tsx
const handleVerify = async (serverId: string) => {
  setVerifying(serverId);
  await mcpActions.verifyConnection(serverId);
  setVerifying(null);
};

const handleSync = async (serverId: string) => {
  setSyncing(serverId);
  await mcpActions.syncCapabilities(serverId);
  setSyncing(null);
};

const handleDeleteServer = async (serverId: string) => {
  if (confirm("Delete this server?")) {
    await mcpActions.deleteServer(serverId);
  }
};
```

---

### `N8nPanel.tsx`

**Purpose**: n8n workflow management UI.

**Features**:
- Connection status to n8n server
- List of active workflows
- Execute workflow button
- View workflow details

---

### `ToolCallList.tsx`

**Purpose**: Displays tool execution history for current conversation.

**Features**:
- List of tool calls with status (in progress, completed, failed)
- Click to expand details
- Shows tool name, arguments, result, error

---

### `ToolCallDetails.tsx`

**Purpose**: Detailed view of a single tool execution.

**Features**:
- Tool name and type (FUNCTION, MCP)
- Server name (for MCP tools)
- Arguments (formatted JSON)
- Result (formatted JSON or text)
- Error message (if failed)
- Timestamps (created, updated)

**Implementation**:
```tsx
<div className="tool-call-details">
  <h3>{toolCall.name}</h3>
  <div className="status-badge">{toolCall.status}</div>
  
  <section>
    <h4>Arguments</h4>
    <pre>{JSON.stringify(toolCall.arguments, null, 2)}</pre>
  </section>
  
  {toolCall.result && (
    <section>
      <h4>Result</h4>
      <pre>{JSON.stringify(toolCall.result, null, 2)}</pre>
    </section>
  )}
  
  {toolCall.error && (
    <section>
      <h4>Error</h4>
      <div className="error-message">{toolCall.error}</div>
    </section>
  )}
</div>
```

---

### `UserApprovalDialog.tsx` ⭐ **Approval Workflow UI**

**Purpose**: Modal for approving/denying tool execution.

**Features**:
- Displays tool name, server, arguments
- Approve button (green)
- Deny button (red)
- Blocks until user decision

**Implementation**:
```tsx
<Modal onClose={() => {}}>
  <div className="approval-dialog">
    <h2>Tool Execution Approval Required</h2>
    
    <div className="tool-info">
      <p><strong>Server:</strong> {request.serverLabel}</p>
      <p><strong>Tool:</strong> {request.toolName}</p>
      {request.arguments && (
        <>
          <p><strong>Arguments:</strong></p>
          <pre>{request.arguments}</pre>
        </>
      )}
    </div>
    
    <div className="actions">
      <button
        className="approve"
        onClick={() => handleApprove(true)}
      >
        ✓ Approve
      </button>
      <button
        className="deny"
        onClick={() => handleApprove(false)}
      >
        ✗ Deny
      </button>
    </div>
  </div>
</Modal>

const handleApprove = async (approved: boolean) => {
  await chatActions.approveToolExecution(
    request.approvalRequestId,
    approved
  );
};
```

---

### `Modal.tsx`

**Purpose**: Generic modal wrapper.

**Features**:
- Backdrop (click to close)
- Close button (X)
- Escape key to close
- Prevents body scroll when open

**Implementation**:
```tsx
const Modal: React.FC<Props> = ({ children, onClose }) => {
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    
    document.addEventListener("keydown", handleEscape);
    document.body.style.overflow = "hidden";
    
    return () => {
      document.removeEventListener("keydown", handleEscape);
      document.body.style.overflow = "";
    };
  }, [onClose]);
  
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose}>
          ✕
        </button>
        {children}
      </div>
    </div>
  );
};
```

---

## Services

### `apiClient.ts` ⭐ **HTTP Client**

**Purpose**: Typed API client for backend communication.

**Features**:
- TypeScript types for all requests/responses
- Error handling
- Base URL configuration
- Authentication header injection

**API Methods**:

```typescript
export const apiClient = {
  // Conversations
  getConversations: () => Promise<ConversationSummary[]>;
  getConversation: (id: number) => Promise<ConversationDetail>;
  createConversation: (title?: string) => Promise<Conversation>;
  deleteConversation: (id: number) => Promise<void>;
  
  // Models
  getModels: () => Promise<string[]>;
  
  // MCP Servers
  getMcpServers: () => Promise<McpServer[]>;
  createMcpServer: (server: McpServerRequest) => Promise<McpServer>;
  updateMcpServer: (id: string, server: McpServerRequest) => Promise<McpServer>;
  deleteMcpServer: (id: string) => Promise<void>;
  verifyMcpConnection: (id: string) => Promise<McpConnectionStatus>;
  syncMcpCapabilities: (id: string) => Promise<void>;
  getMcpCapabilities: (id: string) => Promise<McpCapabilities>;
  
  // Tool Approval
  approveToolExecution: (id: string, approved: boolean) => Promise<void>;
};
```

**Implementation**:
```typescript
async function fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options?.headers,
    },
  });
  
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`HTTP ${response.status}: ${error}`);
  }
  
  return response.json();
}
```

---

## Types

### `mcp.ts`

**Purpose**: TypeScript types for MCP-related data.

```typescript
export type McpTransport = "SSE" | "STREAMABLE_HTTP";

export type McpServerStatus =
  | "IDLE"
  | "CONNECTING"
  | "CONNECTED"
  | "ERROR";

export type SyncStatus =
  | "NEVER_SYNCED"
  | "SYNCING"
  | "SYNCED"
  | "SYNC_FAILED";

export interface McpServer {
  id: number;
  serverId: string;
  name: string;
  baseUrl: string;
  transport: McpTransport;
  status: McpServerStatus;
  lastSyncedAt?: string;
  syncStatus: SyncStatus;
  lastUpdated: string;
}

export interface McpServerRequest {
  serverId?: string;
  name: string;
  baseUrl: string;
  apiKey?: string;
  transport: McpTransport;
}

export interface McpCapabilities {
  tools: McpTool[];
  resources: McpResource[];
  prompts: McpPrompt[];
  serverInfo: {
    name: string;
    version: string;
  };
}

export interface McpTool {
  name: string;
  description: string;
  inputSchema: any; // JSON Schema
}
```

---

## Utils

### `format.ts`

**Purpose**: Formatting utilities.

```typescript
export function formatTimestamp(timestamp: string | number): string {
  const date = new Date(timestamp);
  return date.toLocaleString();
}

export function formatDate(timestamp: string | number): string {
  const date = new Date(timestamp);
  return date.toLocaleDateString();
}
```

### `logger.ts`

**Purpose**: Logging utilities.

```typescript
const isDevelopment = import.meta.env.DEV;

export const logger = {
  debug: (...args: any[]) => {
    if (isDevelopment) console.log("[DEBUG]", ...args);
  },
  info: (...args: any[]) => {
    console.log("[INFO]", ...args);
  },
  warn: (...args: any[]) => {
    console.warn("[WARN]", ...args);
  },
  error: (...args: any[]) => {
    console.error("[ERROR]", ...args);
  },
};
```

---

## Styling

### CSS Architecture

- **Global styles**: `App.css`, `index.css`
- **Component styles**: Inline in component files (using CSS-in-JS)
- **CSS Variables**: For theming

**CSS Variables** (`index.css`):
```css
:root {
  --color-primary: #007bff;
  --color-secondary: #6c757d;
  --color-success: #28a745;
  --color-danger: #dc3545;
  --color-warning: #ffc107;
  --color-info: #17a2b8;
  
  --color-bg: #ffffff;
  --color-bg-secondary: #f8f9fa;
  --color-text: #212529;
  --color-text-muted: #6c757d;
  --color-border: #dee2e6;
  
  --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  --font-size-base: 16px;
  --font-size-sm: 14px;
  --font-size-lg: 18px;
  
  --spacing-xs: 4px;
  --spacing-sm: 8px;
  --spacing-md: 16px;
  --spacing-lg: 24px;
  --spacing-xl: 32px;
}
```

---

## Build & Development

### Development

```bash
cd chatbot
npm install
npm run dev
```

Access at: http://localhost:5173

### Production Build

```bash
npm run build
```

Output: `build/` directory

### Linting

```bash
npm run lint
```

ESLint configuration in `eslint.config.js`

---

## Configuration

### Vite Config (`vite.config.ts`)

```typescript
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: "build",
    sourcemap: true,
  },
});
```

**Proxy**: Proxies `/api/*` to backend during development (avoids CORS issues)

---

## Known Issues & Improvements

See [../AGENTS.md](../AGENTS.md) for detailed analysis.

**Priority issues**:
1. ⚠️ `chatStore.ts` is too large (1000+ lines) → Split into multiple stores
2. ⚠️ No error boundaries → Add `<ErrorBoundary>` components
3. ⚠️ Inconsistent error handling → Standardize with global error handler
4. ⚠️ No loading states for many actions → Add skeleton loaders
5. ⚠️ SSE connection not always closed properly → Improve cleanup

**Improvements**:
1. Add pagination for conversation list
2. Add search/filter for messages
3. Add export conversation feature
4. Add dark mode toggle
5. Add keyboard shortcuts
6. Add accessibility (ARIA labels, screen reader support)

---

## Testing

### Unit Tests

Currently no tests. Recommended setup:

```bash
npm install --save-dev vitest @testing-library/react @testing-library/jest-dom
```

Example test:
```typescript
import { render, screen } from "@testing-library/react";
import { ChatInput } from "./ChatInput";

test("renders input field", () => {
  render(<ChatInput onSubmit={() => {}} />);
  const input = screen.getByPlaceholderText("Type your message...");
  expect(input).toBeInTheDocument();
});
```

### E2E Tests

Recommended: Playwright or Cypress

```bash
npm install --save-dev @playwright/test
```

---

## Contributing

When adding new features:
1. Follow React best practices (hooks, functional components)
2. Use TypeScript strict mode (no `any` unless necessary)
3. Add types in `types/` directory
4. Update stores if state changes
5. Add unit tests
6. Update this documentation

---

For backend documentation, see [../chatbot-backend/AGENTS.md](../chatbot-backend/AGENTS.md).
