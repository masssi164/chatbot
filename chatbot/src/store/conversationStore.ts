import { create } from "zustand";
import { apiClient, type ConversationSummary } from "../services/apiClient";

/**
 * Conversation store for managing conversation CRUD operations
 * Extracted from chatStore for better maintainability
 */
export interface ConversationState {
  conversationId: number | null;
  conversationTitle: string | null;
  conversationSummaries: ConversationSummary[];
  loading: boolean;
  error: string | null;

  // Actions
  ensureConversation: () => Promise<void>;
  loadConversations: () => Promise<void>;
  loadConversation: (conversationId: number) => Promise<void>;
  createConversation: (title?: string) => Promise<number>;
  setCurrentConversation: (conversationId: number | null, title?: string | null) => void;
  reset: () => void;
}

export const useConversationStore = create<ConversationState>((set, get) => ({
  conversationId: null,
  conversationTitle: null,
  conversationSummaries: [],
  loading: false,
  error: null,

  ensureConversation: async () => {
    const { conversationId } = get();
    if (conversationId) return;

    try {
      set({ loading: true, error: null });
      const conversation = await apiClient.createConversation();
      set({
        conversationId: conversation.id,
        conversationTitle: conversation.title,
        loading: false,
      });
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "Failed to create conversation";
      set({ error: errorMessage, loading: false });
      console.error("Failed to create conversation:", error);
    }
  },

  loadConversations: async () => {
    try {
      set({ loading: true, error: null });
      const summaries = await apiClient.listConversations();
      set({ conversationSummaries: summaries, loading: false });
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "Failed to load conversations";
      set({ error: errorMessage, loading: false });
      console.error("Failed to load conversations:", error);
    }
  },

  loadConversation: async (conversationId: number) => {
    try {
      set({ loading: true, error: null });
      const conversation = await apiClient.getConversation(conversationId);
      set({
        conversationId: conversation.id,
        conversationTitle: conversation.title,
        loading: false,
      });
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "Failed to load conversation";
      set({ error: errorMessage, loading: false });
      console.error("Failed to load conversation:", error);
    }
  },

  createConversation: async (title?: string) => {
    try {
      set({ loading: true, error: null });
      const conversation = await apiClient.createConversation({ title: title || null });
      set({
        conversationId: conversation.id,
        conversationTitle: conversation.title,
        loading: false,
      });
      
      // Also refresh the conversations list
      await get().loadConversations();
      
      return conversation.id;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "Failed to create conversation";
      set({ error: errorMessage, loading: false });
      console.error("Failed to create conversation:", error);
      throw error;
    }
  },

  setCurrentConversation: (conversationId: number | null, title?: string | null) => {
    set({ conversationId, conversationTitle: title ?? null });
  },

  reset: () => {
    set({
      conversationId: null,
      conversationTitle: null,
      error: null,
    });
  },
}));
