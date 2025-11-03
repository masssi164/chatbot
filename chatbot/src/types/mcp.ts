export interface McpCapabilities {
  tools: ToolInfo[];
  resources: ResourceInfo[];
  prompts: PromptInfo[];
  serverInfo: ServerInfo;
}

export interface ToolInfo {
  name: string;
  description?: string;
  inputSchema?: unknown;
}

export interface ResourceInfo {
  uri: string;
  name?: string;
  description?: string;
  mimeType?: string;
}

export interface PromptInfo {
  name: string;
  description?: string;
  arguments: PromptArgument[];
}

export interface PromptArgument {
  name: string;
  description?: string;
  required: boolean;
}

export interface ServerInfo {
  name: string;
  version: string;
  supportsTools?: boolean;
  supportsResources?: boolean;
  supportsPrompts?: boolean;
}
