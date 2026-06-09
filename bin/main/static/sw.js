const CACHE_NAME = 'obsidianscout-shell-v7';
const NAVIGATION_TIMEOUT_MS = 4000;

const ASSETS = [
    '/',
    '/dashboard',
    '/scout',
    '/pit-scout',
    '/qual-scout',
    '/pit-data',
    '/analytics',
    '/graphs',
    '/events',
    '/teams',
    '/matches',
    '/predictor',
    '/alliances',
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
    '/js/pit-data.js',
    '/js/analytics.js',
    '/js/graphs.js',
    '/js/events.js',
    '/js/teams.js',
    '/js/matches.js',
    '/js/predictor.js',
    '/js/alliances.js',
    '/js/users.js',
    '/js/settings.js',
    '/vendor/plotly-2.32.0.min.js'
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
            .then((cache) => {
                console.log('[ServiceWorker] Pre-caching offline shell assets');
                return cache.addAll(ASSETS);
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

    // Stale-While-Revalidate strategy for HTML/navigation pages and static assets
    event.respondWith(
        caches.match(event.request).then((cachedResponse) => {
            const fetchPromise = fetch(event.request).then((networkResponse) => {
                if (networkResponse.status === 200) {
                    const responseClone = networkResponse.clone();
                    caches.open(CACHE_NAME).then((cache) => {
                        cache.put(event.request, responseClone);
                    });
                }
                return networkResponse;
            }).catch(() => {
                // Ignore fetch errors in background revalidation
            });

            if (cachedResponse) {
                return cachedResponse;
            }

            // If it's a navigation request and not in cache, fallback to '/' (index.html) if offline
            if (event.request.mode === 'navigate' || url.pathname.endsWith('.html') || url.pathname === '/') {
                return fetchPromise.catch(() => caches.match('/'));
            }

            return fetchPromise;
        })
    );
});
