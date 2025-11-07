import { describe, it, expect, beforeEach, vi } from "vitest";
import { useN8nStore } from "./n8nStore";
import { apiClient } from "../services/apiClient";

// Mock apiClient
vi.mock("../services/apiClient", () => ({
  apiClient: {
    getN8nConnection: vi.fn(),
    updateN8nConnection: vi.fn(),
    testN8nConnection: vi.fn(),
    getN8nWorkflows: vi.fn(),
  },
  ApiError: class ApiError extends Error {
    constructor(message: string, public status?: number) {
      super(message);
      this.name = "ApiError";
    }
  },
}));

// Mock logger
vi.mock("../utils/logger", () => ({
  default: {
    error: vi.fn(),
    warn: vi.fn(),
    info: vi.fn(),
    debug: vi.fn(),
  },
}));

describe("n8nStore", () => {
  beforeEach(() => {
    // Reset store state
    useN8nStore.setState({
      baseUrl: "",
      configured: false,
      updatedAt: undefined,
      isLoading: false,
      isSaving: false,
      isTesting: false,
      status: null,
      error: undefined,
      workflows: [],
      isLoadingWorkflows: false,
      nextCursor: undefined,
    });

    // Reset mocks
    vi.clearAllMocks();
  });

  describe("basic state management", () => {
    it("should have initial state", () => {
      const state = useN8nStore.getState();
      expect(state.baseUrl).toBe("");
      expect(state.configured).toBe(false);
      expect(state.workflows).toEqual([]);
    });

    it("should reset workflows", () => {
      useN8nStore.setState({
        workflows: [
          {
            id: "1",
            name: "Test",
            active: true,
            updatedAt: Date.now(),
            tagIds: [],
          },
        ],
        nextCursor: "cursor-1",
      });

      useN8nStore.getState().resetWorkflows();

      const state = useN8nStore.getState();
      expect(state.workflows).toEqual([]);
      expect(state.nextCursor).toBeUndefined();
    });

    it("should clear status", () => {
      useN8nStore.setState({
        status: { connected: true, message: "Connected" },
      });

      useN8nStore.getState().clearStatus();

      expect(useN8nStore.getState().status).toBeNull();
    });
  });

  describe("loadConnection", () => {
    it("should load connection successfully", async () => {
      const mockConnection = {
        baseUrl: "http://localhost:5678",
        configured: true,
        updatedAt: Date.now(),
      };

      vi.mocked(apiClient.getN8nConnection).mockResolvedValue(mockConnection);

      await useN8nStore.getState().loadConnection();

      const state = useN8nStore.getState();
      expect(state.baseUrl).toBe("http://localhost:5678");
      expect(state.configured).toBe(true);
      expect(state.isLoading).toBe(false);
    });

    it("should handle errors when loading connection", async () => {
      vi.mocked(apiClient.getN8nConnection).mockRejectedValue(new Error("Network error"));

      await useN8nStore.getState().loadConnection();

      const state = useN8nStore.getState();
      expect(state.error).toBe("Network error"); // Actual implementation preserves error message
      expect(state.isLoading).toBe(false);
    });

    it("should handle null updatedAt", async () => {
      const mockConnection = {
        baseUrl: "http://localhost:5678",
        configured: true,
        updatedAt: null,
      };

      vi.mocked(apiClient.getN8nConnection).mockResolvedValue(mockConnection);

      await useN8nStore.getState().loadConnection();

      expect(useN8nStore.getState().updatedAt).toBeNull();
    });
  });

  describe("updateConnection", () => {
    it("should update connection successfully", async () => {
      const mockPayload = {
        baseUrl: "http://localhost:5678",
        apiKey: "test-key",
      };

      const mockResponse = {
        baseUrl: "http://localhost:5678",
        configured: true,
        updatedAt: Date.now(),
      };

      vi.mocked(apiClient.updateN8nConnection).mockResolvedValue(mockResponse);

      const success = await useN8nStore.getState().updateConnection(mockPayload);

      expect(success).toBe(true);
      
      const state = useN8nStore.getState();
      expect(state.baseUrl).toBe("http://localhost:5678");
      expect(state.configured).toBe(true);
      expect(state.isSaving).toBe(false);
    });

    it("should handle errors when updating connection", async () => {
      const mockPayload = {
        baseUrl: "http://localhost:5678",
        apiKey: "test-key",
      };

      vi.mocked(apiClient.updateN8nConnection).mockRejectedValue(new Error("Update failed"));

      const success = await useN8nStore.getState().updateConnection(mockPayload);

      expect(success).toBe(false);
      
      const state = useN8nStore.getState();
      expect(state.error).toBe("Update failed"); // Actual implementation preserves error message
      expect(state.isSaving).toBe(false);
    });
  });

  describe("testConnection", () => {
    it("should test connection successfully", async () => {
      const mockStatus = {
        connected: true,
        message: "Connection successful",
      };

      vi.mocked(apiClient.testN8nConnection).mockResolvedValue(mockStatus);

      const result = await useN8nStore.getState().testConnection();

      expect(result).toEqual(mockStatus);
      
      const state = useN8nStore.getState();
      expect(state.status).toEqual({
        connected: true,
        message: "Connection successful",
      });
      expect(state.isTesting).toBe(false);
    });

    it("should handle connection test failure", async () => {
      const mockStatus = {
        connected: false,
        message: "Connection failed",
      };

      vi.mocked(apiClient.testN8nConnection).mockResolvedValue(mockStatus);

      const result = await useN8nStore.getState().testConnection();

      expect(result).toEqual(mockStatus);
      
      const state = useN8nStore.getState();
      expect(state.status).toEqual({
        connected: false,
        message: "Connection failed",
      });
    });

    it("should handle errors when testing connection", async () => {
      vi.mocked(apiClient.testN8nConnection).mockRejectedValue(new Error("Test error"));

      const result = await useN8nStore.getState().testConnection();

      expect(result).toBeNull();
      
      const state = useN8nStore.getState();
      expect(state.status).toEqual({ connected: false, message: "Test error" }); // Error goes into status
      expect(state.isTesting).toBe(false);
    });
  });

  describe("loadWorkflows", () => {
    it("should load workflows successfully", async () => {
      // Set configured to true so loadWorkflows actually runs
      useN8nStore.setState({ configured: true });
      
      const mockWorkflows = {
        data: [
          {
            id: "1",
            name: "Test Workflow",
            active: true,
            updatedAt: Date.now(),
            tags: [{ id: "tag1", name: "test" }],
          },
        ],
        nextCursor: "cursor-1",
      };

      vi.mocked(apiClient.getN8nWorkflows).mockResolvedValue(mockWorkflows);

      await useN8nStore.getState().loadWorkflows();

      const state = useN8nStore.getState();
      expect(state.workflows).toHaveLength(1);
      expect(state.workflows[0].name).toBe("Test Workflow");
      expect(state.workflows[0].tagIds).toEqual(["tag1"]);
      expect(state.nextCursor).toBe("cursor-1");
      expect(state.isLoadingWorkflows).toBe(false);
    });

    it("should handle workflows with null updatedAt", async () => {
      // Set configured to true
      useN8nStore.setState({ configured: true });
      
      const mockWorkflows = {
        data: [
          {
            id: "1",
            name: "Test Workflow",
            active: true,
            updatedAt: null,
            tags: [],
          },
        ],
        nextCursor: null,
      };

      vi.mocked(apiClient.getN8nWorkflows).mockResolvedValue(mockWorkflows);

      await useN8nStore.getState().loadWorkflows();

      expect(useN8nStore.getState().workflows[0].updatedAt).toBeNull();
    });

    it("should replace workflows when no cursor is provided", async () => {
      useN8nStore.setState({
        configured: true, // Set configured
        workflows: [
          {
            id: "old",
            name: "Old Workflow",
            active: true,
            updatedAt: null,
            tagIds: [],
          },
        ],
      });

      const mockWorkflows = {
        data: [
          {
            id: "new",
            name: "New Workflow",
            active: true,
            updatedAt: Date.now(),
            tags: [],
          },
        ],
        nextCursor: null,
      };

      vi.mocked(apiClient.getN8nWorkflows).mockResolvedValue(mockWorkflows);

      await useN8nStore.getState().loadWorkflows();

      const state = useN8nStore.getState();
      expect(state.workflows).toHaveLength(1);
      expect(state.workflows[0].id).toBe("new");
    });

    it("should handle errors when loading workflows", async () => {
      // First set configured to true so loadWorkflows actually runs
      useN8nStore.setState({ configured: true });
      
      vi.mocked(apiClient.getN8nWorkflows).mockRejectedValue(new Error("Load failed"));

      await useN8nStore.getState().loadWorkflows();

      const state = useN8nStore.getState();
      expect(state.error).toBe("Load failed"); // Actual implementation preserves error message
      expect(state.isLoadingWorkflows).toBe(false);
    });
  });
});
