## 2024-06-13 - Missing tooltips on icon-only buttons
**Learning:** Icon-only buttons in this custom GUI framework (like `theme.button(GuiRenderer.EDIT)`, `theme.minus()`, `theme.plus()`, `theme.confirmedMinus()`) do not have tooltips by default. This makes their purpose unclear to users, especially for those relying on screen readers or those who aren't familiar with the icons.
**Action:** Always assign a descriptive string to the `tooltip` property of icon-only widgets to ensure accessibility and clarity.
