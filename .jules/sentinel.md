## 2025-02-27 - Insecure Random String Generation
**Vulnerability:** Found usage of `RandomStringUtils.insecure().nextAlphabetic(...)` for bypass text in the Spam module, generating easily predictable strings.
**Learning:** `RandomStringUtils.insecure()` relies on pseudo-random generators not suitable for security or robustness features like bypass systems.
**Prevention:** Use `RandomStringUtils.secure()` which leverages Cryptographically Secure Pseudo-Random Number Generators (CSPRNG) when randomness predictability could lead to bypassed logic or detection.
