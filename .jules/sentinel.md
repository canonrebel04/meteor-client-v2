## 2024-05-18 - Prevent Unauthenticated RCE in SwarmHost
**Vulnerability:** `ServerSocket` in `SwarmHost.java` was binding to all interfaces (`0.0.0.0`) by default, exposing the custom port to external network connections and potentially allowing unauthenticated Remote Code Execution.
**Learning:** Default constructors for `ServerSocket` (e.g., `new ServerSocket(port)`) bind to all available IP addresses on the machine, which is often not intended and insecure for local-only IPC/communication.
**Prevention:** Always specify the bind address explicitly using `InetAddress.getLoopbackAddress()` (e.g., `new ServerSocket(port, backlog, InetAddress.getLoopbackAddress())`) for local services to prevent external access.
