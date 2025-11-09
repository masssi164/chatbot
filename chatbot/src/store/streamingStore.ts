import { fetchEventSource } from "@microsoft/fetch-event-source";
import { create } from "zustand";
import { apiClient, type ConversationStatus } from "../services/apiClient";
import { useMessageStore, type ChatMessage } from "./messageStore";
import { useToolCallStore, type ToolCallState, type ApprovalRequest } from "./toolCallStore";
import { useConversationStore } from "./conversationStore";
import { useConfigStore } from "./configStore";

export type StatusSeverity = "info" | "success" | "warning" | "error";

export interface StatusUpdate {
  id: string;
  label: string;
  detail?: string | null;
  severity: StatusSeverity;
  timestamp: number;
}

/**
 * Streaming Store for managing SSE streaming and response lifecycle
 * Extracted from chatStore for better maintainability
 */
export interface StreamingState {
  isStreaming: boolean;
  streamError?: string;
  controller: AbortController | null;
  
  // Response lifecycle tracking (matches backend ConversationStatus)
  responseId?: string | null;
  conversationStatus: ConversationStatus;
  completionReason?: string | null;
  statusUpdates: StatusUpdate[];
  
  // Private streaming state
  streamingOutputs: Record<number, { messageId: string; itemId?: string | null }>;
  
  // Actions
  sendMessage: (content: string) => Promise<void>;
  abortStreaming: () => void;
  sendApprovalResponse: (approve: boolean, remember: boolean) => Promise<void>;
  reset: () => void;
}

function createId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return Math.random().toString(36).slice(2, 12);
}

function buildInputPayload(messages: ChatMessage[], pendingUser?: string, systemPrompt?: string) {
  // Build conversation context as a formatted string for Responses API
  let inputText = '';

  // Add system prompt if present
  if (systemPrompt && systemPrompt.trim().length > 0) {
    inputText += `[System Instructions]\n${systemPrompt.trim()}\n\n`;
  }

  // Add conversation history (excluding tool messages)
  messages
    .filter((message) => message.role !== "tool")
    .forEach((message) => {
      const roleLabel = message.role === "user" ? "User" : "Assistant";
      inputText += `${roleLabel}: ${message.content}\n\n`;
    });

  // Add pending user message
  if (pendingUser && pendingUser.trim().length > 0) {
    inputText += `User: ${pendingUser}\n\n`;
  }

  // Validation: Must have at least one user message
  const hasUserMessage = messages.some((msg) => msg.role === "user") || (pendingUser && pendingUser.trim().length > 0);
  if (!hasUserMessage) {
    throw new Error("Input must contain at least one user message");
  }

  // Add assistant prompt for continuation
  inputText += "Assistant:";

  return inputText;
}

export const useStreamingStore = create<StreamingState>((set, get) => ({
  isStreaming: false,
  streamError: undefined,
  controller: null,
  responseId: null,
  conversationStatus: "CREATED",
  completionReason: null,
  statusUpdates: [],
  streamingOutputs: {},

  sendMessage: async (content: string) => {
    console.log("sendMessage called with:", content);
    const trimmed = content.trim();
    if (!trimmed) {
      console.log("Message is empty, aborting");
      return;
    }

    const state = get();
    console.log("Current state:", { 
      isStreaming: state.isStreaming,
    });
    
    if (state.isStreaming) {
      throw new Error("Streaming already in progress");
    }

    // Ensure conversation exists
    const conversationStore = useConversationStore.getState();
    await conversationStore.ensureConversation();
    const conversationId = conversationStore.conversationId;
    
    if (!conversationId) {
      throw new Error("Failed to create conversation");
    }

    // Add user message
    const userMessage: ChatMessage = {
      id: createId(),
      role: "user",
      content: trimmed,
      createdAt: Date.now(),
      streaming: false,
    };

    const messageStore = useMessageStore.getState();
    messageStore.addMessage(userMessage);

    await apiClient.addMessage(conversationId, {
      role: "USER",
      content: trimmed,
    });

    const controller = new AbortController();
    set({ isStreaming: true, controller, streamError: undefined });

    const configStore = useConfigStore.getState();
    const requestPayload = {
      conversationId,
      title: conversationStore.conversationTitle,
      payload: {
        model: configStore.model,
        input: buildInputPayload(messageStore.messages, trimmed, configStore.systemPrompt),
        ...(configStore.temperature !== undefined && { temperature: configStore.temperature }),
        ...(configStore.maxTokens !== undefined && { max_output_tokens: configStore.maxTokens }),
        ...(configStore.topP !== undefined && { top_p: configStore.topP }),
        ...(configStore.presencePenalty !== undefined && { presence_penalty: configStore.presencePenalty }),
        ...(configStore.frequencyPenalty !== undefined && { frequency_penalty: configStore.frequencyPenalty }),
      },
    };

    try {
      console.log("Starting stream request to:", `${location.origin}/api/responses/stream`);
      console.log("Request payload:", JSON.stringify(requestPayload, null, 2));
      
      await fetchEventSource(`${location.origin}/api/responses/stream`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(requestPayload),
        signal: controller.signal,
        async onopen(response) {
          console.log("Stream opened, response status:", response.status);
          if (!response.ok && response.status !== 200) {
            throw new Error(`Streaming failed with status ${response.status}`);
          }
        },
        onmessage(event) {
          console.log("Stream message received:", event.event, event.data);
          handleStreamEvent(event.event, event.data);
        },
        onerror(err) {
          console.error("Stream error:", err);
          set({ isStreaming: false, controller: null, streamError: err.message });
          throw err;
        },
      });

      set({ isStreaming: false, controller: null });
    } catch (error) {
      if (controller.signal.aborted) {
        set({ isStreaming: false, controller: null });
        return;
      }
      set({
        isStreaming: false,
        controller: null,
        streamError: error instanceof Error ? error.message : String(error),
      });
    }
  },

  abortStreaming: () => {
    const controller = get().controller;
    if (controller) {
      controller.abort();
    }
  },

  sendApprovalResponse: async (approve: boolean, remember: boolean) => {
    const conversationStore = useConversationStore.getState();
    const toolCallStore = useToolCallStore.getState();
    const conversationId = conversationStore.conversationId;
    const pendingApprovalRequest = toolCallStore.pendingApprovalRequest;
    
    if (!pendingApprovalRequest || conversationId === null) {
      console.error("No pending approval request or conversation ID");
      return;
    }

    // If "remember" is checked, update the policy
    if (remember) {
      const policy = approve ? "always" : "never";
      try {
        await apiClient.setToolApprovalPolicy(
          pendingApprovalRequest.serverLabel,
          pendingApprovalRequest.toolName,
          policy
        );
      } catch (err) {
        console.error("Failed to update approval policy:", err);
      }
    }

    // Clear pending approval from store
    toolCallStore.setPendingApproval(null);

    // Send approval response and reconnect to SSE stream
    const controller = new AbortController();
    set({ isStreaming: true, controller });

    try {
      await fetchEventSource(`${location.origin}/api/responses/approval-response`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          conversation_id: conversationId,
          approval_request_id: pendingApprovalRequest.approvalRequestId,
          approve,
          reason: approve ? "User approved" : "User denied",
        }),
        signal: controller.signal,
        async onopen(response) {
          if (!response.ok && response.status !== 200) {
            throw new Error(`Approval response failed with status ${response.status}`);
          }
        },
        onmessage(event) {
          handleStreamEvent(event.event, event.data);
        },
        onerror(err) {
          console.error("Approval response stream error:", err);
          set({ isStreaming: false, streamError: String(err) });
          throw err;
        },
        onclose() {
          set({ isStreaming: false });
        },
      });
    } catch (error) {
      console.error("Failed to send approval response:", error);
      set({ isStreaming: false, streamError: String(error) });
    }
  },

  reset: () => {
    set({
      isStreaming: false,
      streamError: undefined,
      controller: null,
      streamingOutputs: {},
      responseId: null,
      conversationStatus: "CREATED",
      completionReason: null,
      statusUpdates: [],
    });
  },
}));

const STATUS_UPDATE_LIMIT = 20;

function addStatusUpdate(label: string, severity: StatusSeverity = "info", detail?: string | null) {
  const state = useStreamingStore.getState();
  const update: StatusUpdate = {
    id: createId(),
    label,
    detail: detail ?? null,
    severity,
    timestamp: Date.now(),
  };
  const next = [...state.statusUpdates, update];
  useStreamingStore.setState({
    statusUpdates: next.length > STATUS_UPDATE_LIMIT ? next.slice(-STATUS_UPDATE_LIMIT) : next,
  });
}

// ============================================
// SSE Event Handlers
// ============================================

function handleStreamEvent(rawEventName: string | undefined | null, payload: string | undefined) {
  if (!payload) {
    return;
  }

  const trimmedPayload = payload.trim();
  if (!trimmedPayload || trimmedPayload === "[DONE]") {
    return;
  }

  let data: any;
  try {
    data = JSON.parse(trimmedPayload);
  } catch (error) {
    const label = rawEventName && rawEventName.trim().length > 0 ? rawEventName : "message";
    console.warn(`Failed to parse event payload for ${label}`, error);
    return;
  }

  const eventName = normalizeEventName(rawEventName, data?.type);

  if (eventName === "conversation.ready") {
    const conversationStore = useConversationStore.getState();
    conversationStore.setCurrentConversation(
      data?.conversation_id ?? data?.id ?? null,
      data?.title ?? null
    );
    addStatusUpdate("Conversation ready", "success");
    return;
  }

  switch (eventName) {
    // Lifecycle events
    case "response.created":
      handleResponseCreated(data);
      break;
    case "response.completed":
      handleResponseCompleted(data);
      break;
    case "response.incomplete":
      handleResponseIncomplete(data);
      break;
    case "response.failed":
      handleResponseFailed(data);
      break;
    
    // Error events
    case "response.error":
      handleResponseError(data);
      break;
    case "error":
      handleCriticalError(data);
      break;
    
    // Text output events
    case "response.output_text.delta":
      handleTextDelta(data);
      break;
    case "response.output_text.done":
      handleTextDone(data);
      break;
    case "response.output_item.added":
      handleOutputItemAdded(data);
      break;
    case "response.output_item.done":
      handleOutputItemDone(data);
      break;
    
    // Function/MCP call events
    case "response.function_call_arguments.delta":
      updateToolCallArguments(data, "function");
      break;
    case "response.function_call_arguments.done":
      updateToolCallArguments(data, "function", true);
      break;
    case "response.mcp_call_arguments.delta":
      updateToolCallArguments(data, "mcp");
      break;
    case "response.mcp_call_arguments.done":
      updateToolCallArguments(data, "mcp", true);
      break;
    case "response.mcp_call.in_progress":
      updateToolCallStatus(data, "in_progress");
      break;
    case "response.mcp_call.completed":
      updateToolCallStatus(data, "completed");
      break;
    case "response.mcp_call.failed":
      updateToolCallStatus(data, "failed", data?.error ?? null);
      break;
    
    // MCP approval events
    case "response.mcp_approval_request":
      handleMcpApprovalRequest(data);
      break;
    
    default:
      break;
  }
}

function normalizeEventName(rawEventName?: string | null, fallbackType?: unknown): string {
  const trimmed = rawEventName?.trim();
  if (trimmed) {
    return trimmed;
  }

  if (typeof fallbackType === "string") {
    const fallback = fallbackType.trim();
    if (fallback.length > 0) {
      return fallback;
    }
  }

  return "message";
}

// ============================================
// Lifecycle Event Handlers
// ============================================

function handleResponseCreated(data: any) {
  if (!data?.response) {
    return;
  }

  const responseId = data.response.id;
  console.log("✅ Response created:", responseId);
  addStatusUpdate("Assistant is formulating a response", "info");

  useStreamingStore.setState({
    responseId,
    conversationStatus: "STREAMING",
    streamError: undefined,
  });
}

function handleResponseCompleted(data: any) {
  if (!data?.response) {
    return;
  }

  const responseId = data.response.id;
  console.log("✅ Response completed:", responseId);
  addStatusUpdate("Response completed", "success");

  useStreamingStore.setState({
    isStreaming: false,
    controller: null,
    conversationStatus: "COMPLETED",
    completionReason: null,
    streamError: undefined,
  });
}

function handleResponseIncomplete(data: any) {
  if (!data?.response) {
    return;
  }

  const reason = data.response.status_details?.reason || "length";
  console.warn("⚠️ Response incomplete:", reason);
  addStatusUpdate("Response incomplete", "warning", reason);

  useStreamingStore.setState({
    isStreaming: false,
    controller: null,
    conversationStatus: "INCOMPLETE",
    completionReason: reason,
    streamError: undefined,
  });
}

function handleResponseFailed(data: any) {
  if (!data?.response) {
    return;
  }

  const error = data.response.error || {};
  const errorCode = error.code || "unknown";
  const errorMessage = error.message || "Response failed";

  console.error("❌ Response failed:", errorCode, errorMessage);
  addStatusUpdate("Response failed", "error", `${errorCode}: ${errorMessage}`);

  useStreamingStore.setState({
    isStreaming: false,
    controller: null,
    conversationStatus: "FAILED",
    completionReason: `${errorCode}: ${errorMessage}`,
    streamError: errorMessage,
  });
}

function handleResponseError(data: any) {
  if (!data?.error) {
    return;
  }

  const error = data.error;
  const code = error.code || "unknown";
  const message = error.message || "Error occurred";

  if (code === "rate_limit_exceeded") {
    console.warn("⚠️ Rate limit exceeded:", message);
    addStatusUpdate("Rate limit encountered", "warning", message);
    useStreamingStore.setState({
      streamError: `Rate limit: ${message}`,
    });
  } else {
    console.error("❌ Response error:", code, message);
    addStatusUpdate("Response error", "error", message);
    useStreamingStore.setState({
      streamError: message,
    });
  }
}

function handleCriticalError(data: any) {
  if (!data?.error) {
    return;
  }

  const error = data.error;
  const code = error.code || "unknown";
  const message = error.message || "Critical error";

  console.error("❌ CRITICAL ERROR:", code, message);
  addStatusUpdate("Critical error", "error", message);

  useStreamingStore.setState({
    isStreaming: false,
    controller: null,
    conversationStatus: "FAILED",
    completionReason: `CRITICAL: ${code}`,
    streamError: message,
  });
}

// ============================================
// Text/Message Event Handlers
// ============================================

function handleTextDelta(data: any) {
  if (!data) {
    return;
  }
  
  const outputIndex = typeof data.output_index === "number" ? data.output_index : 0;
  const delta = typeof data.delta === "string" ? data.delta : "";
  const itemId = data.item_id ?? null;

  if (!delta) {
    return;
  }

  const streamingStore = useStreamingStore.getState();
  const messageStore = useMessageStore.getState();
  
  const mapping = { ...streamingStore.streamingOutputs };
  let entry = mapping[outputIndex];

  if (!entry) {
    entry = { messageId: createId(), itemId };
    mapping[outputIndex] = entry;
    
    const newMessage: ChatMessage = {
      id: entry.messageId,
      role: "assistant",
      content: "",
      createdAt: Date.now(),
      rawJson: null,
      outputIndex,
      itemId: itemId ?? undefined,
      streaming: true,
    };
    messageStore.addMessage(newMessage);
  }

  // Use appendMessageDelta for efficient delta updates
  messageStore.appendMessageDelta(entry.messageId, delta);
  messageStore.updateMessage(entry.messageId, {
    streaming: true,
    itemId: itemId ?? undefined,
    outputIndex,
  });

  useStreamingStore.setState({ streamingOutputs: mapping });
}

function handleTextDone(data: any) {
  if (!data) {
    return;
  }
  
  const outputIndex = typeof data.output_index === "number" ? data.output_index : 0;
  const text = typeof data.text === "string" ? data.text : "";
  const itemId = data.item_id ?? null;

  const streamingStore = useStreamingStore.getState();
  const messageStore = useMessageStore.getState();
  
  const mapping = { ...streamingStore.streamingOutputs };
  let entry = mapping[outputIndex];

  if (!entry) {
    entry = { messageId: createId(), itemId };
  }

  delete mapping[outputIndex];

  const existingMessage = messageStore.messages.find(m => m.id === entry!.messageId);
  if (existingMessage) {
    messageStore.updateMessage(entry.messageId, {
      content: text || existingMessage.content,
      streaming: false,
      itemId: itemId ?? undefined,
      outputIndex,
    });
  }

  if (text) {
    addStatusUpdate("Assistant response finalized", "info");
  }

  useStreamingStore.setState({ streamingOutputs: mapping });
}

// ============================================
// Tool Call Event Handlers
// ============================================

function handleOutputItemAdded(data: any) {
  if (!data || !data.item) {
    return;
  }

  const item = data.item;
  const type = item.type;
  if (type !== "function_call" && type !== "mcp_call") {
    return;
  }

  const outputIndex = typeof data.output_index === "number" ? data.output_index : undefined;
  const itemId = item.id ?? item.item_id ?? createId();
  const toolCallStore = useToolCallStore.getState();
  
  const existing = toolCallStore.getToolCall(itemId);
  const toolCall: ToolCallState = {
    itemId,
    type: type === "function_call" ? "function" : "mcp",
    status: "in_progress",
    name: item.name ?? existing?.name,
    arguments: existing?.arguments ?? null,
    result: item.output ? JSON.stringify(item.output) : existing?.result ?? null,
    outputIndex: outputIndex ?? existing?.outputIndex,
    error: item.error ? JSON.stringify(item.error) : existing?.error ?? null,
    updatedAt: Date.now(),
  };

  if (existing) {
    toolCallStore.updateToolCall(itemId, toolCall);
  } else {
    toolCallStore.addToolCall(toolCall);
  }
}

function handleOutputItemDone(data: any) {
  if (!data || !data.item) {
    return;
  }

  const item = data.item;
  const type = item.type;
  if (type !== "function_call" && type !== "mcp_call") {
    return;
  }

  const outputIndex = typeof data.output_index === "number" ? data.output_index : undefined;
  const itemId = item.id ?? item.item_id ?? createId();
  const toolCallStore = useToolCallStore.getState();
  
  const existing = toolCallStore.getToolCall(itemId);
  const updates: Partial<ToolCallState> = {
    name: item.name ?? existing?.name,
    type: type === "function_call" ? "function" : "mcp",
    status: item.status === "completed" ? "completed" : existing?.status ?? "completed",
    result: item.output ? JSON.stringify(item.output) : existing?.result ?? null,
    outputIndex: outputIndex ?? existing?.outputIndex,
    error: item.error ? JSON.stringify(item.error) : existing?.error ?? null,
  };

  if (existing) {
    toolCallStore.updateToolCall(itemId, updates);
  } else {
    toolCallStore.addToolCall({
      itemId,
      ...updates,
      arguments: null,
      updatedAt: Date.now(),
    } as ToolCallState);
  }
}

function updateToolCallArguments(
  data: any,
  type: "function" | "mcp",
  finalize = false,
) {
  if (!data) {
    return;
  }

  const itemId = data.item_id ?? createId();
  const delta = typeof data.delta === "string" ? data.delta : "";
  const argumentsJson = typeof data.arguments === "string" ? data.arguments : undefined;
  const outputIndex = typeof data.output_index === "number" ? data.output_index : undefined;

  console.log(`🔍 updateToolCallArguments - itemId: ${itemId}, finalize: ${finalize}, argumentsJson: ${argumentsJson}, delta: ${delta}`);

  const toolCallStore = useToolCallStore.getState();
  const existing = toolCallStore.getToolCall(itemId);

  const combined = finalize
    ? argumentsJson ?? existing?.arguments ?? ""
    : `${existing?.arguments ?? ""}${delta}`;

  console.log(`🔍 updateToolCallArguments - existing.arguments: ${existing?.arguments}, combined: ${combined}`);

  const updates: Partial<ToolCallState> = {
    type,
    arguments: combined,
    outputIndex: outputIndex ?? existing?.outputIndex,
  };

  if (existing) {
    toolCallStore.updateToolCall(itemId, updates);
  } else {
    toolCallStore.addToolCall({
      itemId,
      ...updates,
      status: "in_progress",
      result: null,
      error: null,
      updatedAt: Date.now(),
    } as ToolCallState);
  }
}

function updateToolCallStatus(
  data: any,
  status: "in_progress" | "completed" | "failed",
  error: string | null = null,
) {
  if (!data) {
    return;
  }

  const itemId = data.item_id ?? createId();
  const outputIndex = typeof data.output_index === "number" ? data.output_index : undefined;

  const toolCallStore = useToolCallStore.getState();
  const existing = toolCallStore.getToolCall(itemId);

  const updates: Partial<ToolCallState> = {
    status,
    error,
    outputIndex: outputIndex ?? existing?.outputIndex,
  };

  if (existing) {
    toolCallStore.updateToolCall(itemId, updates);
  } else {
    toolCallStore.addToolCall({
      itemId,
      ...updates,
      type: "mcp",
      arguments: null,
      result: null,
      updatedAt: Date.now(),
    } as ToolCallState);
  }

  if (status === "in_progress") {
    addStatusUpdate("Tool call started", "info", data?.tool_name ?? itemId);
  } else if (status === "completed") {
    addStatusUpdate("Tool call completed", "success", data?.tool_name ?? itemId);
  } else if (status === "failed") {
    addStatusUpdate("Tool call failed", "error", error ?? data?.tool_name ?? itemId);
  }
}

function handleMcpApprovalRequest(data: any) {
  if (!data) {
    return;
  }
  
  const approvalRequest: ApprovalRequest = {
    approvalRequestId: data.approval_request_id ?? "",
    serverLabel: data.server_label ?? "",
    toolName: data.tool_name ?? "",
    arguments: data.arguments,
  };
  
  const toolCallStore = useToolCallStore.getState();
  toolCallStore.setPendingApproval(approvalRequest);
  addStatusUpdate("Tool approval required", "warning", approvalRequest.toolName || approvalRequest.serverLabel);
}
