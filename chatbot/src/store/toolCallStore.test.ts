import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useToolCallStore, type ToolCallState } from './toolCallStore';

// Mock fetch for approval tests
(globalThis as any).fetch = vi.fn();

describe('toolCallStore', () => {
  beforeEach(() => {
    // Reset store state before each test
    useToolCallStore.setState({
      toolCalls: [],
      toolCallIndex: {},
      pendingApprovalRequest: null,
    });
    vi.clearAllMocks();
  });

  it('should have default values', () => {
    const state = useToolCallStore.getState();
    expect(state.toolCalls).toEqual([]);
    expect(state.toolCallIndex).toEqual({});
    expect(state.pendingApprovalRequest).toBeNull();
  });

  it('should add a tool call', () => {
    const toolCall: ToolCallState = {
      itemId: 'tc-1',
      name: 'test_tool',
      type: 'function',
      status: 'in_progress',
      updatedAt: Date.now(),
    };

    useToolCallStore.getState().addToolCall(toolCall);

    const state = useToolCallStore.getState();
    expect(state.toolCalls).toHaveLength(1);
    expect(state.toolCalls[0]).toEqual(toolCall);
    expect(state.toolCallIndex['tc-1']).toEqual(toolCall);
  });

  it('should not add duplicate tool call', () => {
    const toolCall: ToolCallState = {
      itemId: 'tc-1',
      name: 'test_tool',
      type: 'function',
      status: 'in_progress',
      updatedAt: Date.now(),
    };

    useToolCallStore.getState().addToolCall(toolCall);
    useToolCallStore.getState().addToolCall(toolCall);

    const state = useToolCallStore.getState();
    expect(state.toolCalls).toHaveLength(1);
  });

  it('should update a tool call', () => {
    const toolCall: ToolCallState = {
      itemId: 'tc-1',
      name: 'test_tool',
      type: 'function',
      status: 'in_progress',
      updatedAt: Date.now(),
    };

    useToolCallStore.getState().addToolCall(toolCall);
    useToolCallStore.getState().updateToolCall('tc-1', {
      status: 'completed',
      result: '{"success": true}',
    });

    const state = useToolCallStore.getState();
    expect(state.toolCalls[0].status).toBe('completed');
    expect(state.toolCalls[0].result).toBe('{"success": true}');
    expect(state.toolCallIndex['tc-1'].status).toBe('completed');
  });

  it('should handle update of non-existent tool call', () => {
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    
    useToolCallStore.getState().updateToolCall('non-existent', { status: 'completed' });

    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('Tool call non-existent not found'));
    consoleSpy.mockRestore();
  });

  it('should remove a tool call', () => {
    const toolCall: ToolCallState = {
      itemId: 'tc-1',
      name: 'test_tool',
      type: 'function',
      status: 'in_progress',
      updatedAt: Date.now(),
    };

    useToolCallStore.getState().addToolCall(toolCall);
    useToolCallStore.getState().removeToolCall('tc-1');

    const state = useToolCallStore.getState();
    expect(state.toolCalls).toHaveLength(0);
    expect(state.toolCallIndex['tc-1']).toBeUndefined();
  });

  it('should get a tool call by itemId', () => {
    const toolCall: ToolCallState = {
      itemId: 'tc-1',
      name: 'test_tool',
      type: 'function',
      status: 'in_progress',
      updatedAt: Date.now(),
    };

    useToolCallStore.getState().addToolCall(toolCall);
    const retrieved = useToolCallStore.getState().getToolCall('tc-1');

    expect(retrieved).toEqual(toolCall);
  });

  it('should return undefined for non-existent tool call', () => {
    const retrieved = useToolCallStore.getState().getToolCall('non-existent');
    expect(retrieved).toBeUndefined();
  });

  it('should clear all tool calls', () => {
    const toolCall1: ToolCallState = {
      itemId: 'tc-1',
      name: 'test_tool_1',
      type: 'function',
      status: 'in_progress',
      updatedAt: Date.now(),
    };
    const toolCall2: ToolCallState = {
      itemId: 'tc-2',
      name: 'test_tool_2',
      type: 'mcp',
      status: 'completed',
      updatedAt: Date.now(),
    };

    useToolCallStore.getState().addToolCall(toolCall1);
    useToolCallStore.getState().addToolCall(toolCall2);
    useToolCallStore.getState().clearToolCalls();

    const state = useToolCallStore.getState();
    expect(state.toolCalls).toHaveLength(0);
    expect(state.toolCallIndex).toEqual({});
    expect(state.pendingApprovalRequest).toBeNull();
  });

  it('should set pending approval request', () => {
    const approvalRequest = {
      approvalRequestId: 'approval-1',
      serverLabel: 'test-server',
      toolName: 'dangerous_tool',
      arguments: '{"param": "value"}',
    };

    useToolCallStore.getState().setPendingApproval(approvalRequest);

    const state = useToolCallStore.getState();
    expect(state.pendingApprovalRequest).toEqual(approvalRequest);
  });

  it('should clear pending approval request', () => {
    const approvalRequest = {
      approvalRequestId: 'approval-1',
      serverLabel: 'test-server',
      toolName: 'dangerous_tool',
    };

    useToolCallStore.getState().setPendingApproval(approvalRequest);
    useToolCallStore.getState().setPendingApproval(null);

    const state = useToolCallStore.getState();
    expect(state.pendingApprovalRequest).toBeNull();
  });

  it('should approve tool execution', async () => {
    const mockFetch = vi.mocked(globalThis.fetch);
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 200 }));

    await useToolCallStore.getState().approveToolExecution('approval-1', true);

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/responses/approval/approval-1',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ approved: true }),
      })
    );

    const state = useToolCallStore.getState();
    expect(state.pendingApprovalRequest).toBeNull();
  });

  it('should deny tool execution', async () => {
    const mockFetch = vi.mocked(globalThis.fetch);
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 200 }));

    await useToolCallStore.getState().approveToolExecution('approval-1', false);

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/responses/approval/approval-1',
      expect.objectContaining({
        body: JSON.stringify({ approved: false }),
      })
    );
  });

  it('should handle approval errors', async () => {
    const mockFetch = vi.mocked(globalThis.fetch);
    mockFetch.mockRejectedValueOnce(new Error('Network error'));

    await expect(
      useToolCallStore.getState().approveToolExecution('approval-1', true)
    ).rejects.toThrow('Network error');
  });

  it('should update updatedAt timestamp when updating tool call', () => {
    const initialTime = Date.now();
    const toolCall: ToolCallState = {
      itemId: 'tc-1',
      name: 'test_tool',
      type: 'function',
      status: 'in_progress',
      updatedAt: initialTime,
    };

    useToolCallStore.getState().addToolCall(toolCall);
    
    // Wait a bit to ensure time difference
    setTimeout(() => {
      useToolCallStore.getState().updateToolCall('tc-1', { status: 'completed' });
      
      const state = useToolCallStore.getState();
      expect(state.toolCalls[0].updatedAt).toBeGreaterThan(initialTime);
    }, 10);
  });
});
