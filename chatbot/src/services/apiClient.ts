import type {
    McpCapabilities,
} from "../types/mcp";

export type {
    McpCapabilities, PromptInfo, ResourceInfo, ToolInfo
} from "../types/mcp";

const DEFAULT_API_BASE = "/api";

export class ApiError extends Error {
  status?: number;
  response?: unknown;
  
  constructor(
    message: string,
    status?: number,
    response?: unknown
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.response = response;
  }
}

function resolveBaseUrl() {
  const raw = import.meta.env.VITE_BACKEND_API;
  if (typeof raw === "string" && raw.trim().length > 0) {
    return raw.replace(/\/+$/, "");
  }
  return DEFAULT_API_BASE;
}

const API_BASE = resolveBaseUrl();

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, init);
  if (!response.ok) {
    const message = await response.text();
    throw new ApiError(
      message || `Request failed with status ${response.status}`,
      response.status
    );
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export interface ConversationSummary {
  id: number;
  title: string | null;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
  responseId?: string | null;
  status?: ConversationStatus;
  completionReason?: string | null;
}

export type Role = "USER" | "ASSISTANT" | "TOOL";

/**
 * Conversation lifecycle status tracking for OpenAI Responses API.
 * 
 * Status transitions:
 * CREATED → STREAMING → (COMPLETED | INCOMPLETE | FAILED)
 * 
 * Matches backend enum: app.chatbot.conversation.ConversationStatus
 */
export type ConversationStatus = 
  | "CREATED"      // Conversation created, no response started yet
  | "STREAMING"    // Response streaming in progress (after response.created event)
  | "COMPLETED"    // Response completed successfully (response.completed event)
  | "INCOMPLETE"   // Response ended before completion, typically due to token limits
  | "FAILED";      // Response failed due to an error

export interface MessageDto {
  id: number;
  role: Role;
  content: string;
  rawJson?: string | null;
  outputIndex?: number | null;
  itemId?: string | null;
  createdAt: string;
}

export interface ToolCallDto {
  id: number;
  type: "FUNCTION" | "MCP";
  name?: string | null;
  callId?: string | null;
  argumentsJson?: string | null;
  resultJson?: string | null;
  status: "IN_PROGRESS" | "COMPLETED" | "FAILED";
  outputIndex?: number | null;
  itemId?: string | null;
  createdAt: string;
}

export interface ConversationDetail {
  id: number;
  title: string | null;
  createdAt: string;
  updatedAt: string;
  messages: MessageDto[];
  toolCalls: ToolCallDto[];
  responseId?: string | null;
  status?: ConversationStatus;
  completionReason?: string | null;
}

export interface CreateConversationRequest {
  title?: string | null;
  messages?: NewMessageRequest[];
}

export interface NewMessageRequest {
  role: Role;
  content: string;
  rawJson?: string | null;
  outputIndex?: number | null;
  itemId?: string | null;
}

export interface AddMessageRequest {
  role: Role;
  content: string;
  rawJson?: string | null;
  outputIndex?: number | null;
  itemId?: string | null;
}

// N8n Types
export interface BackendN8nConnection {
  baseUrl: string;
  configured: boolean;
  updatedAt?: number | null;
}

export interface BackendN8nConnectionRequest {
  baseUrl: string;
  apiKey?: string;
}

export interface BackendN8nConnectionStatus {
  connected: boolean;
  message: string;
}

export interface BackendN8nWorkflowSummary {
  id: string;
  name: string;
  active: boolean;
  updatedAt?: number | null;
  tags?: Array<{ id: string; name: string }>;
}

export interface BackendN8nWorkflowList {
  data: BackendN8nWorkflowSummary[];
  nextCursor?: string | null;
}

// MCP Server Types
export type McpTransportType = "SSE" | "STREAMABLE_HTTP";

export interface McpServerDto {
  serverId: string;
  name?: string | null;
  baseUrl: string;
  transport: McpTransportType;
  status?: "IDLE" | "CONNECTING" | "CONNECTED" | "ERROR";
  createdAt?: string;
  updatedAt?: string;
  requireApproval?: "never" | "always";
  extraHeaders?: string[];
  accessGroups?: string[];
}

export interface McpServerRequest {
  serverId?: string;
  name: string;
  baseUrl: string;
  transport: McpTransportType;
  authType?: "none" | "authorization" | "api_key" | "oauth_client_credentials";
  authValue?: string;
  staticHeaders?: Record<string, string>;
  extraHeaders?: string[];
  accessGroups?: string[];
  requireApproval?: "never" | "always";
}

export const apiClient = {
  // Conversation Management
  listConversations(): Promise<ConversationSummary[]> {
    return request<ConversationSummary[]>("/conversations");
  },

  getConversation(conversationId: number): Promise<ConversationDetail> {
    return request<ConversationDetail>(`/conversations/${conversationId}`);
  },

  createConversation(payload: CreateConversationRequest = {}): Promise<ConversationDetail> {
    return request<ConversationDetail>("/conversations", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });
  },

  addMessage(conversationId: number, payload: AddMessageRequest): Promise<MessageDto> {
    return request<MessageDto>(`/conversations/${conversationId}/messages`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });
  },

  // MCP Server Management
  listMcpServers(): Promise<McpServerDto[]> {
    return request<McpServerDto[]>("/mcp-servers");
  },

  upsertMcpServer(payload: McpServerRequest): Promise<McpServerDto> {
    return request<McpServerDto>("/mcp-servers", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });
  },

  updateMcpServer(serverId: string, payload: McpServerRequest): Promise<McpServerDto> {
    return request<McpServerDto>(`/mcp-servers/${serverId}`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });
  },

  deleteMcpServer(serverId: string): Promise<void> {
    return request<void>(`/mcp-servers/${serverId}`, {
      method: "DELETE",
    });
  },

  getMcpCapabilities(serverId: string): Promise<McpCapabilities> {
    // Capabilities and tool operations live under /api/mcp/*
    return request<McpCapabilities>(`/mcp/servers/${serverId}/capabilities`);
  },



  // N8n Integration
  getN8nConnection(): Promise<BackendN8nConnection> {
    return request<BackendN8nConnection>("/n8n/connection");
  },

  updateN8nConnection(payload: BackendN8nConnectionRequest): Promise<BackendN8nConnection> {
    return request<BackendN8nConnection>("/n8n/connection", {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });
  },

  testN8nConnection(): Promise<BackendN8nConnectionStatus> {
    return request<BackendN8nConnectionStatus>("/n8n/connection/test");
  },

  getN8nWorkflows(params?: {
    limit?: number;
    cursor?: string;
    active?: boolean;
  }): Promise<BackendN8nWorkflowList> {
    const query = new URLSearchParams();
    if (params?.limit) query.set("limit", params.limit.toString());
    if (params?.cursor) query.set("cursor", params.cursor);
    if (params?.active !== undefined) query.set("active", params.active.toString());
    const queryString = query.toString();
    const url = queryString ? `/n8n/workflows?${queryString}` : "/n8n/workflows";
    return request<BackendN8nWorkflowList>(url);
  },

  fetchModels(): Promise<string[]> {
    return request<string[]>("/models");
  },
};
