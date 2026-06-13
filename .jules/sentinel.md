## 2024-05-18 - Prevent Unauthenticated RCE in Local Services
**Vulnerability:** ServerSocket bound to all interfaces (`0.0.0.0`) by default in `SwarmHost.java`, which allows unauthenticated network connections.
**Learning:** By default, `ServerSocket(port)` binds to all network interfaces. If the service is intended for local or internal use (or relies on client authentication), exposing it to the network can lead to Remote Code Execution (RCE) or unauthorized access.
**Prevention:** Always use `InetAddress.getLoopbackAddress()` to bind the `ServerSocket` explicitly to `localhost` unless external network access is explicitly intended and properly secured with authentication.
## 2026-06-04 - Fix OAuth CSRF Vulnerability in Microsoft Login
**Vulnerability:** The local `HttpServer` listening for the Microsoft OAuth callback on `127.0.0.1:9675` accepted any incoming `code` parameter without verifying its origin via the OAuth `state` parameter.
**Learning:** This lack of verification meant a malicious website could perform a Cross-Site Request Forgery (CSRF) attack to force a user to log into an attacker-controlled Microsoft account (since it listens locally and accepts forged `code` redirects).
**Prevention:** Always implement and strictly validate the OAuth `state` parameter (e.g., using a random UUID) to ensure the callback request corresponds to a flow initiated by the local application instance.
## 2025-02-27 - Insecure Random String Generation
**Vulnerability:** Found usage of `RandomStringUtils.insecure().nextAlphabetic(...)` for bypass text in the Spam module, generating easily predictable strings.
**Learning:** `RandomStringUtils.insecure()` relies on pseudo-random generators not suitable for security or robustness features like bypass systems.
**Prevention:** Use `RandomStringUtils.secure()` which leverages Cryptographically Secure Pseudo-Random Number Generators (CSPRNG) when randomness predictability could lead to bypassed logic or detection.
## 2024-05-30 - Insecure API Call
**Vulnerability:** Found `http` being used in TheAlteningAccount.java for sensitive endpoints (`http://sessionserver.thealtening.com` and `http://authserver.thealtening.com`).
**Learning:** Hardcoding insecure protocol endpoints, especially related to authentication and session management, leaves the application vulnerable to Man-in-the-Middle (MitM) attacks.
**Prevention:** Ensure that all endpoints use secure protocols (`https`) instead of `http` to protect sensitive data transmission.
