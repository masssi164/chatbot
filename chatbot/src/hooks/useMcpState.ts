import { useMcpServerStore } from "../store/mcpServerStore";

/**
 * Custom hook to group MCP server state selectors
 */
export function useMcpState() {
  return {
    servers: useMcpServerStore((state) => state.servers),
    activeServerId: useMcpServerStore((state) => state.activeServerId),
    isSyncing: useMcpServerStore((state) => state.isSyncing),
  };
}

/**
 * Custom hook to group MCP server actions
 */
export function useMcpActions() {
  return {
    loadServers: useMcpServerStore((state) => state.loadServers),
    loadCapabilities: useMcpServerStore((state) => state.loadCapabilities),
    registerServer: useMcpServerStore((state) => state.registerServer),
    setActiveServer: useMcpServerStore((state) => state.setActiveServer),
    updateServer: useMcpServerStore((state) => state.updateServer),
    setServerStatus: useMcpServerStore((state) => state.setServerStatus),
    removeServer: useMcpServerStore((state) => state.removeServer),
    connectToStatusStream: useMcpServerStore((state) => state.connectToStatusStream),
    disconnectFromStatusStream: useMcpServerStore((state) => state.disconnectFromStatusStream),
  };
}
