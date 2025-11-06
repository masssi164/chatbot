import { create } from "zustand";

/**
 * Tool Call Store for managing tool execution tracking
 * Extracted from chatStore for better maintainability
 */
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

export interface ApprovalRequest {
  approvalRequestId: string;
  serverLabel: string;
  toolName: string;
  arguments?: string;
}

export interface ToolCallStoreState {
  toolCalls: ToolCallState[];
  toolCallIndex: Record<string, ToolCallState>; // For fast lookup
  pendingApprovalRequest: ApprovalRequest | null;

  // Actions
  addToolCall: (toolCall: ToolCallState) => void;
  updateToolCall: (itemId: string, updates: Partial<ToolCallState>) => void;
  removeToolCall: (itemId: string) => void;
  getToolCall: (itemId: string) => ToolCallState | undefined;
  clearToolCalls: () => void;
  setPendingApproval: (request: ApprovalRequest | null) => void;
  approveToolExecution: (approvalRequestId: string, approved: boolean) => Promise<void>;
}

export const useToolCallStore = create<ToolCallStoreState>((set, get) => ({
  toolCalls: [],
  toolCallIndex: {},
  pendingApprovalRequest: null,

  addToolCall: (toolCall: ToolCallState) => {
    set((state) => {
      // Check if tool call already exists
      if (state.toolCallIndex[toolCall.itemId]) {
        return state; // Don't add duplicate
      }

      return {
        toolCalls: [...state.toolCalls, toolCall],
        toolCallIndex: {
          ...state.toolCallIndex,
          [toolCall.itemId]: toolCall,
        },
      };
    });
  },

  updateToolCall: (itemId: string, updates: Partial<ToolCallState>) => {
    set((state) => {
      const existingIndex = state.toolCalls.findIndex((tc) => tc.itemId === itemId);
      if (existingIndex === -1) {
        console.warn(`Tool call ${itemId} not found for update`);
        return state;
      }

      const updatedToolCalls = [...state.toolCalls];
      updatedToolCalls[existingIndex] = {
        ...updatedToolCalls[existingIndex],
        ...updates,
        updatedAt: Date.now(),
      };

      return {
        toolCalls: updatedToolCalls,
        toolCallIndex: {
          ...state.toolCallIndex,
          [itemId]: updatedToolCalls[existingIndex],
        },
      };
    });
  },

  removeToolCall: (itemId: string) => {
    set((state) => {
      const { [itemId]: _, ...remainingIndex } = state.toolCallIndex;
      return {
        toolCalls: state.toolCalls.filter((tc) => tc.itemId !== itemId),
        toolCallIndex: remainingIndex,
      };
    });
  },

  getToolCall: (itemId: string) => {
    return get().toolCallIndex[itemId];
  },

  clearToolCalls: () => {
    set({ toolCalls: [], toolCallIndex: {}, pendingApprovalRequest: null });
  },

  setPendingApproval: (request: ApprovalRequest | null) => {
    set({ pendingApprovalRequest: request });
  },

  approveToolExecution: async (approvalRequestId: string, approved: boolean) => {
    try {
      // Send approval decision to backend
      await fetch(`/api/responses/approval/${approvalRequestId}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ approved }),
      });

      set({ pendingApprovalRequest: null });
      console.log(`Tool ${approved ? "approved" : "denied"}`);
    } catch (error) {
      console.error("Failed to approve tool:", error);
      throw error;
    }
  },
}));
