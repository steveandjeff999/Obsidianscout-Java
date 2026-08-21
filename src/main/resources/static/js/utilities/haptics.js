/**
 * Utility Haptics Module - ObsidianScout
 * Vibration API driver and global event delegation for touch and interaction feedback.
 */

import { safeGetItem } from '../base/storage.js';

export function triggerHaptic(type = "light") {
    if (typeof navigator !== "undefined" && navigator.vibrate) {
        const hapticSetting = safeGetItem("obsidianscout:haptic_feedback") || "enabled";
        if (hapticSetting !== "enabled") {
            return;
        }
        try {
            switch (type) {
                case "light":
                case "tap":
                case "click":
                    navigator.vibrate(12);
                    break;
                case "medium":
                    navigator.vibrate(30);
                    break;
                case "success":
                    navigator.vibrate([20, 50, 20]);
                    break;
                case "warning":
                case "error":
                    navigator.vibrate([60, 50, 60]);
                    break;
            }
        } catch (e) {
            console.warn("Haptic feedback failed:", e);
        }
    }
}

export function initHapticDelegation() {
    document.addEventListener("click", (e) => {
        const target = e.target;
        const button = target.closest("button");
        if (button) {
            if (button.disabled) return;
            triggerHaptic("light");
            return;
        }
        if (target.type === "checkbox") {
            triggerHaptic("light");
            return;
        }
    }, { passive: true });

    document.addEventListener("input", (e) => {
        if (e.target.type === "range") {
            triggerHaptic("light");
        }
    }, { passive: true });

    document.addEventListener("change", (e) => {
        if (e.target.tagName === "SELECT") {
            triggerHaptic("light");
        }
    }, { passive: true });
}
