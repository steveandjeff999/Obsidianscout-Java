/**
 * Layout Mobile-Topbar Module - ObsidianScout
 * Responsive mobile navigation header and off-canvas sidebar drawer toggle.
 */

export function injectMobileTopBar() {
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
            <span class="hamburger-icon">☰</span>
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

    const setMobileOpen = (open) => {
        sidebar.classList.toggle("mobile-open", open);
        overlay.classList.toggle("visible", open);
        button.setAttribute("aria-expanded", open.toString());
        button.setAttribute("aria-label", open ? "Close menu" : "Open menu");
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
}
