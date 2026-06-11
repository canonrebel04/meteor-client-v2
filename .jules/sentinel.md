## 2024-10-25 - Unauthenticated RCE via ServerSocket Binding
**Vulnerability:** ServerSocket instantiated with only port bound to all interfaces (0.0.0.0), exposing internal client service to network.
**Learning:** Meteor Client's Swarm module used ServerSocket for IPC/local workers but implicitly allowed external network connections, risking RCE if packets are sent.
**Prevention:** Always bind internal/IPC network services explicitly to `localhost` (loopback interface) using `InetAddress.getLoopbackAddress()`.
