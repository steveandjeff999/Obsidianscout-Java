const CACHE_NAME = 'obsidianscout-shell-v19';
const NAVIGATION_TIMEOUT_MS = 4000;

// Minimal critical assets pre-cached during install to avoid connection pool saturation
const ASSETS = [
    '/favicon.ico',
    '/css/app.css',
    '/js/common.js'
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

// Install: Cache only the minimal critical assets
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(async (cache) => {
                console.log('[ServiceWorker] Pre-caching minimal critical assets');
                for (const url of ASSETS) {
                    try {
                        const response = await fetch(url);
                        if (response.status === 200) {
                            await cache.put(url, response);
                        }
                    } catch (err) {
                        console.warn(`[ServiceWorker] Failed to fetch critical asset ${url}:`, err);
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
    // If online, bypass the Service Worker completely to ensure native network loading
    if (navigator.onLine) {
        return;
    }

    const url = new URL(event.request.url);

    // Skip non-GET requests, API calls, and HTML/navigation requests
    if (event.request.method !== 'GET' || 
        url.pathname.startsWith('/api') || 
        event.request.mode === 'navigate' || 
        url.pathname.endsWith('.html') || 
        url.pathname === '/' || 
        !url.pathname.includes('.')) {
        return;
    }

    // Static assets (JS, CSS, images, vendor libraries): Cache-First, fallback to Network
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
