## 2024-05-30 - Insecure API Call
**Vulnerability:** Found `http` being used in TheAlteningAccount.java for sensitive endpoints (`http://sessionserver.thealtening.com` and `http://authserver.thealtening.com`).
**Learning:** Hardcoding insecure protocol endpoints, especially related to authentication and session management, leaves the application vulnerable to Man-in-the-Middle (MitM) attacks.
**Prevention:** Ensure that all endpoints use secure protocols (`https`) instead of `http` to protect sensitive data transmission.
