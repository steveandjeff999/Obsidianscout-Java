/**
 * Service Chat-Poller Module - ObsidianScout
 * Background unread message & mention counter polling for the global navigation chat badge.
 */

import { request } from '../base/http.js';
import { checkLoginStatus } from '../base/auth.js';

export async function initChatUnreadPolling() {
    const page = document.body.dataset.page;
    if (page === "login" || page === "reset-password") return;

    const loggedIn = await checkLoginStatus();
    if (!loggedIn) return;

    try {
        const settingsResponse = await request("/api/settings?local=true", { timeoutMs: 3000 });
        if (!settingsResponse || !settingsResponse.settings || !settingsResponse.settings.chatEnabled) {
            return;
        }
    } catch (e) {
        console.warn("Failed to fetch settings for chat unreads:", e);
        return;
    }

    let pollInterval = null;

    async function fetchUnreadStatus() {
        try {
            const status = await request("/api/chat/unread-status", { timeoutMs: 3000 });
            if (status) {
                updateChatBadge(status.unreadCount, status.mentionCount);
            }
        } catch (e) {
            if (e.status === 401 && pollInterval) {
                clearInterval(pollInterval);
            }
            console.warn("Failed to fetch chat unread status:", e);
        }
    }

    function updateChatBadge(unreadCount, mentionCount) {
        const chatLink = document.getElementById("nav-chat");
        if (!chatLink) return;

        // Remove existing
        const existingBadge = chatLink.querySelector(".nav-chat-badge, .nav-chat-dot");
        if (existingBadge) {
            existingBadge.remove();
        }

        if (mentionCount > 0) {
            const badge = document.createElement("span");
            badge.className = "nav-chat-badge";
            badge.textContent = mentionCount;
            chatLink.appendChild(badge);
        } else if (unreadCount > 0) {
            const dot = document.createElement("span");
            dot.className = "nav-chat-dot";
            chatLink.appendChild(dot);
        }
    }

    // Poll every 30 seconds
    pollInterval = setInterval(fetchUnreadStatus, 30000);

    // Initial fetch
    fetchUnreadStatus();

    // Listen for immediate update events when user reads messages
    window.addEventListener("obsidianscout:chat-read", () => {
        fetchUnreadStatus();
    });
}
