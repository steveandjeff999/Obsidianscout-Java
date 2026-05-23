const CACHE_NAME = 'obsidianscout-shell-v1';

const ASSETS = [
    '/',
    '/index.html',
    '/dashboard.html',
    '/scout.html',
    '/analytics.html',
    '/graphs.html',
    '/events.html',
    '/teams.html',
    '/matches.html',
    '/users.html',
    '/config.html',
    '/css/app.css',
    '/js/common.js',
    '/js/login.js',
    '/js/dashboard.js',
    '/js/scout.js',
    '/js/analytics.js',
    '/js/graphs.js',
    '/js/events.js',
    '/js/teams.js',
    '/js/matches.js',
    '/js/users.js',
    '/js/settings.js',
    '/vendor/plotly-2.32.0.min.js'
];

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

    // Network-First strategy with Cache Fallback for HTML/navigation pages
    if (event.request.mode === 'navigate' || url.pathname.endsWith('.html') || url.pathname === '/') {
        event.respondWith(
            fetch(event.request)
                .then((response) => {
                    // Update cache with the fresh page
                    const responseClone = response.clone();
                    caches.open(CACHE_NAME).then((cache) => {
                        cache.put(event.request, responseClone);
                    });
                    return response;
                })
                .catch(() => {
                    // Fall back to cache
                    return caches.match(event.request).then((cachedResponse) => {
                        if (cachedResponse) {
                            return cachedResponse;
                        }
                        // Fallback to index.html if we are completely offline and page is not cached
                        return caches.match('/index.html');
                    });
                })
        );
        return;
    }

    // Cache-First strategy with Network Fallback for static assets (CSS, JS)
    event.respondWith(
        caches.match(event.request)
            .then((cachedResponse) => {
                if (cachedResponse) {
                    return cachedResponse;
                }
                return fetch(event.request).then((response) => {
                    // Cache the newly fetched asset
                    if (response.status === 200) {
                        const responseClone = response.clone();
                        caches.open(CACHE_NAME).then((cache) => {
                            cache.put(event.request, responseClone);
                        });
                    }
                    return response;
                });
            })
            .catch(() => {
                // If both fail, let it error naturally
            })
    );
});
