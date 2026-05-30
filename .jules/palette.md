## 2024-05-18 - Tooltips and Placeholders in Friends Tab
**Learning:** Found that custom GUI framework elements (WPlus, WMinus, WTextBox) in the Meteor Client sometimes lack necessary context for users. Adding tooltips to icon-only buttons and placeholders to text inputs significantly improves accessibility and clarity.
**Action:** Always check custom widgets for missing tooltips (via the `tooltip` property) and use the `GuiTheme` API's placeholder support for text boxes when implementing or reviewing UI components.
