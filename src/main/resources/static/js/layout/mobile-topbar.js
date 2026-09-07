/**
 * Layout Mobile-Topbar Module - ObsidianScout
 * Responsive mobile navigation header and off-canvas sidebar drawer toggle.
 */

const HAMBURGER_SVG = `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="4" y1="6" x2="20" y2="6"></line><line x1="4" y1="12" x2="20" y2="12"></line><line x1="4" y1="18" x2="20" y2="18"></line></svg>`;
const CLOSE_SVG = `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>`;

export function injectMobileTopBar() {
    const ensureTopBar = () => {
        if (window.innerWidth >= 900) {
            return;
        }

        const sidebar = document.querySelector(".sidebar");
        const appShell = document.querySelector(".app-shell");
        if (!sidebar || !appShell) {
            return;
        }

        if (document.querySelector(".mobile-topbar")) {
            return;
        }

        const topBar = document.createElement("header");
        topBar.className = "mobile-topbar";
        topBar.innerHTML = `
            <button type="button" class="mobile-menu-button" aria-label="Open menu" aria-expanded="false">
                <span class="hamburger-icon">${HAMBURGER_SVG}</span>
            </button>
            <div class="mobile-topbar-brand">ObsidianScout</div>
        `;

        appShell.parentNode.insertBefore(topBar, appShell);

        let overlay = document.querySelector(".sidebar-overlay");
        if (!overlay) {
            overlay = document.createElement("div");
            overlay.className = "sidebar-overlay";
            document.body.appendChild(overlay);
        }

        const button = topBar.querySelector(".mobile-menu-button");
        const iconContainer = button.querySelector(".hamburger-icon");

        const setMobileOpen = (open) => {
            sidebar.classList.toggle("mobile-open", open);
            overlay.classList.toggle("visible", open);
            button.setAttribute("aria-expanded", open.toString());
            button.setAttribute("aria-label", open ? "Close menu" : "Open menu");
            button.classList.toggle("is-active", open);
            if (iconContainer) {
                iconContainer.innerHTML = open ? CLOSE_SVG : HAMBURGER_SVG;
            }
        };

        button.addEventListener("click", () => {
            setMobileOpen(!sidebar.classList.contains("mobile-open"));
        });

        overlay.addEventListener("click", () => setMobileOpen(false));

        sidebar.querySelectorAll(".sidebar-link").forEach((link) => {
            link.addEventListener("click", () => setMobileOpen(false));
        });

        window.addEventListener("resize", () => {
            if (window.innerWidth >= 900) {
                setMobileOpen(false);
            }
        });
    };

    ensureTopBar();
    window.addEventListener("resize", ensureTopBar);
}
