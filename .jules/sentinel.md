## 2024-06-08 - ServerSocket Unauthenticated RCE Risk
**Vulnerability:** ServerSocket in Swarm module was bound to all interfaces (0.0.0.0) by default, exposing the service to potential unauthenticated Remote Code Execution (RCE) from external networks.
**Learning:** In the Meteor codebase (e.g., the Swarm module), network services must bind to localhost to prevent exposure unless explicit external access is intended.
**Prevention:** Always use `InetAddress.getLoopbackAddress()` when initializing `ServerSocket` unless external access with strong authentication is explicitly required.
