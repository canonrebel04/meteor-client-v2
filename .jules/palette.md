## 2023-11-20 - Custom GUI System Placeholder Support
**Learning:** The custom `GuiTheme` UI framework in Meteor Client supports native placeholder text natively in its `textBox` initialization signatures (e.g., `theme.textBox(text, placeholder)`), which improves discoverability in frequently used inputs like the module search bar without requiring custom rendering code.
**Action:** Always check the overloaded methods of standard UI builders (`theme.textBox`) to apply quick inline micro-UX improvements like placeholder text or tooltips, avoiding manual event-handling or renderer overrides.
## 2025-06-04 - Icon-only button accessibility
**Learning:** Icon-only buttons (like WConfirmedMinus) in the custom GUI framework lack tooltips by default, making them inaccessible to screen readers and visually impaired users.
**Action:** Always explicitly assign the `.tooltip` property to icon-only widgets to ensure screen readers and users can identify their function.
## 2026-06-07 - Added Tooltips to Icon-Only Buttons
**Learning:** Icon-only buttons in Meteor's custom GUI (e.g. WButton with GuiRenderer.EDIT, WConfirmedMinus) do not have tooltips by default, which can cause accessibility issues and poor user experience, especially since standard buttons typically just display strings.
**Action:** Always assign a descriptive string to the .tooltip property of icon-only widgets (such as theme.button(GuiRenderer.EDIT) or theme.minus()) when creating them to ensure users can identify their function.
## 2026-06-09 - Tooltips for Custom Icon-Only UI Widgets
**Learning:** Icon-only widgets (like `WMinus`) in this custom GUI framework lack accessible names by default, which hides their functionality from screen readers.
**Action:** Always assign the `.tooltip` property to `WMinus`, `WPlus`, and other icon-only components upon instantiation to ensure accessibility.
