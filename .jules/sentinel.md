## 2024-05-24 - ServerSocket Binding to All Interfaces
**Vulnerability:** ServerSockets were binding to all interfaces (0.0.0.0) by default, exposing network services (like Swarm) to external network access, which could lead to RCE.
**Learning:** `new ServerSocket(port)` binds to all local IP addresses. It does not default to `localhost`.
**Prevention:** Always use `new ServerSocket(port, backlog, InetAddress.getLoopbackAddress())` when a service should only be accessible locally.