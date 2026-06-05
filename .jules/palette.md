## 2024-05-24 - Icon-only Widgets Missing Tooltips
**Learning:** Icon-only widgets (such as `theme.button(GuiRenderer.EDIT)` or `theme.minus()`) in the custom GUI framework lack tooltips by default and must have the `.tooltip` property explicitly assigned to be accessible to screen readers and visually impaired users.
**Action:** When adding icon-only buttons in the custom GUI framework, always verify that the `.tooltip` property is explicitly set.
