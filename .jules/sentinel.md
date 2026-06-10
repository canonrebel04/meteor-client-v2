## 2025-02-14 - Unauthenticated ServerSocket Binding
**Vulnerability:** The `SwarmHost` module was creating a `ServerSocket` bound to `0.0.0.0` (all network interfaces) by default, exposing the client to unauthenticated remote connections.
**Learning:** In Java, `new ServerSocket(port)` binds to all local IP addresses. If IPC or local communication is the goal, this unintentionally exposes the port to the broader network.
**Prevention:** Always use `new ServerSocket(port, backlog, InetAddress.getLoopbackAddress())` when the service should only be accessed locally.
