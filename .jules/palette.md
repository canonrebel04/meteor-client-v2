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
## 2025-01-20 - Add tooltips to icon-only buttons
**Learning:** Found that custom GUI systems with icon-only widgets (such as WButton with GuiRenderer.EDIT, WMinus, WConfirmedMinus) often lack accessibility labels by default. In Meteor Client, setting the `.tooltip` property is required to make these elements accessible and their purpose clear.
**Action:** Always ensure icon-only buttons in custom UI frameworks have an explicit `.tooltip` or ARIA equivalent set to ensure screen reader compatibility and clear visual indication for users.
## 2025-02-12 - Missing tooltips on icon-only widgets
**Learning:** In Meteor's custom GUI framework, icon-only widgets (such as `theme.plus()` and `theme.minus()`) lack tooltips by default. This makes them inaccessible to screen readers and visually impaired users.
**Action:** Always manually assign the `.tooltip` property to any icon-only widgets to ensure accessibility.
## 2024-06-13 - Missing tooltips on icon-only buttons
**Learning:** Icon-only buttons in this custom GUI framework (like `theme.button(GuiRenderer.EDIT)`, `theme.minus()`, `theme.plus()`, `theme.confirmedMinus()`) do not have tooltips by default. This makes their purpose unclear to users, especially for those relying on screen readers or those who aren't familiar with the icons.
**Action:** Always assign a descriptive string to the `tooltip` property of icon-only widgets to ensure accessibility and clarity.
## 2024-05-24 - Icon-only Widgets Missing Tooltips
**Learning:** Icon-only widgets (such as `theme.button(GuiRenderer.EDIT)` or `theme.minus()`) in the custom GUI framework lack tooltips by default and must have the `.tooltip` property explicitly assigned to be accessible to screen readers and visually impaired users.
**Action:** When adding icon-only buttons in the custom GUI framework, always verify that the `.tooltip` property is explicitly set.
## 2024-06-28 - Custom GUI System Placeholder Support for Search Inputs
**Learning:** Search inputs in the custom `GuiTheme` UI framework often lack placeholder text (e.g., initialized as `theme.textBox("")`). Using the overloaded method `theme.textBox(text, placeholder)` improves discoverability and usability of these search fields.
**Action:** Ensure search bar inputs (e.g., in HUD element screens, setting tabs) use the overloaded method to display helpful placeholder text like "Search elements...".
