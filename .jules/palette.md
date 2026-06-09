## 2026-06-09 - Tooltips for Custom Icon-Only UI Widgets
**Learning:** Icon-only widgets (like `WMinus`) in this custom GUI framework lack accessible names by default, which hides their functionality from screen readers.
**Action:** Always assign the `.tooltip` property to `WMinus`, `WPlus`, and other icon-only components upon instantiation to ensure accessibility.
