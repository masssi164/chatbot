-- Initial schema for chatbot application

-- Conversations table
CREATE TABLE conversations (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Messages table
CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL,
    content TEXT,
    raw_json TEXT,
    output_index INT,
    item_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);

-- Tool calls table
CREATE TABLE tool_calls (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    call_id VARCHAR(255) NOT NULL,
    arguments_json TEXT,
    result_json TEXT,
    status VARCHAR(50) NOT NULL,
    output_index INT,
    item_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);

-- MCP servers table
CREATE TABLE mcp_servers (
    id BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_key VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'IDLE',
    transport VARCHAR(50),
    last_updated TIMESTAMP NOT NULL,
    version BIGINT DEFAULT 0,
    tools_cache TEXT,
    resources_cache TEXT,
    prompts_cache TEXT,
    last_synced_at TIMESTAMP,
    sync_status VARCHAR(50),
    client_metadata TEXT
);

-- Indexes for performance
CREATE INDEX idx_messages_conversation ON messages(conversation_id);
CREATE INDEX idx_messages_output ON messages(output_index);
CREATE INDEX idx_tool_calls_conversation ON tool_calls(conversation_id);
CREATE INDEX idx_tool_calls_output ON tool_calls(output_index);
CREATE INDEX idx_tool_calls_call_id ON tool_calls(call_id);
CREATE INDEX idx_mcp_servers_status ON mcp_servers(status);
