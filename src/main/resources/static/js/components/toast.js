/**
 * Component Toast Module - ObsidianScout
 * Non-blocking floating toast notification messages with haptic feedback and auto-dismiss.
 */

import { triggerHaptic } from '../utilities/haptics.js';
import { setButtonLoading } from '../base/http.js';

export const toastRootId = "toast-root";

export function showToast(message, tone = "info") {
    let root = document.getElementById(toastRootId);
    if (!root) {
        root = document.createElement("div");
        root.id = toastRootId;
        document.body.appendChild(root);
    }
    const toast = document.createElement("div");
    toast.className = `toast ${tone}`;
    toast.textContent = message;
    root.appendChild(toast);
    setTimeout(() => {
        toast.remove();
    }, 2800);

    if (tone === "success") {
        triggerHaptic("success");
    } else if (tone === "error") {
        triggerHaptic("error");
        try {
            document.querySelectorAll('button[data-loading="true"], button.is-loading, input[type="submit"][data-loading="true"]').forEach((btn) => {
                setButtonLoading(btn, false);
            });
        } catch (e) {}
    } else if (tone === "warning") {
        triggerHaptic("warning");
        try {
            document.querySelectorAll('button[data-loading="true"], button.is-loading, input[type="submit"][data-loading="true"]').forEach((btn) => {
                setButtonLoading(btn, false);
            });
        } catch (e) {}
    }
}
