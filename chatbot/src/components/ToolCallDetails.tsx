import { useState } from "react";
import type { ToolCallDto } from "../services/apiClient";
import type { ToolCallState } from "../store/chatStore";
import "./ToolCallDetails.css";

interface ToolCallDetailsProps {
  readonly toolCall: ToolCallDto | ToolCallState;
}

// Helper to normalize field names between DTO and State
function getField<T>(
  toolCall: ToolCallDto | ToolCallState,
  dtoField: keyof ToolCallDto,
  stateField: keyof ToolCallState
): T | null | undefined {
  if ("id" in toolCall) {
    // It's a DTO
    return toolCall[dtoField] as T;
  } else {
    // It's a State
    return toolCall[stateField] as T;
  }
}

export function ToolCallDetails({ toolCall }: ToolCallDetailsProps) {
  const [isExpanded, setIsExpanded] = useState(false);

  // Normalize field access
  const argumentsJson = getField<string>(toolCall, "argumentsJson", "arguments");
  const resultJson = getField<string>(toolCall, "resultJson", "result");
  const callId = "id" in toolCall ? toolCall.callId : toolCall.itemId;
  const createdAt = "id" in toolCall ? toolCall.createdAt : new Date(toolCall.updatedAt).toISOString();
  
  // Normalize status to lowercase for consistent comparison
  const normalizedStatus = ("status" in toolCall
    ? toolCall.status.toLowerCase()
    : "in_progress") as "in_progress" | "completed" | "failed";

  // Get status icon and color
  const statusConfig = getStatusConfig(normalizedStatus);

  const handleClick = () => setIsExpanded(!isExpanded);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      setIsExpanded(!isExpanded);
    }
  };

  // Format JSON safely
  const formatJson = (jsonString: string | null | undefined): string => {
    if (!jsonString) return "";
    try {
      const parsed = JSON.parse(jsonString);
      return JSON.stringify(parsed, null, 2);
    } catch {
      return jsonString;
    }
  };

  const formattedArguments = formatJson(argumentsJson);
  const formattedResult = formatJson(resultJson);

  return (
    <div className={`tool-call-details ${statusConfig.className}`}>
      <button
        type="button"
        className="tool-call-header"
        onClick={handleClick}
        onKeyDown={handleKeyDown}
        aria-expanded={isExpanded}
      >
        <div className="tool-call-title">
          <span className="tool-call-icon">{statusConfig.icon}</span>
          <span className="tool-call-name">
            {toolCall.name || callId || "Unknown Tool"}
          </span>
          <span className="tool-call-type">{toolCall.type}</span>
          <span className={`tool-call-status ${statusConfig.className}`}>
            {toolCall.status}
          </span>
        </div>
        <span className="expand-icon">{isExpanded ? "▼" : "▶"}</span>
      </button>

      {isExpanded && (
        <div className="tool-call-body">
          {/* Metadata */}
          <div className="tool-call-section">
            <h4>Metadata</h4>
            <div className="tool-call-metadata">
              <div className="metadata-row">
                <span className="metadata-label">Item ID:</span>
                <span className="metadata-value">{toolCall.itemId ?? "N/A"}</span>
              </div>
              <div className="metadata-row">
                <span className="metadata-label">Call ID:</span>
                <span className="metadata-value">{callId || "N/A"}</span>
              </div>
              <div className="metadata-row">
                <span className="metadata-label">Output Index:</span>
                <span className="metadata-value">{toolCall.outputIndex ?? "N/A"}</span>
              </div>
              <div className="metadata-row">
                <span className="metadata-label">Created:</span>
                <span className="metadata-value">
                  {new Date(createdAt).toLocaleString()}
                </span>
              </div>
            </div>
          </div>

          {/* Arguments */}
          {formattedArguments && (
            <div className="tool-call-section">
              <h4>Arguments</h4>
              <pre className="tool-call-json">
                {formattedArguments}
              </pre>
            </div>
          )}

          {/* Result */}
          {formattedResult && (
            <div className="tool-call-section">
              <h4>Result</h4>
              <pre className="tool-call-json">
                {formattedResult}
              </pre>
            </div>
          )}

          {/* Raw JSON (collapsed by default) */}
          <details className="tool-call-raw">
            <summary>Raw JSON</summary>
            <div className="tool-call-section">
              <h5>Arguments JSON</h5>
              <pre className="tool-call-json-raw">
                {argumentsJson || "null"}
              </pre>
            </div>
            {resultJson && (
              <div className="tool-call-section">
                <h5>Result JSON</h5>
                <pre className="tool-call-json-raw">
                  {resultJson}
                </pre>
              </div>
            )}
          </details>
        </div>
      )}
    </div>
  );
}

// Helper function
function getStatusConfig(status: string) {
  switch (status) {
    case "COMPLETED":
      return {
        icon: "✅",
        className: "status-completed",
      };
    case "IN_PROGRESS":
      return {
        icon: "⏳",
        className: "status-in-progress",
      };
    case "FAILED":
      return {
        icon: "❌",
        className: "status-failed",
      };
    default:
      return {
        icon: "❓",
        className: "status-unknown",
      };
  }
}
