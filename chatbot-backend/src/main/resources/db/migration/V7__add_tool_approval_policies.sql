-- Add tool approval policies table
CREATE TABLE tool_approval_policies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    server_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    policy VARCHAR(20) NOT NULL DEFAULT 'always',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Unique constraint: one policy per server+tool combination
    CONSTRAINT uk_server_tool UNIQUE (server_id, tool_name),
    
    -- Foreign key to mcp_servers with CASCADE delete
    CONSTRAINT fk_tool_policy_server FOREIGN KEY (server_id) 
        REFERENCES mcp_servers(server_id) ON DELETE CASCADE
);

-- Index for fast lookups by server
CREATE INDEX idx_tool_policies_server ON tool_approval_policies(server_id);
