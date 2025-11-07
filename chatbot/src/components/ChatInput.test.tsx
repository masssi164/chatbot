import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ChatInput from "./ChatInput";

describe("ChatInput", () => {
  const defaultProps = {
    prompt: "",
    onPromptChange: vi.fn(),
    onSend: vi.fn(),
    isLoading: false,
    currentModel: "gpt-4o",
    availableModels: ["gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo"],
    onModelChange: vi.fn(),
    parameters: {
      temperature: 0.7,
      maxTokens: 2000,
      topP: undefined,
      presencePenalty: undefined,
      frequencyPenalty: undefined,
    },
    onParametersChange: {
      setTemperature: vi.fn(),
      setMaxTokens: vi.fn(),
      setTopP: vi.fn(),
      setPresencePenalty: vi.fn(),
      setFrequencyPenalty: vi.fn(),
    },
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should render textarea", () => {
    render(<ChatInput {...defaultProps} />);
    
    const textarea = screen.getByPlaceholderText(/Ask the assistant/);
    expect(textarea).toBeInTheDocument();
  });

  it("should display prompt value", () => {
    render(<ChatInput {...defaultProps} prompt="Hello world" />);
    
    const textarea = screen.getByDisplayValue("Hello world");
    expect(textarea).toBeInTheDocument();
  });

  it("should call onPromptChange when text is entered", async () => {
    const user = userEvent.setup();
    render(<ChatInput {...defaultProps} />);
    
    const textarea = screen.getByPlaceholderText(/Ask the assistant/);
    await user.type(textarea, "Hello");
    
    expect(defaultProps.onPromptChange).toHaveBeenCalled();
  });

  it("should render model selector", () => {
    render(<ChatInput {...defaultProps} />);
    
    const select = screen.getByRole("combobox");
    expect(select).toBeInTheDocument();
    expect(select).toHaveValue("gpt-4o");
  });

  it("should call onModelChange when model is changed", async () => {
    const user = userEvent.setup();
    render(<ChatInput {...defaultProps} />);
    
    const select = screen.getByRole("combobox");
    await user.selectOptions(select, "gpt-4-turbo");
    
    expect(defaultProps.onModelChange).toHaveBeenCalledWith("gpt-4-turbo");
  });

  it("should disable model selector when loading", () => {
    render(<ChatInput {...defaultProps} isLoading={true} />);
    
    const select = screen.getByRole("combobox");
    expect(select).toBeDisabled();
  });

  it("should disable textarea when loading", () => {
    render(<ChatInput {...defaultProps} isLoading={true} />);
    
    const textarea = screen.getByPlaceholderText(/Ask the assistant/);
    expect(textarea).toBeDisabled();
  });

  it("should call onSend when form is submitted", async () => {
    const user = userEvent.setup();
    const onSend = vi.fn().mockResolvedValue(undefined);
    
    render(<ChatInput {...defaultProps} prompt="Test message" onSend={onSend} />);
    
    await user.click(screen.getByRole("button", { name: /Send/ }));
    
    expect(onSend).toHaveBeenCalledWith("Test message");
  });

  it("should not call onSend when prompt is empty", async () => {
    const user = userEvent.setup();
    const onSend = vi.fn();
    
    render(<ChatInput {...defaultProps} prompt="" onSend={onSend} />);
    
    await user.click(screen.getByRole("button", { name: /Send/ }));
    
    expect(onSend).not.toHaveBeenCalled();
  });

  it("should clear prompt after sending", async () => {
    const user = userEvent.setup();
    const onPromptChange = vi.fn();
    const onSend = vi.fn().mockResolvedValue(undefined);
    
    render(
      <ChatInput 
        {...defaultProps} 
        prompt="Test message" 
        onPromptChange={onPromptChange}
        onSend={onSend}
      />
    );
    
    await user.click(screen.getByRole("button", { name: /Send/ }));
    
    expect(onPromptChange).toHaveBeenCalledWith("");
  });

  it("should disable send button when prompt is empty", () => {
    render(<ChatInput {...defaultProps} prompt="" />);
    
    const sendButton = screen.getByRole("button", { name: /Send/ });
    expect(sendButton).toBeDisabled();
  });

  it("should disable send button when loading", () => {
    render(<ChatInput {...defaultProps} prompt="Test" isLoading={true} />);
    
    const sendButton = screen.getByRole("button", { name: /Sending/ });
    expect(sendButton).toBeDisabled();
  });

  it("should show 'Sending...' text when loading", () => {
    render(<ChatInput {...defaultProps} isLoading={true} />);
    
    expect(screen.getByText("Sending…")).toBeInTheDocument();
  });

  it("should toggle parameters panel", async () => {
    const user = userEvent.setup();
    render(<ChatInput {...defaultProps} />);
    
    const toggleButton = screen.getByRole("button", { name: /Payload options/ });
    await user.click(toggleButton);
    
    expect(screen.getByText(/Temperature/)).toBeInTheDocument();
    
    const hideButton = screen.getByRole("button", { name: /Hide payload/ });
    await user.click(hideButton);
    
    expect(screen.queryByText(/Temperature/)).not.toBeInTheDocument();
  });

  it("should render parameter controls when panel is open", async () => {
    const user = userEvent.setup();
    render(<ChatInput {...defaultProps} />);
    
    const toggleButton = screen.getByRole("button", { name: /Payload options/ });
    await user.click(toggleButton);
    
    expect(screen.getByLabelText("Temperature")).toBeInTheDocument();
    expect(screen.getByLabelText("Max tokens")).toBeInTheDocument();
    expect(screen.getByLabelText("Top-p")).toBeInTheDocument();
    expect(screen.getByLabelText("Presence penalty")).toBeInTheDocument();
    expect(screen.getByLabelText("Frequency penalty")).toBeInTheDocument();
  });

  it("should call setTemperature when temperature is changed", async () => {
    const user = userEvent.setup();
    const setTemperature = vi.fn();
    const props = {
      ...defaultProps,
      onParametersChange: {
        ...defaultProps.onParametersChange,
        setTemperature,
      },
    };
    
    render(<ChatInput {...props} />);
    
    const toggleButton = screen.getByRole("button", { name: /Payload options/ });
    await user.click(toggleButton);
    
    const temperatureInput = screen.getByLabelText("Temperature");
    await user.clear(temperatureInput);
    await user.type(temperatureInput, "1.5");
    
    // Verify function was called
    expect(setTemperature).toHaveBeenCalled();
  });

  it("should call setMaxTokens when max tokens is changed", async () => {
    const user = userEvent.setup();
    const setMaxTokens = vi.fn();
    const props = {
      ...defaultProps,
      onParametersChange: {
        ...defaultProps.onParametersChange,
        setMaxTokens,
      },
    };
    
    render(<ChatInput {...props} />);
    
    const toggleButton = screen.getByRole("button", { name: /Payload options/ });
    await user.click(toggleButton);
    
    const maxTokensInput = screen.getByLabelText("Max tokens");
    await user.clear(maxTokensInput);
    await user.type(maxTokensInput, "1000");
    
    // Verify function was called
    expect(setMaxTokens).toHaveBeenCalled();
  });

  it("should pass undefined when parameter input is empty", async () => {
    const user = userEvent.setup();
    render(<ChatInput {...defaultProps} />);
    
    const toggleButton = screen.getByRole("button", { name: /Payload options/ });
    await user.click(toggleButton);
    
    const temperatureInput = screen.getByLabelText("Temperature");
    await user.clear(temperatureInput);
    
    expect(defaultProps.onParametersChange.setTemperature).toHaveBeenCalledWith(undefined);
  });

  it("should render stop button when onAbort is provided", () => {
    render(<ChatInput {...defaultProps} onAbort={vi.fn()} />);
    
    expect(screen.getByRole("button", { name: /Stop/ })).toBeInTheDocument();
  });

  it("should not render stop button when onAbort is not provided", () => {
    render(<ChatInput {...defaultProps} />);
    
    expect(screen.queryByRole("button", { name: /Stop/ })).not.toBeInTheDocument();
  });

  it("should call onAbort when stop button is clicked", async () => {
    const user = userEvent.setup();
    const onAbort = vi.fn();
    
    render(<ChatInput {...defaultProps} onAbort={onAbort} isLoading={true} />);
    
    const stopButton = screen.getByRole("button", { name: /Stop/ });
    await user.click(stopButton);
    
    expect(onAbort).toHaveBeenCalled();
  });

  it("should enable stop button only when loading", () => {
    const onAbort = vi.fn();
    
    const { rerender } = render(
      <ChatInput {...defaultProps} onAbort={onAbort} isLoading={false} />
    );
    
    let stopButton = screen.getByRole("button", { name: /Stop/ });
    expect(stopButton).toBeDisabled();
    
    rerender(<ChatInput {...defaultProps} onAbort={onAbort} isLoading={true} />);
    
    stopButton = screen.getByRole("button", { name: /Stop/ });
    expect(stopButton).not.toBeDisabled();
  });

  it("should display all available models", () => {
    render(<ChatInput {...defaultProps} />);
    
    const select = screen.getByRole("combobox");
    const options = Array.from(select.querySelectorAll("option"));
    
    expect(options).toHaveLength(4); // 3 models + 1 placeholder
    expect(options[1]).toHaveTextContent("gpt-4o");
    expect(options[2]).toHaveTextContent("gpt-4-turbo");
    expect(options[3]).toHaveTextContent("gpt-3.5-turbo");
  });

  it("should disable controls when disabled prop is true", () => {
    render(<ChatInput {...defaultProps} disabled={true} prompt="Test" />);
    
    const sendButton = screen.getByRole("button", { name: /Send/ });
    expect(sendButton).toBeDisabled();
  });

  it("should show no models message when availableModels is empty", () => {
    render(<ChatInput {...defaultProps} availableModels={[]} currentModel="" />);
    
    expect(screen.getByText("No models available")).toBeInTheDocument();
  });

  it("should expose focus method via ref", () => {
    const ref = { current: null } as any;
    render(<ChatInput {...defaultProps} ref={ref} />);
    
    expect(ref.current).toBeDefined();
    expect(typeof ref.current?.focusPrompt).toBe("function");
  });
});
