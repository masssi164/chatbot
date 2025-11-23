import { create } from "zustand";
import {
  apiClient,
  type McpCapabilities,
  type McpServerDto,
  type McpServerRequest,
  type McpTransportType,
} from "../services/apiClient";

export type McpServerStatus = "idle" | "connecting" | "connected" | "error";

export interface McpServer {
  id: string;
  name: string;
  baseUrl: string;
  status: McpServerStatus;
  transport: McpTransportType;
  updatedAt: number;
  requireApproval: "never" | "always";
  capabilities?: McpCapabilities;
  apiKey?: string;
}

interface McpServerState {
  servers: McpServer[];
  activeServerId: string | null;
  isSyncing: boolean;

  loadServers: () => Promise<void>;
  loadCapabilities: (serverId: string) => Promise<void>;
  registerServer: (
    server: Omit<McpServer, "id" | "updatedAt" | "status" | "capabilities"> & {
      id?: string;
      status?: McpServerStatus;
    },
  ) => Promise<string>;
  setActiveServer: (serverId: string) => void;
  updateServer: (
    serverId: string,
    updates: Partial<Omit<McpServer, "id" | "updatedAt" | "capabilities">>,
  ) => Promise<void>;
  removeServer: (serverId: string) => Promise<void>;
}

function normalizeStatus(status?: string): McpServerStatus {
  switch ((status ?? "connected").toLowerCase()) {
    case "connecting":
      return "connecting";
    case "error":
      return "error";
    case "connected":
      return "connected";
    default:
      return "idle";
  }
}

function parseTimestamp(value?: string): number {
  if (!value) {
    return Date.now();
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? Date.now() : parsed;
}

function fromBackend(server: McpServerDto): McpServer {
  return {
    id: server.serverId,
    name: server.name?.trim() || server.serverId,
    baseUrl: server.baseUrl,
    status: normalizeStatus(server.status),
    transport: server.transport,
    updatedAt: parseTimestamp(server.updatedAt ?? server.createdAt),
    requireApproval: server.requireApproval ?? "never",
  };
}

export const useMcpServerStore = create<McpServerState>((set, get) => ({
  servers: [],
  activeServerId: null,
  isSyncing: false,

  loadServers: async () => {
    set({ isSyncing: true });
    try {
      const response = await apiClient.listMcpServers();
      const servers = response
        .map((item) => fromBackend(item))
        .sort((a, b) => b.updatedAt - a.updatedAt);
      set((state) => ({
        servers,
        isSyncing: false,
        activeServerId: state.activeServerId ?? servers[0]?.id ?? null,
      }));
    } catch (error) {
      console.error("Failed to load MCP servers", error);
      set({ isSyncing: false });
    }
  },

  registerServer: async (server) => {
    const trimmedKey = server.apiKey?.trim() ?? "";
    const payload: McpServerRequest = {
      name: server.name.trim(),
      baseUrl: server.baseUrl.trim(),
      transport: server.transport,
      authType: trimmedKey ? "api_key" : "none",
      authValue: trimmedKey || undefined,
      requireApproval: server.requireApproval ?? "never",
    };

    const saved = await apiClient.upsertMcpServer(payload);
    const normalized = fromBackend(saved);
    set((state) => {
      const existing = state.servers.some((item) => item.id === normalized.id);
      const servers = existing
        ? state.servers.map((item) => (item.id === normalized.id ? normalized : item))
        : [normalized, ...state.servers];
      return {
        servers,
        activeServerId: state.activeServerId ?? normalized.id,
      };
    });

    return normalized.id;
  },

  loadCapabilities: async (serverId) => {
    const server = get().servers.find((s) => s.id === serverId);
    if (!server || server.status !== "connected") {
      return;
    }

    try {
      const capabilities = await apiClient.getMcpCapabilities(serverId);
      set((state) => ({
        servers: state.servers.map((item) =>
          item.id === serverId ? { ...item, capabilities } : item,
        ),
      }));
    } catch (error) {
      console.error(`Failed to load capabilities for server ${serverId}`, error);
    }
  },

  setActiveServer: (serverId) =>
    set((state) => ({
      activeServerId: state.servers.some((server) => server.id === serverId)
        ? serverId
        : state.activeServerId,
    })),

  updateServer: async (serverId, updates) => {
    const current = get().servers.find((server) => server.id === serverId);
    if (!current) {
      throw new Error("Server not found");
    }

    const payload: McpServerRequest = {
      serverId,
      name: (updates.name ?? current.name).trim(),
      baseUrl: (updates.baseUrl ?? current.baseUrl).trim(),
      transport: updates.transport ?? current.transport,
      requireApproval: updates.requireApproval ?? current.requireApproval,
    };

    if (updates.apiKey !== undefined) {
      const trimmed = updates.apiKey?.trim() ?? "";
      payload.authType = trimmed ? "api_key" : "none";
      payload.authValue = trimmed || undefined;
    }

    const updated = await apiClient.updateMcpServer(serverId, payload);
    const normalized = fromBackend(updated);
    set((state) => ({
      servers: state.servers.map((item) => (item.id === normalized.id ? normalized : item)),
    }));
  },

  removeServer: async (serverId) => {
    await apiClient.deleteMcpServer(serverId);
    set((state) => {
      const remaining = state.servers.filter((server) => server.id !== serverId);
      return {
        servers: remaining,
        activeServerId:
          state.activeServerId === serverId ? remaining[0]?.id ?? null : state.activeServerId,
      };
    });
  },
}));
