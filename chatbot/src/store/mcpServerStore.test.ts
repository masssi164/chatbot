import { describe, it, expect, beforeEach, vi } from "vitest";
import { useMcpServerStore } from "./mcpServerStore";
import { apiClient } from "../services/apiClient";

// Mock apiClient
vi.mock("../services/apiClient", () => ({
  apiClient: {
    listMcpServers: vi.fn(),
    upsertMcpServer: vi.fn(),
    updateMcpServer: vi.fn(),
    deleteMcpServer: vi.fn(),
    getMcpCapabilities: vi.fn(),
  },
  resolveApiUrl: vi.fn((path: string) => `http://localhost:8080${path}`),
}));

// Mock EventSource
class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onopen: (() => void) | null = null;
  close = vi.fn();
  
  constructor(public url: string) {}
}

global.EventSource = MockEventSource as any;

describe("mcpServerStore", () => {
  beforeEach(() => {
    // Reset store state
    useMcpServerStore.setState({
      servers: [],
      activeServerId: null,
      isSyncing: false,
      sseConnection: null,
    });
    
    // Reset mocks
    vi.clearAllMocks();
  });

  describe("basic state management", () => {
    it("should have initial state", () => {
      const state = useMcpServerStore.getState();
      expect(state.servers).toEqual([]);
      expect(state.activeServerId).toBeNull();
      expect(state.isSyncing).toBe(false);
    });

    it("should set active server when server exists", () => {
      useMcpServerStore.setState({
        servers: [{
          id: "test-1",
          name: "Test Server",
          baseUrl: "http://localhost:5678",
          status: "idle",
          transport: "SSE",
          lastUpdated: Date.now(),
        }],
      });

      useMcpServerStore.getState().setActiveServer("test-1");
      expect(useMcpServerStore.getState().activeServerId).toBe("test-1");
    });

    it("should not set active server when server does not exist", () => {
      useMcpServerStore.setState({ activeServerId: null, servers: [] });
      
      useMcpServerStore.getState().setActiveServer("non-existent");
      expect(useMcpServerStore.getState().activeServerId).toBeNull();
    });
  });

  describe("loadServers", () => {
    it("should load servers from API", async () => {
      const mockServers = [
        {
          serverId: "test-1",
          name: "Test Server",
          baseUrl: "http://localhost:5678",
          status: "IDLE" as const,
          transport: "SSE" as const,
          lastUpdated: "2024-01-01T00:00:00Z",
        },
      ];

      vi.mocked(apiClient.listMcpServers).mockResolvedValue(mockServers);

      await useMcpServerStore.getState().loadServers();

      const state = useMcpServerStore.getState();
      expect(state.servers).toHaveLength(1);
      expect(state.servers[0].id).toBe("test-1");
      expect(state.servers[0].name).toBe("Test Server");
      expect(state.servers[0].status).toBe("idle");
      expect(state.isSyncing).toBe(false);
    });

    it("should handle errors when loading servers", async () => {
      vi.mocked(apiClient.listMcpServers).mockRejectedValue(new Error("Network error"));

      const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

      await useMcpServerStore.getState().loadServers();

      expect(consoleError).toHaveBeenCalled();
      expect(useMcpServerStore.getState().servers).toHaveLength(0);
      expect(useMcpServerStore.getState().isSyncing).toBe(false);
      
      consoleError.mockRestore();
    });
  });

  describe("registerServer", () => {
    it("should register a new server", async () => {
      const mockResponse = {
        serverId: "new-server",
        name: "New Server",
        baseUrl: "http://localhost:5678",
        status: "IDLE" as const,
        transport: "SSE" as const,
        lastUpdated: "2024-01-01T00:00:00Z",
      };

      vi.mocked(apiClient.upsertMcpServer).mockResolvedValue(mockResponse);

      const serverId = await useMcpServerStore.getState().registerServer({
        name: "New Server",
        baseUrl: "http://localhost:5678",
        transport: "SSE",
      });

      expect(serverId).toBe("new-server");
      
      const state = useMcpServerStore.getState();
      expect(state.servers).toHaveLength(1);
      expect(state.servers[0].name).toBe("New Server");
    });

    it("should handle errors when registering server", async () => {
      vi.mocked(apiClient.upsertMcpServer).mockRejectedValue(new Error("Server error"));

      const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

      await expect(
        useMcpServerStore.getState().registerServer({
          name: "New Server",
          baseUrl: "http://localhost:5678",
          transport: "SSE",
        })
      ).rejects.toThrow("Server error");

      consoleError.mockRestore();
    });
  });

  describe("removeServer", () => {
    it("should remove a server", async () => {
      useMcpServerStore.setState({
        servers: [{
          id: "test-1",
          name: "Test Server",
          baseUrl: "http://localhost:5678",
          status: "idle",
          transport: "SSE",
          lastUpdated: Date.now(),
        }],
      });

      vi.mocked(apiClient.deleteMcpServer).mockResolvedValue(undefined);

      await useMcpServerStore.getState().removeServer("test-1");

      expect(useMcpServerStore.getState().servers).toHaveLength(0);
    });

    it("should handle errors when removing server", async () => {
      vi.mocked(apiClient.deleteMcpServer).mockRejectedValue(new Error("Delete failed"));

      // removeServer throws on error since there's no try-catch
      await expect(useMcpServerStore.getState().removeServer("test-1")).rejects.toThrow("Delete failed");
    });
  });

  describe("loadCapabilities", () => {
    it("should load capabilities for a server", async () => {
      useMcpServerStore.setState({
        servers: [{
          id: "test-1",
          name: "Test Server",
          baseUrl: "http://localhost:5678",
          status: "connected", // Must be connected, not idle!
          transport: "SSE",
          lastUpdated: Date.now(),
        }],
      });

      const mockCapabilities = {
        tools: [{ name: "test-tool", description: "A test tool" }],
        resources: [],
        prompts: [],
      };

      vi.mocked(apiClient.getMcpCapabilities).mockResolvedValue(mockCapabilities);

      await useMcpServerStore.getState().loadCapabilities("test-1");

      // Get fresh state after async operation
      const server = useMcpServerStore.getState().servers.find(s => s.id === "test-1");
      expect(server?.capabilities).toEqual(mockCapabilities);
    });

    it("should handle errors when loading capabilities", async () => {
      useMcpServerStore.setState({
        servers: [{
          id: "test-1",
          name: "Test Server",
          baseUrl: "http://localhost:5678",
          status: "connected", // Must be connected!
          transport: "SSE",
          lastUpdated: Date.now(),
        }],
      });
      
      vi.mocked(apiClient.getMcpCapabilities).mockRejectedValue(new Error("Capabilities error"));

      const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

      // Should not throw, just log error
      await useMcpServerStore.getState().loadCapabilities("test-1");

      expect(consoleError).toHaveBeenCalledWith(expect.stringContaining("Failed to load capabilities"), expect.any(Error));
      
      consoleError.mockRestore();
    });
  });

  describe("SSE connection", () => {
    it("should connect to status stream", () => {
      useMcpServerStore.getState().connectToStatusStream();

      const state = useMcpServerStore.getState();
      expect(state.sseConnection).not.toBeNull();
      expect(state.sseConnection?.url).toContain("/mcp/servers/status-stream"); // Actual URL format
    });

    it("should disconnect from status stream", () => {
      useMcpServerStore.getState().connectToStatusStream();
      
      const connection = useMcpServerStore.getState().sseConnection;
      expect(connection).not.toBeNull();

      useMcpServerStore.getState().disconnectFromStatusStream();

      expect(useMcpServerStore.getState().sseConnection).toBeNull();
      expect(connection?.close).toHaveBeenCalled();
    });
  });
});
