## 2026-06-04 - Fix OAuth CSRF Vulnerability in Microsoft Login
**Vulnerability:** The local `HttpServer` listening for the Microsoft OAuth callback on `127.0.0.1:9675` accepted any incoming `code` parameter without verifying its origin via the OAuth `state` parameter.
**Learning:** This lack of verification meant a malicious website could perform a Cross-Site Request Forgery (CSRF) attack to force a user to log into an attacker-controlled Microsoft account (since it listens locally and accepts forged `code` redirects).
**Prevention:** Always implement and strictly validate the OAuth `state` parameter (e.g., using a random UUID) to ensure the callback request corresponds to a flow initiated by the local application instance.
