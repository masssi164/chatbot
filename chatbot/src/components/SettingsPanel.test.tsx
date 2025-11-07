import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import SettingsPanel from "./SettingsPanel";
import type { ChatConfig } from "../store/configStore";
import type { McpServer } from "../store/mcpServerStore";

describe("SettingsPanel", () => {
  const defaultConfig: ChatConfig = {
    model: "gpt-4o",
    titleModel: "gpt-4o",
    temperature: 0.7,
    maxTokens: 2000,
    topP: undefined,
    presencePenalty: undefined,
    frequencyPenalty: undefined,
    systemPrompt: undefined,
  };

  const defaultProps = {
    config: defaultConfig,
    availableModels: ["gpt-4o", "gpt-3.5-turbo"],
    servers: [] as McpServer[],
    activeServerId: null,
    isSyncingServers: false,
    onModelChange: vi.fn(),
    onTitleModelChange: vi.fn(),
    onRefreshModels: vi.fn(),
    onSelectServer: vi.fn(),
    onAddServer: vi.fn(),
    onRemoveServer: vi.fn(),
  };

  it("should render settings panel", () => {
    const { container } = render(<SettingsPanel {...defaultProps} />);
    expect(container).toBeInTheDocument();
  });

  it("should render with empty servers list", () => {
    const { container } = render(<SettingsPanel {...defaultProps} servers={[]} />);
    expect(container).toBeInTheDocument();
  });

  it("should render panel element", () => {
    const { container } = render(<SettingsPanel {...defaultProps} />);
    const panel = container.querySelector(".panel");
    expect(panel).toBeInTheDocument();
  });
});
