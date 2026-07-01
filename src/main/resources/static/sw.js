const CACHE_NAME = 'obsidianscout-shell-v25';
const NAVIGATION_TIMEOUT_MS = 4000;

// Application shell assets cached during install
const ASSETS = [
    '/favicon.ico',
    '/assets/images/obsidian/obsidian-192.png',
    '/assets/images/obsidian/obsidian-512.png',
    '/',
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
    '/graphs',
    '/events',
    '/teams',
    '/matches',
    '/predictor',
    '/alliances',
    '/alliance-edit',
    '/all-data',
    '/cache-manager',
    '/qual-data',
    '/team',
    '/users',
    '/config',
    '/css/app.css',
    '/js/common.js',
    '/js/login.js',
    '/js/dashboard.js',
    '/js/scout.js',
    '/js/pit-scout.js',
    '/js/qual-scout.js',
    '/js/prescout-scout.js',
    '/js/prescout-pit.js',
    '/js/prescout-qual.js',
    '/js/qr-scanner.js',
    '/js/pit-data.js',
    '/js/analytics.js',
    '/js/graphs.js',
    '/js/events.js',
    '/js/teams.js',
    '/js/matches.js',
    '/js/predictor.js',
    '/js/alliances.js',
    '/js/alliance-edit.js',
    '/js/all-data.js',
    '/js/cache-manager.js',
    '/js/qual-data.js',
    '/js/team.js',
    '/js/users.js',
    '/js/settings.js',
    '/vendor/qrcode.min.js',
    '/vendor/qr-scanner.min.js',
    '/vendor/qr-scanner-worker.min.js',
    '/vendor/jabcodeJSLib.min.js',
    '/vendor/plotly-2.32.0.min.js',
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
                console.log('[ServiceWorker] Pre-caching offline shell assets sequentially');
                for (const url of ASSETS) {
                    try {
                        const response = await fetch(url);
                        if (response.status === 200) {
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

    // Skip non-GET requests, API calls, and non-http/https requests (like chrome-extension://)
    if (event.request.method !== 'GET' || 
        url.pathname.startsWith('/api') ||
        (url.protocol !== 'http:' && url.protocol !== 'https:')) {
        return;
    }

    // 1. Navigation / HTML pages: Network-First with Cache Fallback
    if (event.request.mode === 'navigate' || 
        url.pathname.endsWith('.html') || 
        url.pathname === '/' || 
        !url.pathname.includes('.')) {
        
        event.respondWith(
            fetch(event.request)
                .then((networkResponse) => {
                    if (networkResponse.status === 200) {
                        const responseClone = networkResponse.clone();
                        caches.open(CACHE_NAME).then((cache) => {
                            cache.put(event.request, responseClone);
                        });
                    }
                    return networkResponse;
                })
                .catch(() => {
                    // Fallback to cache if offline
                    return caches.match(event.request, { ignoreSearch: true }).then((cachedResponse) => {
                        if (cachedResponse) {
                            return cachedResponse;
                        }
                        // Fallback to root index page if specific page not cached
                        return caches.match('/', { ignoreSearch: true });
                    });
                })
        );
        return;
    }

    // 2. Static assets (JS, CSS, images, vendor libraries): Cache-First, fallback to Network
    event.respondWith(
        caches.match(event.request, { ignoreSearch: true }).then((cachedResponse) => {
            if (cachedResponse) {
                return cachedResponse;
            }
            return fetch(event.request).then((networkResponse) => {
                if (networkResponse.status === 200) {
                    const responseClone = networkResponse.clone();
                    caches.open(CACHE_NAME).then((cache) => {
                        cache.put(event.request, responseClone);
                    });
                }
                return networkResponse;
            }).catch(() => {
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
