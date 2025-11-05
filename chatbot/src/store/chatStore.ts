import { fetchEventSource } from "@microsoft/fetch-event-source";
import { create } from "zustand";
import {
    apiClient,
    type ConversationDetail,
    type ConversationStatus,
    type ConversationSummary,
    type MessageDto,
    type ToolCallDto,
} from "../services/apiClient";

export type ChatRole = "user" | "assistant" | "tool";

export interface ChatConfig {
  model: string;
  titleModel?: string;
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  presencePenalty?: number;
  frequencyPenalty?: number;
}

export interface ChatSummary {
  id: number;
  title: string | null;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
}

export interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  createdAt: number;
  rawJson?: string | null;
  outputIndex?: number | null;
  itemId?: string | null;
  streaming?: boolean;
}

export interface ToolCallState {
  itemId: string;
  name?: string | null;
  type: "function" | "mcp";
  status: "in_progress" | "completed" | "failed";
  arguments?: string | null;
  result?: string | null;
  outputIndex?: number | null;
  error?: string | null;
  updatedAt: number;
}

interface PrivateState {
  streamingOutputs: Record<number, { messageId: string; itemId?: string | null }>;
  toolCallIndex: Record<string, ToolCallState>;
}

interface ChatState extends PrivateState {
  conversationId: number | null;
  conversationTitle: string | null;
  conversationSummaries: ConversationSummary[];
  messages: ChatMessage[];
  toolCalls: ToolCallState[];
  isStreaming: boolean;
  streamError?: string;
  
  // Response lifecycle tracking (matches backend ConversationStatus)
  responseId?: string | null;
  conversationStatus: ConversationStatus;
  completionReason?: string | null;
  
  model: string;
  availableModels: string[];
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  presencePenalty?: number;
  frequencyPenalty?: number;
  systemPrompt?: string;
  controller: AbortController | null;
  ensureConversation: () => Promise<void>;
  loadConversations: () => Promise<void>;
  loadConversation: (conversationId: number) => Promise<void>;
  sendMessage: (content: string) => Promise<void>;
  abortStreaming: () => void;
  reset: () => void;
  fetchModels: () => Promise<void>;
  setModel: (model: string) => void;
  setTemperature: (value: number | undefined) => void;
  setMaxTokens: (value: number | undefined) => void;
  setTopP: (value: number | undefined) => void;
  setPresencePenalty: (value: number | undefined) => void;
  setFrequencyPenalty: (value: number | undefined) => void;
  setSystemPrompt: (value: string | undefined) => void;
}

function createId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return Math.random().toString(36).slice(2, 12);
}

function mapMessage(dto: MessageDto): ChatMessage {
  return {
    id: dto.id.toString(),
    role: dto.role.toLowerCase() as ChatRole,
    content: dto.content,
    createdAt: new Date(dto.createdAt).getTime(),
    rawJson: dto.rawJson ?? null,
    outputIndex: dto.outputIndex ?? undefined,
    itemId: dto.itemId ?? undefined,
    streaming: false,
  };
}

function mapToolCall(dto: ToolCallDto): ToolCallState {
  return {
    itemId: dto.itemId ?? dto.id.toString(),
    name: dto.name,
    type: dto.type.toLowerCase() as "function" | "mcp",
    status: dto.status.toLowerCase() as ToolCallState["status"],
    arguments: dto.argumentsJson ?? null,
    result: dto.resultJson ?? null,
    outputIndex: dto.outputIndex ?? undefined,
    error: null,
    updatedAt: new Date(dto.createdAt).getTime(),
  };
}

function normalizeToolCalls(map: Record<string, ToolCallState>): ToolCallState[] {
  return Object.values(map).sort((a, b) => a.updatedAt - b.updatedAt);
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

export const useChatStore = create<ChatState>((set, get) => ({
  conversationId: null,
  conversationTitle: null,
  conversationSummaries: [],
  messages: [],
  toolCalls: [],
  isStreaming: false,
  streamError: undefined,
  
  // Response lifecycle tracking
  responseId: null,
  conversationStatus: "CREATED",
  completionReason: null,
  
  model: "gpt-4.1-mini",
  availableModels: [],
  temperature: undefined,
  maxTokens: undefined,
  topP: undefined,
  presencePenalty: undefined,
  frequencyPenalty: undefined,
  systemPrompt: undefined,
  controller: null,
  streamingOutputs: {},
  toolCallIndex: {},

  async ensureConversation() {
    const state = get();
    if (state.conversationId !== null) {
      return;
    }
    const detail = await apiClient.createConversation({});
    applyConversationDetail(detail);
  },

  async loadConversations() {
    const summaries = await apiClient.listConversations();
    set({ conversationSummaries: summaries });
  },

  async loadConversation(conversationId: number) {
    const detail = await apiClient.getConversation(conversationId);
    applyConversationDetail(detail);
  },

  async sendMessage(content: string) {
    console.log("sendMessage called with:", content);
    const trimmed = content.trim();
    if (!trimmed) {
      console.log("Message is empty, aborting");
      return;
    }

    const state = get();
    console.log("Current state:", { 
      conversationId: state.conversationId, 
      isStreaming: state.isStreaming,
      model: state.model 
    });
    
    if (state.isStreaming) {
      throw new Error("Streaming already in progress");
    }

    let conversationId = state.conversationId;
    if (conversationId === null) {
      const detail = await apiClient.createConversation({});
      applyConversationDetail(detail);
      conversationId = detail.id;
    }

    const userMessage: ChatMessage = {
      id: createId(),
      role: "user",
      content: trimmed,
      createdAt: Date.now(),
      streaming: false,
    };

    set((current) => ({
      messages: [...current.messages, userMessage],
      streamError: undefined,
    }));

    await apiClient.addMessage(conversationId!, {
      role: "USER",
      content: trimmed,
    });

    const controller = new AbortController();
    set({ isStreaming: true, controller });

    const currentState = get();
    const requestPayload = {
      conversationId,
      title: currentState.conversationTitle,
      payload: {
        model: currentState.model,
        input: buildInputPayload(currentState.messages, trimmed, currentState.systemPrompt),
        ...(currentState.temperature !== undefined && { temperature: currentState.temperature }),
        ...(currentState.maxTokens !== undefined && { max_output_tokens: currentState.maxTokens }),
        ...(currentState.topP !== undefined && { top_p: currentState.topP }),
        ...(currentState.presencePenalty !== undefined && { presence_penalty: currentState.presencePenalty }),
        ...(currentState.frequencyPenalty !== undefined && { frequency_penalty: currentState.frequencyPenalty }),
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
          handleStreamEvent(event.event ?? "message", event.data, set);
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

  abortStreaming() {
    const controller = get().controller;
    if (controller) {
      controller.abort();
    }
  },

  reset() {
    set({
      conversationId: null,
      conversationTitle: null,
      messages: [],
      toolCalls: [],
      isStreaming: false,
      streamError: undefined,
      controller: null,
      streamingOutputs: {},
      toolCallIndex: {},
      // Clear lifecycle fields (matches backend reset behavior)
      responseId: null,
      conversationStatus: "CREATED",
      completionReason: null,
    });
  },

  async fetchModels() {
    try {
      console.log("Fetching models from backend...");
      const models = await apiClient.fetchModels();
      console.log("Models received:", models);
      
      // Set fallback if empty
      const availableModels = models && models.length > 0 ? models : ["gpt-4.1-mini"];
      set({ availableModels });
      
      // Update model if current one is not in the list
      const state = get();
      if (availableModels.length > 0 && !availableModels.includes(state.model)) {
        console.log(`Current model "${state.model}" not in list, switching to "${availableModels[0]}"`);
        set({ model: availableModels[0] });
      }
    } catch (error) {
      console.error("Failed to fetch models:", error);
      // Set fallback on error
      set({ 
        availableModels: ["gpt-4.1-mini"],
        model: "gpt-4.1-mini"
      });
    }
  },

  setModel(model: string) {
    set({ model });
  },

  setTemperature(value: number | undefined) {
    set({ temperature: value });
  },

  setMaxTokens(value: number | undefined) {
    set({ maxTokens: value });
  },

  setTopP(value: number | undefined) {
    set({ topP: value });
  },

  setPresencePenalty(value: number | undefined) {
    set({ presencePenalty: value });
  },

  setFrequencyPenalty(value: number | undefined) {
    set({ frequencyPenalty: value });
  },

  setSystemPrompt(value: string | undefined) {
    set({ systemPrompt: value });
  },
}));

function applyConversationDetail(detail: ConversationDetail) {
  const messages = detail.messages.map(mapMessage);
  const toolCallIndex: Record<string, ToolCallState> = {};
  detail.toolCalls.map(mapToolCall).forEach((toolCall) => {
    toolCallIndex[toolCall.itemId] = toolCall;
  });

  useChatStore.setState({
    conversationId: detail.id,
    conversationTitle: detail.title,
    messages,
    toolCalls: normalizeToolCalls(toolCallIndex),
    toolCallIndex,
    streamingOutputs: {},
    // Lifecycle fields (matches backend Conversation entity)
    responseId: detail.responseId ?? null,
    conversationStatus: detail.status ?? "CREATED",
    completionReason: detail.completionReason ?? null,
  });
}

function handleStreamEvent(
  eventName: string,
  payload: string,
  set: (partial: Partial<ChatState> | ((state: ChatState) => Partial<ChatState>)) => void,
) {
  if (eventName === "conversation.ready") {
    try {
      const node = JSON.parse(payload);
      useChatStore.setState({
        conversationId: node.conversation_id ?? node.id ?? null,
        conversationTitle: node.title ?? null,
      });
    } catch (error) {
      console.warn("Failed to parse conversation.ready payload", error);
    }
    return;
  }

  let data: any = null;
  if (payload) {
    try {
      data = JSON.parse(payload);
    } catch (error) {
      console.warn(`Failed to parse event payload for ${eventName}`, error);
      return;
    }
  }

  switch (eventName) {
    // Lifecycle events (matches backend ResponseStreamService)
    case "response.created":
      handleResponseCreated(data, set);
      break;
    case "response.completed":
      handleResponseCompleted(data, set);
      break;
    case "response.incomplete":
      handleResponseIncomplete(data, set);
      break;
    case "response.failed":
      handleResponseFailed(data, set);
      break;
    
    // Error events
    case "response.error":
      handleResponseError(data, set);
      break;
    case "error":
      handleCriticalError(data, set);
      break;
    
    // Text output events
    case "response.output_text.delta":
      handleTextDelta(data, set);
      break;
    case "response.output_text.done":
      handleTextDone(data, set);
      break;
    case "response.output_item.added":
      handleOutputItemAdded(data, set);
      break;
    
    // Function/MCP call events
    case "response.function_call_arguments.delta":
      updateToolCallArguments(data, set, "function");
      break;
    case "response.function_call_arguments.done":
      updateToolCallArguments(data, set, "function", true);
      break;
    case "response.mcp_call.arguments.delta":
      updateToolCallArguments(data, set, "mcp");
      break;
    case "response.mcp_call.arguments.done":
      updateToolCallArguments(data, set, "mcp", true);
      break;
    case "response.mcp_call.in_progress":
      updateToolCallStatus(data, set, "in_progress");
      break;
    case "response.mcp_call.completed":
      updateToolCallStatus(data, set, "completed");
      break;
    case "response.mcp_call.failed":
      updateToolCallStatus(data, set, "failed", data?.error ?? null);
      break;
    
    default:
      break;
  }
}

function handleTextDelta(data: any, set: any) {
  if (!data) {
    return;
  }
  const outputIndex = typeof data.output_index === "number" ? data.output_index : 0;
  const delta = typeof data.delta === "string" ? data.delta : "";
  const itemId = data.item_id ?? null;

  if (!delta) {
    return;
  }

  set((state: ChatState) => {
    const mapping = { ...state.streamingOutputs };
    let entry = mapping[outputIndex];
    let messages = state.messages;

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
      messages = [...messages, newMessage];
    }

    messages = messages.map((message) =>
      message.id === entry!.messageId
        ? {
            ...message,
            content: `${message.content}${delta}`,
            streaming: true,
            itemId: itemId ?? message.itemId,
            outputIndex,
          }
        : message,
    );

    return {
      messages,
      streamingOutputs: mapping,
    };
  });
}

function handleTextDone(data: any, set: any) {
  if (!data) {
    return;
  }
  const outputIndex = typeof data.output_index === "number" ? data.output_index : 0;
  const text = typeof data.text === "string" ? data.text : "";
  const itemId = data.item_id ?? null;

  set((state: ChatState) => {
    const mapping = { ...state.streamingOutputs };
    let entry = mapping[outputIndex];

    if (!entry) {
      entry = { messageId: createId(), itemId };
    }

    delete mapping[outputIndex];

    const messages = state.messages.map((message) =>
      message.id === entry!.messageId
        ? {
            ...message,
            content: text || message.content,
            streaming: false,
            itemId: itemId ?? message.itemId,
            outputIndex,
          }
        : message,
    );

    return {
      messages,
      streamingOutputs: mapping,
    };
  });
}

function handleOutputItemAdded(data: any, set: any) {
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

  set((state: ChatState) => {
    const toolCallIndex = { ...state.toolCallIndex };
    const existing = toolCallIndex[itemId] ?? {
      itemId,
      type: type === "function_call" ? "function" : "mcp",
      status: "in_progress" as const,
      updatedAt: Date.now(),
    };

    const updated: ToolCallState = {
      ...existing,
      name: item.name ?? existing.name,
      type: type === "function_call" ? "function" : "mcp",
      status: existing.status,
      arguments: existing.arguments ?? null,
      result: item.output ? JSON.stringify(item.output) : existing.result ?? null,
      outputIndex: outputIndex ?? existing.outputIndex,
      error: item.error ? JSON.stringify(item.error) : existing.error ?? null,
      updatedAt: Date.now(),
    };

    toolCallIndex[itemId] = updated;
    return {
      toolCallIndex,
      toolCalls: normalizeToolCalls(toolCallIndex),
    };
  });
}

function updateToolCallArguments(
  data: any,
  set: any,
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

  set((state: ChatState) => {
    const toolCallIndex = { ...state.toolCallIndex };
    const existing = toolCallIndex[itemId] ?? {
      itemId,
      type,
      status: "in_progress" as const,
      updatedAt: Date.now(),
    };

    const combined = finalize
      ? argumentsJson ?? existing.arguments ?? ""
      : `${existing.arguments ?? ""}${delta}`;

    toolCallIndex[itemId] = {
      ...existing,
      type,
      arguments: combined,
      outputIndex: outputIndex ?? existing.outputIndex,
      updatedAt: Date.now(),
    };

    return {
      toolCallIndex,
      toolCalls: normalizeToolCalls(toolCallIndex),
    };
  });
}

function updateToolCallStatus(
  data: any,
  set: any,
  status: "in_progress" | "completed" | "failed",
  error: string | null = null,
) {
  if (!data) {
    return;
  }

  const itemId = data.item_id ?? createId();
  const outputIndex = typeof data.output_index === "number" ? data.output_index : undefined;

  set((state: ChatState) => {
    const toolCallIndex = { ...state.toolCallIndex };
    const existing = toolCallIndex[itemId] ?? {
      itemId,
      type: "mcp" as const,
      status: "in_progress" as const,
      updatedAt: Date.now(),
    };

    toolCallIndex[itemId] = {
      ...existing,
      status,
      error,
      outputIndex: outputIndex ?? existing.outputIndex,
      updatedAt: Date.now(),
    };

    return {
      toolCallIndex,
      toolCalls: normalizeToolCalls(toolCallIndex),
    };
  });
}

// ============================================
// Lifecycle Event Handlers (matches backend ResponseStreamService)
// ============================================

/**
 * Handle response.created event - sets responseId and transitions to STREAMING state.
 * Backend: ResponseStreamService.handleResponseCreated()
 */
function handleResponseCreated(data: any, set: any) {
  if (!data?.response) {
    return;
  }

  const responseId = data.response.id;
  console.log("✅ Response created:", responseId);

  set({
    responseId,
    conversationStatus: "STREAMING",
    streamError: undefined,
  });
}

/**
 * Handle response.completed event - marks conversation as successfully completed.
 * Backend: ResponseStreamService.handleResponseCompleted()
 */
function handleResponseCompleted(data: any, set: any) {
  if (!data?.response) {
    return;
  }

  const responseId = data.response.id;
  console.log("✅ Response completed:", responseId);

  set({
    isStreaming: false,
    controller: null,
    conversationStatus: "COMPLETED",
    completionReason: null,
    streamError: undefined,
  });
}

/**
 * Handle response.incomplete event - marks conversation as incomplete (e.g., token limit).
 * Backend: ResponseStreamService.handleResponseIncomplete()
 */
function handleResponseIncomplete(data: any, set: any) {
  if (!data?.response) {
    return;
  }

  const reason = data.response.status_details?.reason || "length";
  console.warn("⚠️ Response incomplete:", reason);

  set({
    isStreaming: false,
    controller: null,
    conversationStatus: "INCOMPLETE",
    completionReason: reason,
    streamError: undefined, // Not an error, just incomplete
  });
}

/**
 * Handle response.failed event - marks conversation as failed.
 * Backend: ResponseStreamService.handleResponseFailed()
 */
function handleResponseFailed(data: any, set: any) {
  if (!data?.response) {
    return;
  }

  const error = data.response.error || {};
  const errorCode = error.code || "unknown";
  const errorMessage = error.message || "Response failed";

  console.error("❌ Response failed:", errorCode, errorMessage);

  set({
    isStreaming: false,
    controller: null,
    conversationStatus: "FAILED",
    completionReason: `${errorCode}: ${errorMessage}`,
    streamError: errorMessage,
  });
}

/**
 * Handle response.error event - logs error but doesn't necessarily fail the conversation.
 * Backend: ResponseStreamService.handleResponseError()
 */
function handleResponseError(data: any, set: any) {
  if (!data?.error) {
    return;
  }

  const error = data.error;
  const code = error.code || "unknown";
  const message = error.message || "Error occurred";

  // Special handling for rate limits
  if (code === "rate_limit_exceeded") {
    console.warn("⚠️ Rate limit exceeded:", message);
    set({
      streamError: `Rate limit: ${message}`,
    });
  } else {
    console.error("❌ Response error:", code, message);
    set({
      streamError: message,
    });
  }
}

/**
 * Handle critical error event - fails the conversation immediately.
 * Backend: ResponseStreamService.handleCriticalError()
 */
function handleCriticalError(data: any, set: any) {
  if (!data?.error) {
    return;
  }

  const error = data.error;
  const code = error.code || "unknown";
  const message = error.message || "Critical error";

  console.error("❌ CRITICAL ERROR:", code, message);

  set({
    isStreaming: false,
    controller: null,
    conversationStatus: "FAILED",
    completionReason: `CRITICAL: ${code}`,
    streamError: message,
  });
}
