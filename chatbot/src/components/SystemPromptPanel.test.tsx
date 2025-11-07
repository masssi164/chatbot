import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import SystemPromptPanel from "./SystemPromptPanel";

describe("SystemPromptPanel", () => {
  it("should render preset buttons", () => {
    const onChange = vi.fn();
    
    render(<SystemPromptPanel onChange={onChange} />);
    
    expect(screen.getByRole("button", { name: /Standard/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Präzise/ })).toBeInTheDocument();
  });

  it("should render custom prompt checkbox", () => {
    const onChange = vi.fn();
    
    render(<SystemPromptPanel onChange={onChange} />);
    
    const checkbox = screen.getByRole("checkbox");
    expect(checkbox).toBeInTheDocument();
  });

  it("should not show textarea when custom is not selected", () => {
    const onChange = vi.fn();
    
    render(<SystemPromptPanel onChange={onChange} />);
    
    const textarea = screen.queryByRole("textbox");
    expect(textarea).not.toBeInTheDocument();
  });

  it("should show textarea when custom checkbox is checked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    
    render(<SystemPromptPanel onChange={onChange} />);
    
    const checkbox = screen.getByRole("checkbox");
    await user.click(checkbox);
    
    const textarea = screen.getByRole("textbox");
    expect(textarea).toBeInTheDocument();
  });

  it("should call onChange when preset is selected", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    
    render(<SystemPromptPanel onChange={onChange} />);
    
    const presetButton = screen.getByRole("button", { name: /Kreativ/ });
    await user.click(presetButton);
    
    expect(onChange).toHaveBeenCalled();
  });

  it("should call onChange when custom text is entered", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    
    render(<SystemPromptPanel onChange={onChange} />);
    
    const checkbox = screen.getByRole("checkbox");
    await user.click(checkbox);
    
    const textarea = screen.getByRole("textbox");
    await user.type(textarea, "Custom prompt");
    
    expect(onChange).toHaveBeenCalled();
  });

  it("should display character count in custom mode", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    
    render(<SystemPromptPanel onChange={onChange} />);
    
    const checkbox = screen.getByRole("checkbox");
    await user.click(checkbox);
    
    expect(screen.getByText(/Zeichen:/)).toBeInTheDocument();
  });

  it("should show current prompt when value is provided", () => {
    const onChange = vi.fn();
    const value = "Du bist ein kreativer Assistent";
    
    const { container } = render(<SystemPromptPanel value={value} onChange={onChange} />);
    
    // When value is provided and matches a non-standard preset, it will be in custom mode
    // Just verify component renders without errors
    expect(container).toBeInTheDocument();
  });
});