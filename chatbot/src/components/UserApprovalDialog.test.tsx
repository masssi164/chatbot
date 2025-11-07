import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { UserApprovalDialog, type ApprovalRequestData } from "./UserApprovalDialog";

describe("UserApprovalDialog", () => {
  const mockRequest: ApprovalRequestData = {
    approvalRequestId: "approval-123",
    serverLabel: "test-server",
    toolName: "test_tool",
    arguments: '{"param": "value", "count": 42}',
  };

  it("should render approval dialog", () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();

    render(
      <UserApprovalDialog
        request={mockRequest}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    expect(screen.getByText(/Bestätigung erforderlich/)).toBeInTheDocument();
    expect(screen.getByText("test_tool")).toBeInTheDocument();
    expect(screen.getByText("test-server")).toBeInTheDocument();
  });

  it("should display parsed arguments", () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();

    render(
      <UserApprovalDialog
        request={mockRequest}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    expect(screen.getByText(/Argumente:/)).toBeInTheDocument();
    expect(screen.getByText(/"param": "value"/)).toBeInTheDocument();
    expect(screen.getByText(/"count": 42/)).toBeInTheDocument();
  });

  it("should handle missing arguments", () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    const requestWithoutArgs: ApprovalRequestData = {
      ...mockRequest,
      arguments: undefined,
    };

    render(
      <UserApprovalDialog
        request={requestWithoutArgs}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    expect(screen.queryByText(/Argumente:/)).not.toBeInTheDocument();
  });

  it("should handle invalid JSON arguments", () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    const requestWithInvalidJson: ApprovalRequestData = {
      ...mockRequest,
      arguments: "invalid json {",
    };

    render(
      <UserApprovalDialog
        request={requestWithInvalidJson}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    // Should not display arguments section if JSON is invalid
    expect(screen.queryByText(/Argumente:/)).not.toBeInTheDocument();
  });

  it("should call onApprove without remember", async () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    const user = userEvent.setup();

    render(
      <UserApprovalDialog
        request={mockRequest}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    const approveButton = screen.getByRole("button", { name: /Genehmigen/ });
    await user.click(approveButton);

    expect(onApprove).toHaveBeenCalledWith(false);
    expect(onDeny).not.toHaveBeenCalled();
  });

  it("should call onDeny without remember", async () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    const user = userEvent.setup();

    render(
      <UserApprovalDialog
        request={mockRequest}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    const denyButton = screen.getByRole("button", { name: /Ablehnen/ });
    await user.click(denyButton);

    expect(onDeny).toHaveBeenCalledWith(false);
    expect(onApprove).not.toHaveBeenCalled();
  });

  it("should call onApprove with remember when checkbox is checked", async () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    const user = userEvent.setup();

    render(
      <UserApprovalDialog
        request={mockRequest}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    const checkbox = screen.getByRole("checkbox");
    await user.click(checkbox);

    const approveButton = screen.getByRole("button", { name: /Genehmigen/ });
    await user.click(approveButton);

    expect(onApprove).toHaveBeenCalledWith(true);
  });

  it("should call onDeny with remember when checkbox is checked", async () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    const user = userEvent.setup();

    render(
      <UserApprovalDialog
        request={mockRequest}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    const checkbox = screen.getByRole("checkbox");
    await user.click(checkbox);

    const denyButton = screen.getByRole("button", { name: /Ablehnen/ });
    await user.click(denyButton);

    expect(onDeny).toHaveBeenCalledWith(true);
  });

  it("should toggle remember checkbox", async () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    const user = userEvent.setup();

    render(
      <UserApprovalDialog
        request={mockRequest}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    const checkbox = screen.getByRole("checkbox") as HTMLInputElement;
    expect(checkbox.checked).toBe(false);

    await user.click(checkbox);
    expect(checkbox.checked).toBe(true);

    await user.click(checkbox);
    expect(checkbox.checked).toBe(false);
  });

  it("should display approval icon", () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();

    render(
      <UserApprovalDialog
        request={mockRequest}
        onApprove={onApprove}
        onDeny={onDeny}
      />
    );

    expect(screen.getByText("🔔")).toBeInTheDocument();
  });
});
