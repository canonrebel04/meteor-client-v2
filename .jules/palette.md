## 2024-06-25 - Missing tooltips on icon-only buttons
**Learning:** Icon-only interactive widgets in the custom GUI framework (like WMinus, WPlus, and WButton(GuiRenderer.EDIT)) lack built-in accessible labels or tooltips, causing poor UX for screen readers and new users.
**Action:** Consistently add explanatory tooltips using `widget.tooltip = "Action"` when creating icon-only buttons in screens and tabs.
