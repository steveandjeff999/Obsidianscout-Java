/**
 * Component Banners Module - ObsidianScout
 * Fetches, renders, and manages dismissible & expandable system alert banners.
 */

import { request } from '../base/http.js';

export async function loadAndRenderBanners() {
    const mainContent = document.querySelector(".main-content") || document.querySelector(".login-shell") || document.querySelector(".shell");
    if (!mainContent) return;

    try {
        const page = document.body.dataset.page;
        if (page === "reset-password") return;

        let banners = [];
        let isQuorumLostError = false;
        try {
            if (page === "login") {
                banners = await request("/api/banners/login");
            } else {
                banners = await request("/api/banners");
            }
        } catch (err) {
            if (err.status === 503) {
                isQuorumLostError = true;
                banners = [{
                    id: "sys-db-quorum-lost",
                    teamNumber: 0,
                    message: "🚨 Database Quorum Lost: CockroachDB cluster has lost quorum (majority of nodes offline). Database read/write operations are temporarily restricted until quorum is restored.",
                    bannerType: "danger",
                    isDismissible: false,
                    isExpandable: true,
                    expandableMessage: err.message || "CockroachDB requires a majority consensus of nodes to execute database transactions safely. The cluster is currently under-quorum. Full operation will resume automatically when peer nodes reconnect.",
                    isActive: true
                }];
            } else {
                console.error("Failed to load banners:", err);
                return;
            }
        }

        const hasQuorumBanner = Array.isArray(banners) && banners.some(b => b.id === "sys-db-quorum-lost");
        if (hasQuorumBanner || isQuorumLostError) {
            if (!window._quorumBannerCheckTimer) {
                window._quorumBannerCheckTimer = setInterval(() => {
                    loadAndRenderBanners();
                }, 15000);
            }
        } else if (window._quorumBannerCheckTimer) {
            clearInterval(window._quorumBannerCheckTimer);
            window._quorumBannerCheckTimer = null;
        }

        let container = document.querySelector(".banner-container");
        if (!banners || banners.length === 0) {
            if (container) container.remove();
            return;
        }

        let dismissed = [];
        try {
            const saved = localStorage.getItem("obsidianscout:dismissed_banners");
            if (saved) dismissed = JSON.parse(saved);
        } catch (e) {
            console.warn("Failed to load dismissed banners", e);
        }

        if (!container) {
            container = document.createElement("div");
            container.className = "banner-container";
            mainContent.insertBefore(container, mainContent.firstChild);
        }

        container.innerHTML = "";

        banners.forEach(banner => {
            if (banner.isDismissible && dismissed.includes(banner.id)) {
                return;
            }

            const item = document.createElement("div");
            item.className = `banner-item banner-${banner.bannerType}`;
            item.dataset.id = banner.id;

            let html = `
                <div class="banner-body">
                    <div class="banner-message">${banner.message}</div>
            `;

            if (banner.isExpandable && banner.expandableMessage) {
                html += `
                    <div class="banner-details hidden">${banner.expandableMessage}</div>
                    <button class="btn-banner-toggle" type="button">Read More</button>
                `;
            }

            html += `</div>`;

            if (banner.isDismissible) {
                html += `<button class="btn-banner-close" type="button" aria-label="Close banner">&times;</button>`;
            }

            item.innerHTML = html;

            if (banner.isExpandable && banner.expandableMessage) {
                const toggleBtn = item.querySelector(".btn-banner-toggle");
                const details = item.querySelector(".banner-details");
                toggleBtn.addEventListener("click", () => {
                    const isHidden = details.classList.toggle("hidden");
                    toggleBtn.textContent = isHidden ? "Read More" : "Show Less";
                });
            }

            if (banner.isDismissible) {
                const closeBtn = item.querySelector(".btn-banner-close");
                closeBtn.addEventListener("click", () => {
                    item.remove();
                    dismissed.push(banner.id);
                    try {
                        localStorage.setItem("obsidianscout:dismissed_banners", JSON.stringify(dismissed));
                    } catch (e) {
                        console.warn("Failed to save dismissed banners", e);
                    }
                    if (container.children.length === 0) {
                        container.remove();
                    }
                });
            }

            container.appendChild(item);
        });
    } catch (error) {
        console.error("Failed to load banners:", error);
    }
}
