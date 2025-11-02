-- Migration für MCP Server Transport-Typ
-- Fügt transport Spalte hinzu für SSE oder STREAMABLE_HTTP Support

ALTER TABLE mcp_servers 
ADD COLUMN transport VARCHAR(20) NOT NULL DEFAULT 'STREAMABLE_HTTP';

-- Alle bestehenden Einträge bekommen STREAMABLE_HTTP als Default
UPDATE mcp_servers 
SET transport = 'STREAMABLE_HTTP' 
WHERE transport IS NULL;
