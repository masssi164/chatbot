import { useEffect, useState } from "react";
import { apiClient, type ToolApprovalPolicyDto } from "../services/apiClient";
import type { McpCapabilities } from "../types/mcp";
import "./McpCapabilitiesPanel.css";

interface McpCapabilitiesPanelProps {
  capabilities: McpCapabilities | null;
  isLoading: boolean;
  serverName: string;
  serverId: string;
}

export function McpCapabilitiesPanel({
  capabilities,
  isLoading,
  serverName,
  serverId,
}: McpCapabilitiesPanelProps) {
  const [expandedSection, setExpandedSection] = useState<
    "tools" | "resources" | "prompts" | null
  >(null);
  const [approvalPolicies, setApprovalPolicies] = useState<Map<string, "always" | "never">>(new Map());
  const [loadingPolicies, setLoadingPolicies] = useState(false);

  // Load approval policies for tools
  useEffect(() => {
    if (!capabilities || capabilities.tools.length === 0) {
      return;
    }

    setLoadingPolicies(true);
    apiClient
      .getToolApprovalPolicies(serverId)
      .then((policies) => {
        const policyMap = new Map<string, "always" | "never">();
        policies.forEach((p: ToolApprovalPolicyDto) => {
          policyMap.set(p.toolName, p.policy);
        });
        setApprovalPolicies(policyMap);
      })
      .catch((err) => {
        console.error("Failed to load approval policies:", err);
      })
      .finally(() => {
        setLoadingPolicies(false);
      });
  }, [serverId, capabilities]);

  const handleApprovalToggle = async (toolName: string, requiresApproval: boolean) => {
    const policy = requiresApproval ? "always" : "never";
    
    try {
      await apiClient.setToolApprovalPolicy(serverId, toolName, policy);
      setApprovalPolicies((prev) => {
        const updated = new Map(prev);
        updated.set(toolName, policy);
        return updated;
      });
    } catch (err) {
      console.error(`Failed to update approval policy for ${toolName}:`, err);
    }
  };

  if (isLoading) {
    return (
      <div className="mcp-capabilities-panel">
        <div className="mcp-capabilities-loading">Loading capabilities...</div>
      </div>
    );
  }

  if (!capabilities) {
    return (
      <div className="mcp-capabilities-panel">
        <div className="mcp-capabilities-empty">
          No capabilities available. Connect to view features.
        </div>
      </div>
    );
  }

  const toggleSection = (section: "tools" | "resources" | "prompts") => {
    setExpandedSection(expandedSection === section ? null : section);
  };

  const hasTools = capabilities.tools.length > 0;
  const hasResources = capabilities.resources.length > 0;
  const hasPrompts = capabilities.prompts.length > 0;

  return (
    <div className="mcp-capabilities-panel">
      <div className="mcp-capabilities-header">
        <h3>{serverName} Capabilities</h3>
        <span className="mcp-capabilities-version">
          v{capabilities.serverInfo.version}
        </span>
      </div>

      <div className="mcp-capabilities-sections">
        {/* Tools Section */}
        <div className="mcp-capabilities-section">
          <button
            type="button"
            className={`mcp-section-toggle ${hasTools ? "" : "empty"}`}
            onClick={() => hasTools && toggleSection("tools")}
            disabled={!hasTools}
          >
            <span className="mcp-section-icon">🔧</span>
            <span className="mcp-section-title">
              Tools ({capabilities.tools.length})
            </span>
            {hasTools && (
              <span className="mcp-section-arrow">
                {expandedSection === "tools" ? "▼" : "▶"}
              </span>
            )}
          </button>

          {expandedSection === "tools" && hasTools && (
            <div className="mcp-section-content">
              {capabilities.tools.map((tool) => {
                const requiresApproval = approvalPolicies.get(tool.name) === "always";
                
                return (
                  <div key={tool.name} className="mcp-item">
                    <div className="mcp-item-header">
                      <div className="mcp-item-name">{tool.name}</div>
                      <label className="mcp-approval-checkbox">
                        <input
                          type="checkbox"
                          checked={requiresApproval}
                          onChange={(e) => handleApprovalToggle(tool.name, e.target.checked)}
                          disabled={loadingPolicies}
                        />
                        <span>Erfordert Bestätigung</span>
                      </label>
                    </div>
                    {tool.description && (
                      <div className="mcp-item-description">
                        {tool.description}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Resources Section */}
        <div className="mcp-capabilities-section">
          <button
            type="button"
            className={`mcp-section-toggle ${hasResources ? "" : "empty"}`}
            onClick={() => hasResources && toggleSection("resources")}
            disabled={!hasResources}
          >
            <span className="mcp-section-icon">📦</span>
            <span className="mcp-section-title">
              Resources ({capabilities.resources.length})
            </span>
            {hasResources && (
              <span className="mcp-section-arrow">
                {expandedSection === "resources" ? "▼" : "▶"}
              </span>
            )}
          </button>

          {expandedSection === "resources" && hasResources && (
            <div className="mcp-section-content">
              {capabilities.resources.map((resource) => (
                <div key={resource.uri} className="mcp-item">
                  <div className="mcp-item-name">{resource.name || resource.uri}</div>
                  {resource.description && (
                    <div className="mcp-item-description">
                      {resource.description}
                    </div>
                  )}
                  {resource.mimeType && (
                    <div className="mcp-item-meta">Type: {resource.mimeType}</div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Prompts Section */}
        <div className="mcp-capabilities-section">
          <button
            type="button"
            className={`mcp-section-toggle ${hasPrompts ? "" : "empty"}`}
            onClick={() => hasPrompts && toggleSection("prompts")}
            disabled={!hasPrompts}
          >
            <span className="mcp-section-icon">💬</span>
            <span className="mcp-section-title">
              Prompts ({capabilities.prompts.length})
            </span>
            {hasPrompts && (
              <span className="mcp-section-arrow">
                {expandedSection === "prompts" ? "▼" : "▶"}
              </span>
            )}
          </button>

          {expandedSection === "prompts" && hasPrompts && (
            <div className="mcp-section-content">
              {capabilities.prompts.map((prompt) => (
                <div key={prompt.name} className="mcp-item">
                  <div className="mcp-item-name">{prompt.name}</div>
                  {prompt.description && (
                    <div className="mcp-item-description">
                      {prompt.description}
                    </div>
                  )}
                  {prompt.arguments.length > 0 && (
                    <div className="mcp-item-meta">
                      Args: {prompt.arguments.map((arg) => arg.name).join(", ")}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
