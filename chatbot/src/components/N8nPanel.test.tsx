import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import N8nPanel from "./N8nPanel";

describe("N8nPanel", () => {
  it("should render without errors", () => {
    const { container } = render(<N8nPanel />);
    expect(container).toBeInTheDocument();
  });

  it("should render panel heading", () => {
    const { container } = render(<N8nPanel />);
    const heading = container.querySelector('h3');
    expect(heading).toBeInTheDocument();
  });
});
