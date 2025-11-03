-- V3: Add MCP capabilities cache and optimistic locking
-- This migration adds fields for caching MCP server capabilities (tools, resources, prompts)
-- and introduces optimistic locking to prevent database lock timeouts

-- Add version column for optimistic locking (JPA @Version)
ALTER TABLE mcp_servers ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

-- Add JSON cache columns for MCP capabilities
ALTER TABLE mcp_servers ADD COLUMN tools_cache TEXT;
ALTER TABLE mcp_servers ADD COLUMN resources_cache TEXT;
ALTER TABLE mcp_servers ADD COLUMN prompts_cache TEXT;

-- Add cache metadata columns
ALTER TABLE mcp_servers ADD COLUMN last_synced_at TIMESTAMP;
ALTER TABLE mcp_servers ADD COLUMN sync_status VARCHAR(20) DEFAULT 'NEVER_SYNCED' NOT NULL;

-- Add client metadata for debugging/monitoring
ALTER TABLE mcp_servers ADD COLUMN client_metadata TEXT;

-- Create index on sync_status for efficient querying of servers that need sync
CREATE INDEX idx_mcp_servers_sync_status ON mcp_servers(sync_status);

-- Create index on status for efficient querying of connected servers
CREATE INDEX idx_mcp_servers_status ON mcp_servers(status);
