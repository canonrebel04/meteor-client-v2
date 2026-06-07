## 2026-06-07 - Added Tooltips to Icon-Only Buttons
**Learning:** Icon-only buttons in Meteor's custom GUI (e.g. WButton with GuiRenderer.EDIT, WConfirmedMinus) do not have tooltips by default, which can cause accessibility issues and poor user experience, especially since standard buttons typically just display strings.
**Action:** Always assign a descriptive string to the .tooltip property of icon-only widgets (such as theme.button(GuiRenderer.EDIT) or theme.minus()) when creating them to ensure users can identify their function.
