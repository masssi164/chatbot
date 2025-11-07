import { create } from "zustand";
import { apiClient, type MessageDto } from "../services/apiClient";

export type ChatRole = "user" | "assistant" | "tool";

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

/**
 * Message Store for managing chat messages
 * Extracted from chatStore for better maintainability
 */
export interface MessageState {
  messages: ChatMessage[];
  loading: boolean;
  error: string | null;

  // Actions
  addMessage: (message: ChatMessage) => void;
  updateMessage: (id: string, updates: Partial<ChatMessage>) => void;
  appendMessageDelta: (id: string, delta: string) => void;
  removeMessage: (id: string) => void;
  clearMessages: () => void;
  loadMessagesForConversation: (conversationId: number) => Promise<void>;
  setMessages: (messages: ChatMessage[]) => void;
}

function mapMessage(dto: MessageDto): ChatMessage {
  return {
    id: dto.id.toString(),
    role: dto.role.toLowerCase() as ChatRole,
    content: dto.content,
    createdAt: new Date(dto.createdAt).getTime(),
    rawJson: dto.rawJson ?? null,
    outputIndex: dto.outputIndex ?? null,
    itemId: dto.itemId ?? null,
  };
}

export const useMessageStore = create<MessageState>((set) => ({
  messages: [],
  loading: false,
  error: null,

  addMessage: (message: ChatMessage) => {
    set((state) => ({
      messages: [...state.messages, message],
    }));
  },

  updateMessage: (id: string, updates: Partial<ChatMessage>) => {
    set((state) => ({
      messages: state.messages.map((msg) =>
        msg.id === id ? { ...msg, ...updates } : msg
      ),
    }));
  },

  appendMessageDelta: (id: string, delta: string) => {
    set((state) => ({
      messages: state.messages.map((msg) =>
        msg.id === id
          ? { ...msg, content: msg.content + delta }
          : msg
      ),
    }));
  },

  removeMessage: (id: string) => {
    set((state) => ({
      messages: state.messages.filter((msg) => msg.id !== id),
    }));
  },

  clearMessages: () => {
    set({ messages: [], error: null });
  },

  loadMessagesForConversation: async (conversationId: number) => {
    try {
      set({ loading: true, error: null });
      const conversation = await apiClient.getConversation(conversationId);
      const messages = conversation.messages.map(mapMessage);
      set({ messages, loading: false });
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "Failed to load messages";
      set({ error: errorMessage, loading: false });
      console.error("Failed to load messages:", error);
    }
  },

  setMessages: (messages: ChatMessage[]) => {
    set({ messages });
  },
}));
