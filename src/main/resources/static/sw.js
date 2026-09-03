const CACHE_NAME = 'obsidianscout-shell-v38';
const NAVIGATION_TIMEOUT_MS = 4000;

// Application shell assets cached during install
const ASSETS = [
    '/favicon.ico',
    '/manifest.json',
    '/assets/images/obsidian/obsidian-192.png',
    '/assets/images/obsidian/obsidian-512.png',
    '/base.html',
    '/',
    '/index.html',
    '/reset-password',
    '/dashboard',
    '/scout',
    '/pit-scout',
    '/qual-scout',
    '/prescout',
    '/prescout-scout',
    '/prescout-pit',
    '/prescout-qual',
    '/qr-scanner',
    '/pit-data',
    '/analytics',
    '/custom-analytics',
    '/data-validation',
    '/graphs',
    '/events',
    '/teams',
    '/rankings',
    '/qual-rankings',
    '/team',
    '/matches',
    '/predictor',
    '/event-predictor',
    '/alliances',
    '/alliance-edit',
    '/alliance-selection',
    '/all-data',
    '/cache-manager',
    '/qual-data',
    '/users',
    '/config',
    '/admin-settings',
    '/cluster-management',
    '/storage-manager',
    '/fcm-settings',
    '/default-configs',
    '/backup',
    '/banners',
    '/chat',
    '/docs',
    '/contact',
    '/migration',
    '/config-migration',
    '/schema-history',
    '/theme-editor',
    '/404',
    '/500',
    '/css/app.css',
    '/css/base/variables.css',
    '/css/base/reset.css',
    '/css/base/typography.css',
    '/css/layout/shell.css',
    '/css/layout/navigation.css',
    '/css/layout/nav-layouts.css',
    '/css/layout/responsive.css',
    '/css/components/buttons.css',
    '/css/components/cards.css',
    '/css/components/forms.css',
    '/css/components/tables.css',
    '/css/components/modals.css',
    '/css/components/alerts-banners.css',
    '/css/components/widgets.css',
    '/css/pages/dashboard.css',
    '/css/pages/scanner.css',
    '/css/pages/team-profile.css',
    '/css/pages/graphs-analytics.css',
    '/css/pages/tour-wizard.css',
    '/css/utilities/helpers.css',
    '/css/utilities/animations.css',
    '/js/common.js',
    '/js/base/storage.js',
    '/js/base/http.js',
    '/js/base/auth.js',
    '/js/base/i18n.js',
    '/js/layout/theme.js',
    '/js/layout/navigation.js',
    '/js/layout/nav-layouts.js',
    '/js/layout/mobile-topbar.js',
    '/js/components/toast.js',
    '/js/components/banners.js',
    '/js/components/connection-widget.js',
    '/js/components/modals.js',
    '/js/components/qr-modal.js',
    '/js/components/setup-wizard.js',
    '/js/components/conflict-modal.js',
    '/js/components/tour-wizard.js',
    '/js/services/offline-sync.js',
    '/js/services/data-compression.js',
    '/js/services/chat-poller.js',
    '/js/utilities/helpers.js',
    '/js/utilities/haptics.js',
    '/js/utilities/media.js',
    '/js/utilities/svg-filters.js',
    '/js/login.js',
    '/js/reset-password.js',
    '/js/dashboard.js',
    '/js/scout.js',
    '/js/pit-scout.js',
    '/js/qual-scout.js',
    '/js/prescout.js',
    '/js/prescout-scout.js',
    '/js/prescout-pit.js',
    '/js/prescout-qual.js',
    '/js/qr-scanner.js',
    '/js/pit-data.js',
    '/js/analytics.js',
    '/js/custom-analytics.js',
    '/js/data-validation.js',
    '/js/graphs.js',
    '/js/events.js',
    '/js/teams.js',
    '/js/rankings.js',
    '/js/qual-rankings.js',
    '/js/team.js',
    '/js/matches.js',
    '/js/predictor.js',
    '/js/event-predictor.js',
    '/js/alliances.js',
    '/js/alliance-edit.js',
    '/js/alliance-selection.js',
    '/js/all-data.js',
    '/js/cache-manager.js',
    '/js/qual-data.js',
    '/js/users.js',
    '/js/settings.js',
    '/js/admin-settings.js',
    '/js/cluster-management.js',
    '/js/storage-manager.js',
    '/js/backup.js',
    '/js/banners.js',
    '/js/chat.js',
    '/js/contact.js',
    '/js/migration.js',
    '/js/config-migration.js',
    '/js/schema-history.js',
    '/js/theme-editor.js',
    '/vendor/qrcode.min.js',
    '/vendor/qr-scanner.min.js',
    '/vendor/qr-scanner-worker.min.js',
    '/vendor/jabcodeJSLib.min.js',
    '/vendor/plotly-2.32.0.min.js',
    '/vendor/marked.min.js',
    '/vendor/html5-qrcode.min.js',
    '/vendor/jsQR.js',
    '/i18n/en.json',
    '/i18n/es.json',
    '/i18n/tr.json',
    '/i18n/he.json'
];

function fetchWithTimeout(request, timeoutMs) {
    return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Network timeout')), timeoutMs);
        fetch(request)
            .then((response) => resolve(response))
            .catch((error) => reject(error))
            .finally(() => clearTimeout(timeout));
    });
}

// Install: Cache all application shell assets sequentially to avoid connection pool saturation
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(async (cache) => {
                console.log(`[ServiceWorker] Pre-caching offline shell assets sequentially for ${CACHE_NAME}`);
                for (const url of ASSETS) {
                    try {
                        // Append version query parameter to bypass Cloudflare edge cache and intermediate CDN caches
                        const fetchUrl = `${url}${url.includes('?') ? '&' : '?'}_sw_ver=${encodeURIComponent(CACHE_NAME)}`;
                        const response = await fetch(fetchUrl, { cache: 'reload' });
                        if (response.status === 200) {
                            // Store under clean URL so normal app requests match cache keys directly
                            await cache.put(url, response);
                        }
                    } catch (err) {
                        console.warn(`[ServiceWorker] Failed to fetch shell asset ${url}:`, err);
                    }
                }
            })
            .then(() => self.skipWaiting())
    );
});

// Activate: Clean up old caches
self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((keys) => {
            return Promise.all(
                keys.map((key) => {
                    if (key !== CACHE_NAME) {
                        console.log('[ServiceWorker] Removing old cache:', key);
                        return caches.delete(key);
                    }
                })
            );
        }).then(() => self.clients.claim())
    );
});

// Fetch: Intercept requests
self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url);

    // Skip non-GET requests, cross-origin requests, API calls, and non-http/https requests (like chrome-extension://)
    if (event.request.method !== 'GET' ||
        url.origin !== self.location.origin ||
        url.pathname.startsWith('/api') ||
        (url.protocol !== 'http:' && url.protocol !== 'https:')) {
        return;
    }

    // 1. Navigation / HTML pages: Network-First with Timeout & Cache Fallback
    if (event.request.mode === 'navigate' ||
        url.pathname.endsWith('.html') ||
        url.pathname === '/' ||
        !url.pathname.includes('.')) {

        event.respondWith(
            fetchWithTimeout(event.request, NAVIGATION_TIMEOUT_MS)
                .then((networkResponse) => {
                    if (networkResponse && networkResponse.status === 200) {
                        const responseClone = networkResponse.clone();
                        caches.open(CACHE_NAME).then((cache) => {
                            cache.put(event.request, responseClone);
                        });
                    }
                    return networkResponse;
                })
                .catch(() => {
                    // Fallback to exact cached request if offline or timed out
                    return caches.match(event.request, { ignoreSearch: true }).then((cachedResponse) => {
                        if (cachedResponse) {
                            return cachedResponse;
                        }
                        // Fallback to pathname match
                        return caches.match(url.pathname, { ignoreSearch: true }).then((pathResponse) => {
                            if (pathResponse) {
                                return pathResponse;
                            }
                            // Fallback to root / index page if specific page not cached
                            return caches.match('/', { ignoreSearch: true }).then((rootResponse) => {
                                return rootResponse || caches.match('/index.html', { ignoreSearch: true });
                            });
                        });
                    });
                })
        );
        return;
    }

    // 2. Static assets (JS, CSS, images, vendor libraries): Stale-While-Revalidate (fetch updated versions in background)
    event.respondWith(
        caches.match(event.request, { ignoreSearch: true }).then((cachedResponse) => {
            const fetchPromise = fetch(event.request).then((networkResponse) => {
                if (networkResponse.status === 200) {
                    const responseClone = networkResponse.clone();
                    caches.open(CACHE_NAME).then((cache) => {
                        cache.put(event.request, responseClone);
                    });
                }
                return networkResponse;
            });

            if (cachedResponse) {
                // Fetch in background to update cache for next time, catching errors silently
                fetchPromise.catch((err) => {
                    console.warn(`[ServiceWorker] Background update failed for ${event.request.url}:`, err);
                });
                return cachedResponse;
            }

            return fetchPromise.catch(() => {
                return new Response('Offline resource not cached', { status: 503, statusText: 'Offline' });
            });
        })
    );
});

// Push event listener: Handle incoming push messages
self.addEventListener('push', (event) => {
    let data = {};
    if (event.data) {
        try {
            data = event.data.json();
        } catch (e) {
            data = { title: 'New Message', body: event.data.text() };
        }
    }

    const title = data.title || 'New Chat Message';
    const options = {
        body: data.body || 'You have received a new message.',
        icon: '/assets/images/obsidian/obsidian-192.png',
        badge: '/assets/images/obsidian/obsidian-192.png',
        tag: data.tag || 'chat-notification',
        data: data.data || { url: '/chat' },
        vibrate: [100, 50, 100],
        actions: [
            { action: 'open', title: 'Open Chat' }
        ]
    };

    event.waitUntil(
        self.registration.showNotification(title, options)
    );
});

// Notification click listener: Open chat or focus tab and route group
self.addEventListener('notificationclick', (event) => {
    event.notification.close();

    let urlToOpen = '/chat';
    if (event.notification.data && event.notification.data.url) {
        urlToOpen = event.notification.data.url;
    }

    event.waitUntil(
        clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windowClients) => {
            // Check if there is already a window open with this URL
            for (let i = 0; i < windowClients.length; i++) {
                const client = windowClients[i];
                if (client.url.includes('/chat') && 'focus' in client) {
                    // Send a message to the page to switch group if needed
                    if (event.notification.data && event.notification.data.groupName) {
                        client.postMessage({
                            type: 'SWITCH_GROUP',
                            groupName: event.notification.data.groupName
                        });
                    }
                    return client.focus();
                }
            }
            // If no window is open, open a new one
            if (clients.openWindow) {
                if (event.notification.data && event.notification.data.groupName) {
                    urlToOpen += `?group=${encodeURIComponent(event.notification.data.groupName)}`;
                }
                return clients.openWindow(urlToOpen);
            }
        })
    );
});
