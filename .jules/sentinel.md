## 2024-05-24 - [Insecure Authentication Endpoints]
**Vulnerability:** Found `http://` used instead of `https://` for `sessionserver` and `authserver` endpoints in `TheAlteningAccount.java`.
**Learning:** Hardcoding unencrypted HTTP URLs for authentication workflows in third-party account implementations allows attackers to intercept sensitive tokens via Man-in-the-Middle (MitM) attacks.
**Prevention:** Always verify and enforce the use of `https://` for external service URLs, especially when creating custom authentication providers like `TheAlteningAccount`. Consider adding a pre-commit hook or linter rule to check for hardcoded `http://` URLs.
