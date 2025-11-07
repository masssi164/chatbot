import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import { useStreamingStore } from "./streamingStore";
import { useMessageStore } from "./messageStore";
import { useToolCallStore } from "./toolCallStore";
import { useConversationStore } from "./conversationStore";
import { useConfigStore } from "./configStore";
import { apiClient } from "../services/apiClient";
import { fetchEventSource } from "@microsoft/fetch-event-source";

// Mock dependencies
vi.mock("@microsoft/fetch-event-source");
vi.mock("../services/apiClient", () => ({
  apiClient: {
    addMessage: vi.fn(),
    setToolApprovalPolicy: vi.fn(),
  },
}));

describe("streamingStore", () => {
  beforeEach(() => {
    // Reset all stores
    useStreamingStore.setState({
      isStreaming: false,
      streamError: undefined,
      controller: null,
      responseId: null,
      conversationStatus: "CREATED",
      completionReason: null,
      streamingOutputs: {},
    });

    useMessageStore.setState({
      messages: [],
      loading: false,
      error: null,
    });

    useToolCallStore.setState({
      toolCalls: [],
      pendingApprovalRequest: null,
    });

    useConversationStore.setState({
      conversations: [],
      conversationId: null,
      conversationTitle: null,
      loading: false,
      error: null,
    });

    useConfigStore.setState({
      model: "gpt-4o",
      temperature: 0.7,
      maxTokens: 2000,
      topP: undefined,
      presencePenalty: undefined,
      frequencyPenalty: undefined,
      systemPrompt: undefined,
    });

    // Reset mocks
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("initial state", () => {
    it("should have correct initial state", () => {
      const state = useStreamingStore.getState();
      expect(state.isStreaming).toBe(false);
      expect(state.streamError).toBeUndefined();
      expect(state.controller).toBeNull();
      expect(state.responseId).toBeNull();
      expect(state.conversationStatus).toBe("CREATED");
      expect(state.completionReason).toBeNull();
      expect(state.streamingOutputs).toEqual({});
    });
  });

  describe("reset", () => {
    it("should reset all state to initial values", () => {
      // Set some state
      useStreamingStore.setState({
        isStreaming: true,
        streamError: "test error",
        responseId: "test-id",
        conversationStatus: "STREAMING",
        completionReason: "test",
        streamingOutputs: { 0: { messageId: "msg-1", itemId: "item-1" } },
      });

      // Reset
      useStreamingStore.getState().reset();

      const state = useStreamingStore.getState();
      expect(state.isStreaming).toBe(false);
      expect(state.streamError).toBeUndefined();
      expect(state.controller).toBeNull();
      expect(state.responseId).toBeNull();
      expect(state.conversationStatus).toBe("CREATED");
      expect(state.completionReason).toBeNull();
      expect(state.streamingOutputs).toEqual({});
    });
  });

  describe("abortStreaming", () => {
    it("should abort the controller if it exists", () => {
      const mockAbort = vi.fn();
      const controller = { abort: mockAbort } as any;

      useStreamingStore.setState({ controller });
      useStreamingStore.getState().abortStreaming();

      expect(mockAbort).toHaveBeenCalled();
    });

    it("should do nothing if controller is null", () => {
      useStreamingStore.setState({ controller: null });
      
      // Should not throw
      expect(() => useStreamingStore.getState().abortStreaming()).not.toThrow();
    });
  });

  describe("sendMessage", () => {
    beforeEach(() => {
      // Set up conversation
      useConversationStore.setState({
        conversationId: "conv-123",
        conversationTitle: "Test Conversation",
      });

      vi.mocked(apiClient.addMessage).mockResolvedValue(undefined);
    });

    it("should reject empty messages", async () => {
      await useStreamingStore.getState().sendMessage("");
      
      // Should not add message or start streaming
      expect(useMessageStore.getState().messages).toHaveLength(0);
      expect(useStreamingStore.getState().isStreaming).toBe(false);
    });

    it("should reject whitespace-only messages", async () => {
      await useStreamingStore.getState().sendMessage("   \n  ");
      
      expect(useMessageStore.getState().messages).toHaveLength(0);
      expect(useStreamingStore.getState().isStreaming).toBe(false);
    });

    it("should throw if already streaming", async () => {
      useStreamingStore.setState({ isStreaming: true });

      await expect(
        useStreamingStore.getState().sendMessage("Hello")
      ).rejects.toThrow("Streaming already in progress");
    });

    it("should throw if conversation creation fails", async () => {
      useConversationStore.setState({ conversationId: null });
      
      const ensureConversation = vi.fn().mockResolvedValue(undefined);
      useConversationStore.setState({ ensureConversation } as any);

      await expect(
        useStreamingStore.getState().sendMessage("Hello")
      ).rejects.toThrow("Failed to create conversation");
    });

    it("should add user message and start streaming", async () => {
      let capturedOnMessage: any;
      let capturedOnOpen: any;

      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        capturedOnMessage = options.onmessage;
        capturedOnOpen = options.onopen;

        // Simulate stream opening
        await capturedOnOpen({ ok: true, status: 200 });

        // Simulate response.created event
        capturedOnMessage({
          event: "response.created",
          data: JSON.stringify({ response: { id: "resp-123" } }),
        });

        // Simulate text delta
        capturedOnMessage({
          event: "response.output_text.delta",
          data: JSON.stringify({
            output_index: 0,
            delta: "Hello",
            item_id: "item-1",
          }),
        });

        // Simulate response completed
        capturedOnMessage({
          event: "response.completed",
          data: JSON.stringify({ response: { id: "resp-123" } }),
        });
      });

      await useStreamingStore.getState().sendMessage("Test message");

      // Verify user message was added
      const messages = useMessageStore.getState().messages;
      expect(messages.length).toBeGreaterThan(0);
      expect(messages[0].role).toBe("user");
      expect(messages[0].content).toBe("Test message");

      // Verify streaming started
      expect(vi.mocked(fetchEventSource)).toHaveBeenCalled();

      // Verify streaming completed
      expect(useStreamingStore.getState().isStreaming).toBe(false);
    });

    it("should handle stream errors", async () => {
      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        await options.onopen({ ok: true, status: 200 });
        options.onerror(new Error("Stream error"));
      });

      await useStreamingStore.getState().sendMessage("Test");

      const state = useStreamingStore.getState();
      expect(state.isStreaming).toBe(false);
      expect(state.streamError).toBeDefined();
    });

    it("should handle abort signal", async () => {
      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        const controller = new AbortController();
        controller.abort();
        throw new DOMException("Aborted", "AbortError");
      });

      await useStreamingStore.getState().sendMessage("Test");

      const state = useStreamingStore.getState();
      expect(state.isStreaming).toBe(false);
    });
  });

  describe("sendApprovalResponse", () => {
    beforeEach(() => {
      useConversationStore.setState({ conversationId: "conv-123" });
      useToolCallStore.setState({
        pendingApprovalRequest: {
          approvalRequestId: "approval-123",
          serverLabel: "test-server",
          toolName: "test-tool",
          arguments: { param: "value" },
        },
      });

      vi.mocked(apiClient.setToolApprovalPolicy).mockResolvedValue(undefined);
    });

    it("should do nothing if no pending approval", async () => {
      useToolCallStore.setState({ pendingApprovalRequest: null });

      await useStreamingStore.getState().sendApprovalResponse(true, false);

      expect(vi.mocked(fetchEventSource)).not.toHaveBeenCalled();
    });

    it("should do nothing if no conversation ID", async () => {
      useConversationStore.setState({ conversationId: null });

      await useStreamingStore.getState().sendApprovalResponse(true, false);

      expect(vi.mocked(fetchEventSource)).not.toHaveBeenCalled();
    });

    it("should send approval without remembering", async () => {
      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        await options.onopen({ ok: true, status: 200 });
        options.onclose?.();
      });

      await useStreamingStore.getState().sendApprovalResponse(true, false);

      expect(apiClient.setToolApprovalPolicy).not.toHaveBeenCalled();
      expect(vi.mocked(fetchEventSource)).toHaveBeenCalled();
      expect(useToolCallStore.getState().pendingApprovalRequest).toBeNull();
    });

    it("should send approval with remember policy (always)", async () => {
      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        await options.onopen({ ok: true, status: 200 });
        options.onclose?.();
      });

      await useStreamingStore.getState().sendApprovalResponse(true, true);

      expect(apiClient.setToolApprovalPolicy).toHaveBeenCalledWith(
        "test-server",
        "test-tool",
        "always"
      );
    });

    it("should send denial with remember policy (never)", async () => {
      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        await options.onopen({ ok: true, status: 200 });
        options.onclose?.();
      });

      await useStreamingStore.getState().sendApprovalResponse(false, true);

      expect(apiClient.setToolApprovalPolicy).toHaveBeenCalledWith(
        "test-server",
        "test-tool",
        "never"
      );
    });

    it("should handle approval stream errors", async () => {
      vi.mocked(fetchEventSource).mockRejectedValue(new Error("Network error"));

      await useStreamingStore.getState().sendApprovalResponse(true, false);

      expect(useStreamingStore.getState().isStreaming).toBe(false);
      expect(useStreamingStore.getState().streamError).toBeDefined();
    });

    it("should handle policy update errors gracefully", async () => {
      vi.mocked(apiClient.setToolApprovalPolicy).mockRejectedValue(
        new Error("Policy update failed")
      );

      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        await options.onopen({ ok: true, status: 200 });
        options.onclose?.();
      });

      // Should not throw
      await expect(
        useStreamingStore.getState().sendApprovalResponse(true, true)
      ).resolves.not.toThrow();
    });
  });

  describe("SSE Event Handlers - Lifecycle", () => {
    let onMessage: any;

    beforeEach(() => {
      useConversationStore.setState({
        conversationId: "conv-123",
        conversationTitle: "Test",
      });

      vi.mocked(apiClient.addMessage).mockResolvedValue(undefined);

      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        onMessage = options.onmessage;
        await options.onopen({ ok: true, status: 200 });
      });
    });

    it("should handle response.created event", async () => {
      await useStreamingStore.getState().sendMessage("Test");

      onMessage({
        event: "response.created",
        data: JSON.stringify({ response: { id: "resp-123" } }),
      });

      const state = useStreamingStore.getState();
      expect(state.responseId).toBe("resp-123");
      expect(state.conversationStatus).toBe("STREAMING");
      expect(state.streamError).toBeUndefined();
    });

    it("should handle response.completed event", async () => {
      await useStreamingStore.getState().sendMessage("Test");

      onMessage({
        event: "response.completed",
        data: JSON.stringify({ response: { id: "resp-123" } }),
      });

      const state = useStreamingStore.getState();
      expect(state.isStreaming).toBe(false);
      expect(state.conversationStatus).toBe("COMPLETED");
      expect(state.completionReason).toBeNull();
    });

    it("should handle response.incomplete event", async () => {
      await useStreamingStore.getState().sendMessage("Test");

      onMessage({
        event: "response.incomplete",
        data: JSON.stringify({
          response: {
            id: "resp-123",
            status_details: { reason: "length" },
          },
        }),
      });

      const state = useStreamingStore.getState();
      expect(state.isStreaming).toBe(false);
      expect(state.conversationStatus).toBe("INCOMPLETE");
      expect(state.completionReason).toBe("length");
    });

    it("should handle response.failed event", async () => {
      await useStreamingStore.getState().sendMessage("Test");

      onMessage({
        event: "response.failed",
        data: JSON.stringify({
          response: {
            id: "resp-123",
            error: { code: "server_error", message: "Internal error" },
          },
        }),
      });

      const state = useStreamingStore.getState();
      expect(state.isStreaming).toBe(false);
      expect(state.conversationStatus).toBe("FAILED");
      expect(state.streamError).toBe("Internal error");
    });

    it("should handle response.error event", async () => {
      await useStreamingStore.getState().sendMessage("Test");

      onMessage({
        event: "response.error",
        data: JSON.stringify({
          error: { code: "rate_limit_exceeded", message: "Too many requests" },
        }),
      });

      const state = useStreamingStore.getState();
      expect(state.streamError).toBe("Rate limit: Too many requests");
    });

    it("should handle critical error event", async () => {
      await useStreamingStore.getState().sendMessage("Test");

      onMessage({
        event: "error",
        data: JSON.stringify({
          error: { code: "critical", message: "Critical failure" },
        }),
      });

      const state = useStreamingStore.getState();
      expect(state.isStreaming).toBe(false);
      expect(state.conversationStatus).toBe("FAILED");
      expect(state.streamError).toBe("Critical failure");
    });
  });

  describe("SSE Event Handlers - Text/Message", () => {
    let onMessage: any;

    beforeEach(async () => {
      useConversationStore.setState({
        conversationId: "conv-123",
        conversationTitle: "Test",
      });

      vi.mocked(apiClient.addMessage).mockResolvedValue(undefined);

      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        onMessage = options.onmessage;
        await options.onopen({ ok: true, status: 200 });
      });

      await useStreamingStore.getState().sendMessage("Test");
    });

    it("should handle response.output_text.delta event", () => {
      onMessage({
        event: "response.output_text.delta",
        data: JSON.stringify({
          output_index: 0,
          delta: "Hello",
          item_id: "item-1",
        }),
      });

      const messages = useMessageStore.getState().messages;
      expect(messages.length).toBeGreaterThan(1); // User message + assistant message
      const assistantMsg = messages.find(m => m.role === "assistant");
      expect(assistantMsg).toBeDefined();
      expect(assistantMsg?.streaming).toBe(true);
    });

    it("should accumulate text deltas", () => {
      onMessage({
        event: "response.output_text.delta",
        data: JSON.stringify({
          output_index: 0,
          delta: "Hello",
          item_id: "item-1",
        }),
      });

      onMessage({
        event: "response.output_text.delta",
        data: JSON.stringify({
          output_index: 0,
          delta: " world",
          item_id: "item-1",
        }),
      });

      const messages = useMessageStore.getState().messages;
      const assistantMsg = messages.find(m => m.role === "assistant");
      expect(assistantMsg?.content).toBe("Hello world");
    });

    it("should handle response.output_text.done event", () => {
      onMessage({
        event: "response.output_text.delta",
        data: JSON.stringify({
          output_index: 0,
          delta: "Hello",
          item_id: "item-1",
        }),
      });

      onMessage({
        event: "response.output_text.done",
        data: JSON.stringify({
          output_index: 0,
          text: "Hello world",
          item_id: "item-1",
        }),
      });

      const messages = useMessageStore.getState().messages;
      const assistantMsg = messages.find(m => m.role === "assistant");
      expect(assistantMsg?.streaming).toBe(false);
      expect(assistantMsg?.content).toBe("Hello world");
    });
  });

  describe("SSE Event Handlers - Tool Calls", () => {
    let onMessage: any;

    beforeEach(async () => {
      useConversationStore.setState({
        conversationId: "conv-123",
        conversationTitle: "Test",
      });

      vi.mocked(apiClient.addMessage).mockResolvedValue(undefined);

      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        onMessage = options.onmessage;
        await options.onopen({ ok: true, status: 200 });
      });

      await useStreamingStore.getState().sendMessage("Test");
    });

    it("should handle response.output_item.added for function_call", () => {
      onMessage({
        event: "response.output_item.added",
        data: JSON.stringify({
          output_index: 0,
          item: {
            id: "item-1",
            type: "function_call",
            name: "test_function",
          },
        }),
      });

      const toolCalls = useToolCallStore.getState().toolCalls;
      expect(toolCalls).toHaveLength(1);
      expect(toolCalls[0].type).toBe("function");
      expect(toolCalls[0].name).toBe("test_function");
      expect(toolCalls[0].status).toBe("in_progress");
    });

    it("should handle response.output_item.added for mcp_call", () => {
      onMessage({
        event: "response.output_item.added",
        data: JSON.stringify({
          output_index: 0,
          item: {
            id: "item-2",
            type: "mcp_call",
            name: "mcp_tool",
          },
        }),
      });

      const toolCalls = useToolCallStore.getState().toolCalls;
      const mcpCall = toolCalls.find(tc => tc.itemId === "item-2");
      expect(mcpCall).toBeDefined();
      expect(mcpCall?.type).toBe("mcp");
      expect(mcpCall?.name).toBe("mcp_tool");
    });

    it("should handle response.function_call_arguments.delta", () => {
      // First add the tool call
      onMessage({
        event: "response.output_item.added",
        data: JSON.stringify({
          output_index: 0,
          item: {
            id: "item-args-1",
            type: "function_call",
            name: "test_function",
          },
        }),
      });

      // Then send argument deltas
      onMessage({
        event: "response.function_call_arguments.delta",
        data: JSON.stringify({
          item_id: "item-args-1",
          delta: '{"param"',
          output_index: 0,
        }),
      });

      const toolCalls = useToolCallStore.getState().toolCalls;
      const toolCall = toolCalls.find(tc => tc.itemId === "item-args-1");
      expect(toolCall).toBeDefined();
      expect(toolCall?.arguments).toContain('{"param"');
    });

    it("should handle response.function_call_arguments.done", () => {
      onMessage({
        event: "response.function_call_arguments.done",
        data: JSON.stringify({
          item_id: "item-3",
          arguments: '{"param": "value"}',
          output_index: 0,
        }),
      });

      const toolCalls = useToolCallStore.getState().toolCalls;
      const toolCall = toolCalls.find(tc => tc.itemId === "item-3");
      expect(toolCall?.arguments).toBe('{"param": "value"}');
    });

    it("should handle response.mcp_call.in_progress", () => {
      onMessage({
        event: "response.mcp_call.in_progress",
        data: JSON.stringify({
          item_id: "item-4",
          output_index: 0,
        }),
      });

      const toolCalls = useToolCallStore.getState().toolCalls;
      const toolCall = toolCalls.find(tc => tc.itemId === "item-4");
      expect(toolCall?.status).toBe("in_progress");
    });

    it("should handle response.mcp_call.completed", () => {
      onMessage({
        event: "response.mcp_call.completed",
        data: JSON.stringify({
          item_id: "item-5",
          output_index: 0,
        }),
      });

      const toolCalls = useToolCallStore.getState().toolCalls;
      const toolCall = toolCalls.find(tc => tc.itemId === "item-5");
      expect(toolCall?.status).toBe("completed");
    });

    it("should handle response.mcp_call.failed", () => {
      onMessage({
        event: "response.mcp_call.failed",
        data: JSON.stringify({
          item_id: "item-6",
          error: "Tool execution failed",
          output_index: 0,
        }),
      });

      const toolCalls = useToolCallStore.getState().toolCalls;
      const toolCall = toolCalls.find(tc => tc.itemId === "item-6");
      expect(toolCall?.status).toBe("failed");
      expect(toolCall?.error).toBe("Tool execution failed");
    });

    it("should handle response.mcp_approval_request", () => {
      onMessage({
        event: "response.mcp_approval_request",
        data: JSON.stringify({
          approval_request_id: "approval-123",
          server_label: "test-server",
          tool_name: "test-tool",
          arguments: { param: "value" },
        }),
      });

      const pending = useToolCallStore.getState().pendingApprovalRequest;
      expect(pending).not.toBeNull();
      expect(pending?.approvalRequestId).toBe("approval-123");
      expect(pending?.serverLabel).toBe("test-server");
      expect(pending?.toolName).toBe("test-tool");
    });

    it("should handle response.output_item.done", () => {
      onMessage({
        event: "response.output_item.done",
        data: JSON.stringify({
          output_index: 0,
          item: {
            id: "item-7",
            type: "function_call",
            name: "test_function",
            status: "completed",
            output: { result: "success" },
          },
        }),
      });

      const toolCalls = useToolCallStore.getState().toolCalls;
      const toolCall = toolCalls.find(tc => tc.itemId === "item-7");
      expect(toolCall?.status).toBe("completed");
      expect(toolCall?.result).toBeDefined();
    });
  });

  describe("SSE Event Handlers - Edge Cases", () => {
    let onMessage: any;

    beforeEach(async () => {
      useConversationStore.setState({
        conversationId: "conv-123",
        conversationTitle: "Test",
      });

      vi.mocked(apiClient.addMessage).mockResolvedValue(undefined);

      vi.mocked(fetchEventSource).mockImplementation(async (url, options: any) => {
        onMessage = options.onmessage;
        await options.onopen({ ok: true, status: 200 });
      });

      await useStreamingStore.getState().sendMessage("Test");
    });

    it("should handle conversation.ready event", () => {
      const setCurrentConversation = vi.fn();
      useConversationStore.setState({ setCurrentConversation } as any);

      onMessage({
        event: "conversation.ready",
        data: JSON.stringify({
          conversation_id: "new-conv-123",
          title: "New Conversation",
        }),
      });

      expect(setCurrentConversation).toHaveBeenCalledWith("new-conv-123", "New Conversation");
    });

    it("should handle empty event payload", () => {
      expect(() => {
        onMessage({
          event: "response.created",
          data: "",
        });
      }).not.toThrow();
    });

    it("should handle invalid JSON in event payload", () => {
      expect(() => {
        onMessage({
          event: "response.created",
          data: "invalid json",
        });
      }).not.toThrow();
    });

    it("should handle missing event data", () => {
      expect(() => {
        onMessage({
          event: "response.created",
          data: JSON.stringify({}),
        });
      }).not.toThrow();
    });

    it("should handle unknown event types", () => {
      expect(() => {
        onMessage({
          event: "unknown.event.type",
          data: JSON.stringify({ some: "data" }),
        });
      }).not.toThrow();
    });
  });
});
