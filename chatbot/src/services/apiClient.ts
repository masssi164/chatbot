
const DEFAULT_API_BASE = "/api";

function resolveBaseUrl() {
  const raw = import.meta.env.VITE_BACKEND_API;
  if (typeof raw === "string" && raw.trim().length > 0) {
    return raw.replace(/\/+$/, "");
  }
  return DEFAULT_API_BASE;
}

const API_BASE = resolveBaseUrl();

export interface McpCapabilities {
  tools: ToolInfo[];
  resources: ResourceInfo[];
  prompts: PromptInfo[];
  serverInfo: ServerInfo;
}

export interface ToolInfo {
  name: string;
  description?: string;
  inputSchema?: unknown;
}

export interface ResourceInfo {
  uri: string;
  name?: string;
  description?: string;
  mimeType?: string;
}

export interface PromptInfo {
  name: string;
  description?: string;
  arguments: PromptArgument[];
}

export interface PromptArgument {
  name: string;
  description?: string;
  required: boolean;
}

export interface ServerInfo {
  name: string;
  version: string;
}

export class ApiError extends Error {
  status: number;
  payload?: unknown;

  constructor(message: string, status: number, payload?: unknown) {
    super(message);
    this.status = status;
    this.payload = payload;
    this.name = "ApiError";
  }
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    let payload: unknown;

    try {
      const clone = response.clone();
      payload = await clone.json();
      if (
        payload &&
        typeof payload === "object" &&
        "message" in payload &&
        typeof (payload as { message?: unknown }).message === "string"
      ) {
        message = (payload as { message: string }).message;
      }
    } catch {
      try {
        const clone = response.clone();
        const text = await clone.text();
        if (text?.trim()) {
          message = text.trim();
          payload = text;
        }
      } catch {
        // ignore
      }
    }

    throw new ApiError(message, response.status, payload);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

function buildUrl(path: string) {
  if (path.startsWith("http://") || path.startsWith("https://")) {
    return path;
  }
  const normalized = path.startsWith("/") ? path : `/${path}`;
  return `${API_BASE}${normalized}`;
}

const defaultHeaders = {
  "Content-Type": "application/json",
};

export interface BackendChatSummary {
  chatId: string;
  title: string | null;
  createdAt: string;
  updatedAt: string | null;
  messageCount: number;
}

export interface BackendChatMessage {
  messageId: string;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  createdAt: string;
}

export interface BackendChat {
  chatId: string;
  title: string | null;
  systemPrompt: string | null;
  titleModel: string | null;
  createdAt: string;
  updatedAt: string | null;
  messages: BackendChatMessage[];
}

export interface BackendChatMessageRequest {
  messageId: string;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  createdAt?: string | null;
}

export interface BackendCompletionParameters {
  temperature?: number | null;
  maxTokens?: number | null;
  topP?: number | null;
  presencePenalty?: number | null;
  frequencyPenalty?: number | null;
}

export interface BackendCreateChatRequest {
  chatId?: string;
  title?: string | null;
  model: string;
  systemPrompt?: string | null;
  titleModel?: string | null;
  parameters?: BackendCompletionParameters | null;
  messages?: BackendChatMessageRequest[];
}

export interface BackendUpdateChatRequest {
  title?: string | null;
  systemPrompt?: string | null;
  titleModel?: string | null;
}

export interface BackendChatCompletionRequest {
  message: BackendChatMessageRequest;
  model: string;
  systemPrompt?: string | null;
  parameters?: BackendCompletionParameters | null;
}

export type McpTransportType = "SSE" | "STREAMABLE_HTTP";

export interface BackendMcpServer {
  serverId: string;
  name: string;
  baseUrl: string;
  apiKey?: string | null;
  status: "IDLE" | "CONNECTING" | "CONNECTED" | "ERROR";
  transport: McpTransportType;
  lastUpdated: string;
}

export interface BackendMcpServerRequest {
  serverId?: string;
  name: string;
  baseUrl: string;
  apiKey?: string | null;
  status?: BackendMcpServer["status"];
  transport?: McpTransportType;
}

export interface OpenAiModelList {
  data: { id: string }[];
}

export interface BackendN8nConnection {
  baseUrl: string;
  configured: boolean;
  updatedAt?: string | null;
}

export interface BackendN8nConnectionRequest {
  baseUrl: string;
  apiKey: string;
}

export interface BackendN8nConnectionStatus {
  connected: boolean;
  message: string;
}

export interface McpConnectionStatus {
  status: "IDLE" | "CONNECTING" | "CONNECTED" | "ERROR";
  toolCount: number;
  message: string | null;
}

export interface BackendN8nWorkflowSummary {
  id: string;
  name: string;
  active: boolean;
  updatedAt?: string | null;
  tagIds: string[];
}

export interface BackendN8nWorkflowList {
  items: BackendN8nWorkflowSummary[];
  nextCursor?: string | null;
}

export const apiClient = {
  listChats(): Promise<BackendChatSummary[]> {
    return fetch(buildUrl("/chats")).then((response) =>
      handleResponse<BackendChatSummary[]>(response),
    );
  },

  updateChat(
    chatId: string,
    payload: BackendUpdateChatRequest,
  ): Promise<BackendChat> {
    return fetch(buildUrl(`/chats/${encodeURIComponent(chatId)}`), {
      method: "PUT",
      headers: defaultHeaders,
      body: JSON.stringify(payload),
    }).then((response) => handleResponse<BackendChat>(response));
  },

  getChat(chatId: string): Promise<BackendChat> {
    return fetch(buildUrl(`/chats/${encodeURIComponent(chatId)}`)).then(
      (response) => handleResponse<BackendChat>(response),
    );
  },

  createChat(payload: BackendCreateChatRequest): Promise<BackendChat> {
    return fetch(buildUrl("/chats"), {
      method: "POST",
      headers: defaultHeaders,
      body: JSON.stringify(payload),
    }).then((response) => handleResponse<BackendChat>(response));
  },

  sendChatMessage(
    chatId: string,
    payload: BackendChatCompletionRequest,
  ): Promise<BackendChat> {
    return fetch(buildUrl(`/chats/${encodeURIComponent(chatId)}/messages`), {
      method: "POST",
      headers: defaultHeaders,
      body: JSON.stringify(payload),
    }).then((response) => handleResponse<BackendChat>(response));
  },

  deleteChat(chatId: string): Promise<void> {
    return fetch(buildUrl(`/chats/${encodeURIComponent(chatId)}`), {
      method: "DELETE",
    }).then((response) => handleResponse<void>(response));
  },

  listMcpServers(): Promise<BackendMcpServer[]> {
    return fetch(buildUrl("/mcp-servers")).then((response) =>
      handleResponse<BackendMcpServer[]>(response),
    );
  },

  upsertMcpServer(payload: BackendMcpServerRequest): Promise<BackendMcpServer> {
    return fetch(buildUrl("/mcp-servers"), {
      method: "POST",
      headers: defaultHeaders,
      body: JSON.stringify(payload),
    }).then((response) => handleResponse<BackendMcpServer>(response));
  },

  updateMcpServer(
    serverId: string,
    payload: BackendMcpServerRequest,
  ): Promise<BackendMcpServer> {
    return fetch(buildUrl(`/mcp-servers/${encodeURIComponent(serverId)}`), {
      method: "PUT",
      headers: defaultHeaders,
      body: JSON.stringify(payload),
    }).then((response) => handleResponse<BackendMcpServer>(response));
  },

  deleteMcpServer(serverId: string): Promise<void> {
    return fetch(buildUrl(`/mcp-servers/${encodeURIComponent(serverId)}`), {
      method: "DELETE",
    }).then((response) => handleResponse<void>(response));
  },

  verifyMcpServer(serverId: string): Promise<McpConnectionStatus> {
    return fetch(buildUrl(`/mcp-servers/${encodeURIComponent(serverId)}/verify`), {
      method: "POST",
      headers: defaultHeaders,
    }).then((response) => handleResponse<McpConnectionStatus>(response));
  },

  getMcpCapabilities(serverId: string): Promise<McpCapabilities> {
    return fetch(buildUrl(`/mcp/servers/${encodeURIComponent(serverId)}/capabilities`), {
      method: "GET",
      headers: defaultHeaders,
    }).then((response) => handleResponse<McpCapabilities>(response));
  },

  listModels(): Promise<OpenAiModelList> {
    return fetch(buildUrl("/openai/models"), {
      headers: {
        Accept: "application/json",
      },
    }).then((response) => handleResponse<OpenAiModelList>(response));
  },

  getN8nConnection(): Promise<BackendN8nConnection> {
    return fetch(buildUrl("/n8n/connection")).then((response) =>
      handleResponse<BackendN8nConnection>(response),
    );
  },

  updateN8nConnection(
    payload: BackendN8nConnectionRequest,
  ): Promise<BackendN8nConnection> {
    return fetch(buildUrl("/n8n/connection"), {
      method: "PUT",
      headers: defaultHeaders,
      body: JSON.stringify(payload),
    }).then((response) => handleResponse<BackendN8nConnection>(response));
  },

  testN8nConnection(): Promise<BackendN8nConnectionStatus> {
    return fetch(buildUrl("/n8n/connection/test"), {
      method: "POST",
    }).then((response) => handleResponse<BackendN8nConnectionStatus>(response));
  },

  listN8nWorkflows(params?: {
    limit?: number;
    cursor?: string;
    active?: boolean;
  }): Promise<BackendN8nWorkflowList> {
    const search = new URLSearchParams();
    if (typeof params?.limit === "number" && params.limit > 0) {
      search.set("limit", Math.trunc(params.limit).toString());
    }
    if (params?.cursor) {
      search.set("cursor", params.cursor);
    }
    if (typeof params?.active === "boolean") {
      search.set("active", String(params.active));
    }
    const suffix = search.toString();
    const path = suffix ? `/n8n/workflows?${suffix}` : "/n8n/workflows";
    return fetch(buildUrl(path)).then((response) =>
      handleResponse<BackendN8nWorkflowList>(response),
    );
  },
};
