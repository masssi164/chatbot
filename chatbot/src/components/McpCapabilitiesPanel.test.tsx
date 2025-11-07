import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import { McpCapabilitiesPanel } from "./McpCapabilitiesPanel";
import type { McpServer } from "../store/mcpServerStore";

describe("McpCapabilitiesPanel", () => {
  const mockServer: McpServer = {
    id: "server-1",
    serverId: "test-server",
    name: "Test Server",
    baseUrl: "http://localhost:3000",
    transport: "SSE",
    status: "connected",
    toolsCache: [],
    resourcesCache: [],
    promptsCache: [],
    lastSynced: Date.now(),
    syncStatus: "SYNCED",
    lastUpdated: Date.now(),
    version: 1,
  };

  it("should render without errors", () => {
    const { container } = render(<McpCapabilitiesPanel server={mockServer} />);
    expect(container).toBeInTheDocument();
  });

  it("should render when server has no tools", () => {
    const serverWithNoTools = {
      ...mockServer,
      toolsCache: [],
    };

    const { container } = render(<McpCapabilitiesPanel server={serverWithNoTools} />);
    expect(container).toBeInTheDocument();
  });

  it("should handle undefined server gracefully", () => {
    const { container } = render(<McpCapabilitiesPanel server={undefined as any} />);
    expect(container).toBeInTheDocument();
  });

  it("should render panel element", () => {
    const { container } = render(<McpCapabilitiesPanel server={mockServer} />);
    const panel = container.querySelector('.mcp-capabilities-panel');
    expect(panel).toBeInTheDocument();
  });
});
