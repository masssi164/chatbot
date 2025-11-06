import { create } from "zustand";
import {
    ApiError,
    apiClient,
    type BackendN8nConnection,
    type BackendN8nConnectionRequest,
    type BackendN8nConnectionStatus,
    type BackendN8nWorkflowList,
    type BackendN8nWorkflowSummary,
} from "../services/apiClient";
import logger from "../utils/logger";

export interface N8nWorkflow {
  id: string;
  name: string;
  active: boolean;
  updatedAt?: number | null;
  tagIds: string[];
}

interface ConnectionStatus {
  connected: boolean;
  message: string;
}

interface N8nState {
  baseUrl: string;
  configured: boolean;
  updatedAt?: number | null;
  isLoading: boolean;
  isSaving: boolean;
  isTesting: boolean;
  status?: ConnectionStatus | null;
  error?: string;
  workflows: N8nWorkflow[];
  isLoadingWorkflows: boolean;
  nextCursor?: string | null;
  loadConnection: () => Promise<void>;
  updateConnection: (payload: BackendN8nConnectionRequest) => Promise<boolean>;
  testConnection: () => Promise<BackendN8nConnectionStatus | null>;
  loadWorkflows: (options?: {
    limit?: number;
    cursor?: string;
    active?: boolean;
  }) => Promise<void>;
  resetWorkflows: () => void;
  clearStatus: () => void;
}

function mapConnection(dto: BackendN8nConnection) {
  return {
    baseUrl: dto.baseUrl,
    configured: dto.configured,
    updatedAt: dto.updatedAt ?? null,
  };
}

function mapWorkflow(dto: BackendN8nWorkflowSummary): N8nWorkflow {
  return {
    id: dto.id,
    name: dto.name,
    active: dto.active,
    updatedAt: dto.updatedAt ?? null,
    tagIds: dto.tags?.map((tag) => tag.id) ?? [],
  };
}

export const useN8nStore = create<N8nState>((set, get) => ({
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

  async loadConnection() {
    set({ isLoading: true, error: undefined });
    try {
      const data = await apiClient.getN8nConnection();
      const mapped = mapConnection(data);
      set({
        baseUrl: mapped.baseUrl,
        configured: mapped.configured,
        updatedAt: mapped.updatedAt ?? null,
        isLoading: false,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Failed to load connection";
      logger.error("Failed to load n8n connection", error);
      set({ isLoading: false, error: message });
    }
  },

  async updateConnection(payload) {
    set({ isSaving: true, error: undefined, status: null });
    try {
      const data = await apiClient.updateN8nConnection(payload);
      const mapped = mapConnection(data);
      set({
        baseUrl: mapped.baseUrl,
        configured: mapped.configured,
        updatedAt: mapped.updatedAt ?? null,
        isSaving: false,
      });
      if (mapped.configured) {
        await get().loadWorkflows();
      } else {
        set({ workflows: [], nextCursor: undefined });
      }
      return true;
    } catch (error) {
      const message =
        error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Update failed";
      logger.error("Failed to update n8n connection", error);
      set({ isSaving: false, error: message });
      return false;
    }
  },

  async testConnection() {
    set({ isTesting: true, status: null });
    try {
      const status = await apiClient.testN8nConnection();
      set({
        isTesting: false,
        status: { connected: status.connected, message: status.message },
      });
      return status;
    } catch (error) {
      const message =
        error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Test failed";
      logger.error("Failed to test n8n connection", error);
      const status: ConnectionStatus = { connected: false, message };
      set({ isTesting: false, status });
      return null;
    }
  },

  async loadWorkflows(options) {
    const { configured } = get();
    if (!configured) {
      set({ workflows: [], nextCursor: undefined });
      return;
    }
    set({ isLoadingWorkflows: true, error: undefined });
    try {
      const response: BackendN8nWorkflowList = await apiClient.getN8nWorkflows(options);
      const items = response.data?.map(mapWorkflow) ?? [];
      set({
        workflows: items,
        nextCursor: response.nextCursor ?? null,
        isLoadingWorkflows: false,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Failed to load workflows";
      logger.error("Failed to load n8n workflows", error);
      set({ isLoadingWorkflows: false, error: message });
    }
  },

  resetWorkflows() {
    set({ workflows: [], nextCursor: undefined });
  },

  clearStatus() {
    set({ status: null });
  },
}));

export type { BackendN8nConnectionRequest };
