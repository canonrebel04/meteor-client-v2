## 2025-06-04 - Icon-only button accessibility
**Learning:** Icon-only buttons (like WConfirmedMinus) in the custom GUI framework lack tooltips by default, making them inaccessible to screen readers and visually impaired users.
**Action:** Always explicitly assign the `.tooltip` property to icon-only widgets to ensure screen readers and users can identify their function.
