## 2024-05-31 - Command Injection Risk in URL opening
**Vulnerability:** Use of `Runtime.getRuntime().exec` with user-provided strings constructed into OS commands to open URLs and files.
**Learning:** Hardcoding shell commands and interpolating URLs/paths can lead to command injection if inputs are poorly sanitized, and is generally brittle across platforms.
**Prevention:** Use Java's built-in `java.awt.Desktop` API (e.g., `Desktop.getDesktop().browse()` and `Desktop.getDesktop().open()`) to safely hand off URLs and files to the OS.
