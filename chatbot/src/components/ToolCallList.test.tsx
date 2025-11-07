import { describe, it, expect, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { ToolCallList } from "./ToolCallList";
import { useToolCallStore } from "../store/toolCallStore";
import type { ToolCallState } from "../store/toolCallStore";

describe("ToolCallList", () => {
  beforeEach(() => {
    // Reset store
    useToolCallStore.setState({
      toolCalls: [],
      pendingApprovalRequest: null,
    });
  });

  it("should return null when conversationId is null", () => {
    const { container } = render(<ToolCallList conversationId={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("should return null when toolCalls is empty", () => {
    const { container } = render(<ToolCallList conversationId={123} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("should render tool calls list", () => {
    const toolCalls: ToolCallState[] = [
      {
        itemId: "tool-1",
        type: "function",
        status: "completed",
        name: "test_function",
        arguments: '{"param": "value"}',
        result: '{"success": true}',
        error: null,
        outputIndex: 0,
        updatedAt: Date.now(),
      },
      {
        itemId: "tool-2",
        type: "mcp",
        status: "in_progress",
        name: "mcp_tool",
        arguments: '{"key": "value"}',
        result: null,
        error: null,
        outputIndex: 1,
        updatedAt: Date.now(),
      },
    ];

    useToolCallStore.setState({ toolCalls });

    render(<ToolCallList conversationId={123} />);

    expect(screen.getByText("Tool Executions (2)")).toBeInTheDocument();
    expect(screen.getByText(/test_function/)).toBeInTheDocument();
    expect(screen.getByText(/mcp_tool/)).toBeInTheDocument();
  });

  it("should display correct count", () => {
    const toolCalls: ToolCallState[] = [
      {
        itemId: "tool-1",
        type: "function",
        status: "completed",
        name: "test_function",
        arguments: null,
        result: null,
        error: null,
        updatedAt: Date.now(),
      },
    ];

    useToolCallStore.setState({ toolCalls });

    render(<ToolCallList conversationId={123} />);

    expect(screen.getByText("Tool Executions (1)")).toBeInTheDocument();
  });
});
