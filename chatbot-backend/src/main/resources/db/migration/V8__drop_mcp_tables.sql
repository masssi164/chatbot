-- Remove legacy MCP tables after delegating orchestration to LiteLLM
DROP TABLE IF EXISTS tool_approval_policies;
DROP TABLE IF EXISTS mcp_servers;
