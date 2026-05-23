## 2023-11-20 - Pre-fetch expensive operations outside loops
**Learning:** In Minecraft mods, checking inventory items inside a loop (like `onTick` combined with `blockInteractionRange`) causes redundant processing. Pre-fetching results outside the loop saves CPU cycles, especially when checking for specific items in the hotbar.
**Action:** When iterating over blocks in `onTick` (or similar high-frequency methods) and searching for an item, pre-fetch the item using `InvUtils.findInHotbar` before the loop and add an early return if not found.
