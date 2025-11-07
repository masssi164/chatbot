import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import { McpCapabilitiesPanel } from "./McpCapabilitiesPanel";
import type { McpCapabilities } from "../types/mcp";

describe("McpCapabilitiesPanel", () => {
  const mockCapabilities: McpCapabilities = {
    tools: [],
    resources: [],
    prompts: [],
    serverInfo: {
      name: "Test Server",
      version: "1.0.0",
    },
  };

  it("should render without errors", () => {
    const { container } = render(
      <McpCapabilitiesPanel
        capabilities={mockCapabilities}
        isLoading={false}
        serverName="Test Server"
        serverId="test-server"
      />
    );
    expect(container).toBeInTheDocument();
  });

  it("should render when server has no tools", () => {
    const { container } = render(
      <McpCapabilitiesPanel
        capabilities={mockCapabilities}
        isLoading={false}
        serverName="Test Server"
        serverId="test-server"
      />
    );
    expect(container).toBeInTheDocument();
  });

  it("should handle null capabilities gracefully", () => {
    const { container } = render(
      <McpCapabilitiesPanel
        capabilities={null}
        isLoading={false}
        serverName="Test Server"
        serverId="test-server"
      />
    );
    expect(container).toBeInTheDocument();
  });

  it("should render panel element", () => {
    const { container } = render(
      <McpCapabilitiesPanel
        capabilities={mockCapabilities}
        isLoading={false}
        serverName="Test Server"
        serverId="test-server"
      />
    );
    const panel = container.querySelector('.mcp-capabilities-panel');
    expect(panel).toBeInTheDocument();
  });
});
