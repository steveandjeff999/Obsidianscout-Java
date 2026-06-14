const CACHE_NAME = 'obsidianscout-shell-v15';
const NAVIGATION_TIMEOUT_MS = 4000;

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
    '/base.html',
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
    '/vendor/plotly-2.32.0.min.js',
    '/vendor/qrcode.min.js',
    '/vendor/qr-scanner.min.js',
    '/vendor/qr-scanner-worker.min.js'
    ,'/i18n/en.json'
    ,'/i18n/es.json'
    ,'/i18n/tr.json'
    ,'/i18n/he.json'
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

// Install: Cache all the application shell assets
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(async (cache) => {
                console.log('[ServiceWorker] Resiliently pre-caching offline shell assets sequentially');
                for (const url of ASSETS) {
                    try {
                        const response = await fetch(url);
                        if (response.status === 200) {
                            await cache.put(url, response);
                        } else {
                            console.warn(`[ServiceWorker] Skip caching ${url} (status: ${response.status})`);
                        }
                    } catch (err) {
                        console.warn(`[ServiceWorker] Failed to fetch ${url}:`, err);
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

    // Skip non-GET requests and API calls
    if (event.request.method !== 'GET' || url.pathname.startsWith('/api')) {
        return;
    }

    // 1. Navigation / HTML pages: Network-First with Cache Fallback
    if (event.request.mode === 'navigate' || url.pathname.endsWith('.html') || url.pathname === '/' || !url.pathname.includes('.')) {
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
                    return caches.match(event.request).then((cachedResponse) => {
                        return cachedResponse || caches.match('/');
                    });
                })
        );
        return;
    }

    // 2. Static assets (JS, CSS, images, vendor libraries): Cache-First, fallback to Network
    event.respondWith(
        caches.match(event.request).then((cachedResponse) => {
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
                // Return a fallback or just let it fail
                return new Response('Offline resource not cached', { status: 503, statusText: 'Offline' });
            });
        })
    );
});
