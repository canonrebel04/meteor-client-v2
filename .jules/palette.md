## 2023-11-20 - Custom GUI System Placeholder Support
**Learning:** The custom `GuiTheme` UI framework in Meteor Client supports native placeholder text natively in its `textBox` initialization signatures (e.g., `theme.textBox(text, placeholder)`), which improves discoverability in frequently used inputs like the module search bar without requiring custom rendering code.
**Action:** Always check the overloaded methods of standard UI builders (`theme.textBox`) to apply quick inline micro-UX improvements like placeholder text or tooltips, avoiding manual event-handling or renderer overrides.
