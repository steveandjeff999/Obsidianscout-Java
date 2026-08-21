/**
 * Base Storage Module - ObsidianScout
 * LocalStorage wrappers, cache clearance, and scroll position persistence.
 */

export function safeGetItem(key) {
    try {
        return localStorage.getItem(key);
    } catch (e) {
        console.warn("[Storage] Failed to read from localStorage:", e);
        return null;
    }
}

export function safeSetItem(key, value) {
    try {
        localStorage.setItem(key, value);
    } catch (e) {
        console.warn("[Storage] Failed to write to localStorage:", e);
    }
}

export function safeRemoveItem(key) {
    try {
        localStorage.removeItem(key);
    } catch (e) {
        console.warn("[Storage] Failed to remove from localStorage:", e);
    }
}

export function clearAllCaches() {
    try {
        const keysToRemove = [];
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (key && key.startsWith("cache:") && key !== "cache:/api/auth/me") {
                keysToRemove.push(key);
            }
        }
        keysToRemove.forEach(k => safeRemoveItem(k));
    } catch (e) {
        console.warn("[Storage] Failed to clear caches:", e);
    }
}

export function saveScrollPositions() {
    try {
        const scrolls = {
            windowX: window.scrollX || window.pageXOffset,
            windowY: window.scrollY || window.pageYOffset,
            elements: []
        };
        document.querySelectorAll('.table-scroll, .main-content, .sidebar').forEach((el, index) => {
            if (el.scrollTop > 0 || el.scrollLeft > 0) {
                scrolls.elements.push({
                    index: index,
                    class: el.className,
                    id: el.id,
                    top: el.scrollTop,
                    left: el.scrollLeft
                });
            }
        });
        safeSetItem("obsidianscout:scroll_positions", JSON.stringify(scrolls));
    } catch (e) {
        console.warn("Failed to save scroll positions:", e);
    }
}

export function restoreScrollPositions() {
    try {
        const saved = safeGetItem("obsidianscout:scroll_positions");
        if (!saved) return;
        safeRemoveItem("obsidianscout:scroll_positions");
        const scrolls = JSON.parse(saved);
        if (!scrolls) return;

        setTimeout(() => {
            window.scrollTo(scrolls.windowX || 0, scrolls.windowY || 0);
            
            scrolls.elements.forEach(item => {
                let el = null;
                if (item.id) {
                    el = document.getElementById(item.id);
                } else {
                    const candidates = document.querySelectorAll('.table-scroll, .main-content, .sidebar');
                    if (candidates[item.index]) {
                        el = candidates[item.index];
                    }
                }
                if (el) {
                    el.scrollTop = item.top;
                    el.scrollLeft = item.left;
                }
            });
        }, 100);
    } catch (e) {
        console.warn("Failed to restore scroll positions:", e);
    }
}
