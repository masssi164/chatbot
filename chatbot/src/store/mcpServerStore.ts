import { create } from "zustand";
import { apiClient, type McpTransportType, type McpCapabilities } from "../services/apiClient";

export type McpServerStatus = "idle" | "connecting" | "connected" | "error";

export interface McpServer {
  id: string;
  name: string;
  baseUrl: string;
  apiKey?: string;
  status: McpServerStatus;
  transport: McpTransportType;
  lastUpdated: number;
  capabilities?: McpCapabilities;
}

interface McpServerState {
  servers: McpServer[];
  activeServerId: string | null;
  isSyncing: boolean;
  loadServers: () => Promise<void>;
  loadCapabilities: (serverId: string) => Promise<void>;
  registerServer: (
    server: Omit<McpServer, "status" | "lastUpdated" | "id"> & {
      id?: string;
      status?: McpServerStatus;
    },
  ) => Promise<string>;
  setActiveServer: (serverId: string) => void;
  updateServer: (
    serverId: string,
    updates: Partial<Omit<McpServer, "id" | "lastUpdated">>,
  ) => Promise<void>;
  setServerStatus: (serverId: string, status: McpServerStatus) => Promise<void>;
  removeServer: (serverId: string) => Promise<void>;
}

function safeId() {
  if (
    typeof crypto !== "undefined" &&
    typeof crypto.randomUUID === "function"
  ) {
    return crypto.randomUUID();
  }

  return Math.random().toString(36).slice(2);
}

function fromBackend(server: {
  serverId: string;
  name: string;
  baseUrl: string;
  apiKey?: string | null;
  status: "IDLE" | "CONNECTING" | "CONNECTED" | "ERROR";
  transport: McpTransportType;
  lastUpdated: string;
}): McpServer {
  return {
    id: server.serverId,
    name: server.name,
    baseUrl: server.baseUrl,
    apiKey: server.apiKey ?? undefined,
    status: server.status.toLowerCase() as McpServerStatus,
    transport: server.transport,
    lastUpdated: Date.parse(server.lastUpdated),
  };
}

function toBackendStatus(
  status: McpServerStatus,
): "IDLE" | "CONNECTING" | "CONNECTED" | "ERROR" {
  switch (status) {
    case "connecting":
      return "CONNECTING";
    case "connected":
      return "CONNECTED";
    case "error":
      return "ERROR";
    default:
      return "IDLE";
  }
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
        .sort((a: McpServer, b: McpServer) => b.lastUpdated - a.lastUpdated);
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
    const serverId = server.id ?? safeId();
    const payload = {
      serverId,
      name: server.name.trim(),
      baseUrl: server.baseUrl.trim(),
      apiKey: server.apiKey?.trim() || undefined,
      status: toBackendStatus(server.status ?? "idle"),
      transport: server.transport,
    };
    const saved = await apiClient.upsertMcpServer(payload);
    const normalized = fromBackend(saved);
    set((state) => {
      const existing = state.servers.some((item) => item.id === normalized.id);
      const servers = existing
        ? state.servers.map((item: McpServer) =>
            item.id === normalized.id ? normalized : item,
          )
        : [normalized, ...state.servers];
      return {
        servers,
        activeServerId: state.activeServerId ?? normalized.id,
      };
    });

    // Verify connection after registration
    try {
      const verifyResult = await apiClient.verifyMcpServer(serverId);
      const updatedServer = {
        ...normalized,
        status: verifyResult.status.toLowerCase() as McpServerStatus,
      };
      set((state) => ({
        servers: state.servers.map((item: McpServer) =>
          item.id === serverId ? updatedServer : item,
        ),
      }));
      console.log(
        `MCP server ${serverId} verification: ${verifyResult.status}, tools: ${verifyResult.toolCount}`,
      );
    } catch (error) {
      console.error(`Failed to verify MCP server ${serverId}`, error);
      // Update status to error if verification fails
      set((state) => ({
        servers: state.servers.map((item: McpServer) =>
          item.id === serverId ? { ...item, status: "error" } : item,
        ),
      }));
    }

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
        servers: state.servers.map((item: McpServer) =>
          item.id === serverId ? { ...item, capabilities } : item,
        ),
      }));
    } catch (error) {
      console.error(`Failed to load capabilities for server ${serverId}`, error);
    }
  },

  setActiveServer: (serverId) =>
    set((state) => ({
      activeServerId: state.servers.some(
        (server: McpServer) => server.id === serverId,
      )
        ? serverId
        : state.activeServerId,
    })),
  updateServer: async (serverId, updates) => {
    const current = get().servers.find((server) => server.id === serverId);
    if (!current) {
      throw new Error("Server not found");
    }

    const payload = {
      serverId,
      name: (updates.name ?? current.name).trim(),
      baseUrl: (updates.baseUrl ?? current.baseUrl).trim(),
      apiKey:
        updates.apiKey !== undefined
          ? updates.apiKey?.trim() || undefined
          : current.apiKey,
      status: toBackendStatus(updates.status ?? current.status),
    };

    const updated = await apiClient.updateMcpServer(serverId, payload);
    const normalized = fromBackend(updated);
    set((state) => ({
      servers: state.servers.map((item) =>
        item.id === normalized.id ? normalized : item,
      ),
    }));
  },
  setServerStatus: async (serverId, status) => {
    const current = get().servers.find((server) => server.id === serverId);
    if (!current) {
      return;
    }

    set((state) => ({
      servers: state.servers.map((server: McpServer) =>
        server.id === serverId
          ? { ...server, status, lastUpdated: Date.now() }
          : server,
      ),
    }));

    try {
      await apiClient.updateMcpServer(serverId, {
        serverId,
        name: current.name,
        baseUrl: current.baseUrl,
        apiKey: current.apiKey,
        status: toBackendStatus(status),
      });
    } catch (error) {
      console.error("Failed to persist MCP status", error);
    }
  },
  removeServer: async (serverId) => {
    await apiClient.deleteMcpServer(serverId);
    set((state) => {
      const remaining = state.servers.filter(
        (server) => server.id !== serverId,
      );
      return {
        servers: remaining,
        activeServerId:
          state.activeServerId === serverId
            ? (remaining[0]?.id ?? null)
            : state.activeServerId,
      };
    });
  },
}));
