import { describe, it, expect, beforeEach, vi } from "vitest";
import { useMessageStore } from "./messageStore";
import { apiClient } from "../services/apiClient";

// Mock apiClient
vi.mock("../services/apiClient", () => ({
  apiClient: {
    getConversation: vi.fn(),
  },
}));

describe("messageStore", () => {
  beforeEach(() => {
    // Reset store state
    useMessageStore.setState({
      messages: [],
      loading: false,
      error: null,
    });

    // Reset mocks
    vi.clearAllMocks();
  });

  describe("basic state management", () => {
    it("should have initial state", () => {
      const state = useMessageStore.getState();
      expect(state.messages).toEqual([]);
      expect(state.loading).toBe(false);
      expect(state.error).toBeNull();
    });
  });

  describe("addMessage", () => {
    it("should add a message", () => {
      const message = {
        id: "1",
        role: "user" as const,
        content: "Hello",
        createdAt: Date.now(),
      };

      useMessageStore.getState().addMessage(message);

      const state = useMessageStore.getState();
      expect(state.messages).toHaveLength(1);
      expect(state.messages[0]).toEqual(message);
    });

    it("should add multiple messages", () => {
      const message1 = {
        id: "1",
        role: "user" as const,
        content: "Hello",
        createdAt: Date.now(),
      };

      const message2 = {
        id: "2",
        role: "assistant" as const,
        content: "Hi there",
        createdAt: Date.now(),
      };

      useMessageStore.getState().addMessage(message1);
      useMessageStore.getState().addMessage(message2);

      const state = useMessageStore.getState();
      expect(state.messages).toHaveLength(2);
      expect(state.messages[0].id).toBe("1");
      expect(state.messages[1].id).toBe("2");
    });
  });

  describe("updateMessage", () => {
    it("should update a message", () => {
      const message = {
        id: "1",
        role: "user" as const,
        content: "Hello",
        createdAt: Date.now(),
      };

      useMessageStore.getState().addMessage(message);
      useMessageStore.getState().updateMessage("1", { content: "Updated" });

      const state = useMessageStore.getState();
      expect(state.messages[0].content).toBe("Updated");
    });

    it("should not update non-existent message", () => {
      useMessageStore.getState().updateMessage("999", { content: "Updated" });

      const state = useMessageStore.getState();
      expect(state.messages).toHaveLength(0);
    });
  });

  describe("appendMessageDelta", () => {
    it("should append delta to message content", () => {
      const message = {
        id: "1",
        role: "assistant" as const,
        content: "Hello",
        createdAt: Date.now(),
      };

      useMessageStore.getState().addMessage(message);
      useMessageStore.getState().appendMessageDelta("1", " world");

      const state = useMessageStore.getState();
      expect(state.messages[0].content).toBe("Hello world");
    });

    it("should handle multiple deltas", () => {
      const message = {
        id: "1",
        role: "assistant" as const,
        content: "",
        createdAt: Date.now(),
      };

      useMessageStore.getState().addMessage(message);
      useMessageStore.getState().appendMessageDelta("1", "Hello");
      useMessageStore.getState().appendMessageDelta("1", " ");
      useMessageStore.getState().appendMessageDelta("1", "world");

      const state = useMessageStore.getState();
      expect(state.messages[0].content).toBe("Hello world");
    });
  });

  describe("removeMessage", () => {
    it("should remove a message", () => {
      const message = {
        id: "1",
        role: "user" as const,
        content: "Hello",
        createdAt: Date.now(),
      };

      useMessageStore.getState().addMessage(message);
      expect(useMessageStore.getState().messages).toHaveLength(1);

      useMessageStore.getState().removeMessage("1");

      const state = useMessageStore.getState();
      expect(state.messages).toHaveLength(0);
    });

    it("should handle removing non-existent message", () => {
      useMessageStore.getState().removeMessage("999");

      const state = useMessageStore.getState();
      expect(state.messages).toHaveLength(0);
    });
  });

  describe("clearMessages", () => {
    it("should clear all messages", () => {
      useMessageStore.setState({
        messages: [
          {
            id: "1",
            role: "user",
            content: "Hello",
            createdAt: Date.now(),
          },
          {
            id: "2",
            role: "assistant",
            content: "Hi",
            createdAt: Date.now(),
          },
        ],
      });

      useMessageStore.getState().clearMessages();

      const state = useMessageStore.getState();
      expect(state.messages).toEqual([]);
      expect(state.error).toBeNull();
    });
  });

  describe("loadMessagesForConversation", () => {
    it("should load messages successfully", async () => {
      const mockConversation = {
        id: 1,
        title: "Test",
        createdAt: "2024-01-01T00:00:00Z",
        updatedAt: "2024-01-01T00:00:00Z",
        status: "CREATED" as const,
        responseId: null,
        completionReason: null,
        messages: [
          {
            id: 1,
            conversationId: 1,
            role: "USER" as const,
            content: "Hello",
            createdAt: "2024-01-01T00:00:00Z",
            rawJson: null,
            outputIndex: null,
            itemId: null,
          },
        ],
        toolCalls: [],
      };

      vi.mocked(apiClient.getConversation).mockResolvedValue(mockConversation);

      await useMessageStore.getState().loadMessagesForConversation(1);

      const state = useMessageStore.getState();
      expect(state.messages).toHaveLength(1);
      expect(state.messages[0].role).toBe("user");
      expect(state.messages[0].content).toBe("Hello");
      expect(state.loading).toBe(false);
    });

    it("should handle errors when loading messages", async () => {
      vi.mocked(apiClient.getConversation).mockRejectedValue(new Error("Load failed"));

      await useMessageStore.getState().loadMessagesForConversation(1);

      const state = useMessageStore.getState();
      expect(state.error).toBe("Load failed");
      expect(state.loading).toBe(false);
    });
  });

  describe("setMessages", () => {
    it("should set messages directly", () => {
      const messages = [
        {
          id: "1",
          role: "user" as const,
          content: "Hello",
          createdAt: Date.now(),
        },
        {
          id: "2",
          role: "assistant" as const,
          content: "Hi",
          createdAt: Date.now(),
        },
      ];

      useMessageStore.getState().setMessages(messages);

      const state = useMessageStore.getState();
      expect(state.messages).toEqual(messages);
    });

    it("should replace existing messages", () => {
      useMessageStore.setState({
        messages: [
          {
            id: "old",
            role: "user",
            content: "Old",
            createdAt: Date.now(),
          },
        ],
      });

      const newMessages = [
        {
          id: "new",
          role: "user" as const,
          content: "New",
          createdAt: Date.now(),
        },
      ];

      useMessageStore.getState().setMessages(newMessages);

      const state = useMessageStore.getState();
      expect(state.messages).toHaveLength(1);
      expect(state.messages[0].id).toBe("new");
    });
  });
});
