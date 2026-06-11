## 2025-02-12 - Missing tooltips on icon-only widgets
**Learning:** In Meteor's custom GUI framework, icon-only widgets (such as `theme.plus()` and `theme.minus()`) lack tooltips by default. This makes them inaccessible to screen readers and visually impaired users.
**Action:** Always manually assign the `.tooltip` property to any icon-only widgets to ensure accessibility.
