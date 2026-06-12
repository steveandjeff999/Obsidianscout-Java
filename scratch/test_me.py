import urllib.request
import urllib.parse
import json
import ssl
import random
import time

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

# Register
username = f"user_{int(time.time())}_{random.randint(1000, 9999)}"
register_data = {
    "username": username,
    "email": f"{username}@test.com",
    "teamNumber": 862,
    "password": "testpassword",
    "role": "SCOUT",
    "keepMeLoggedIn": False
}

register_url = "https://127.0.0.1:8443/api/auth/register"
req1 = urllib.request.Request(
    register_url,
    data=json.dumps(register_data).encode('utf-8'),
    headers={'Content-Type': 'application/json'},
    method='POST'
)

print("Registering...")
try:
    with urllib.request.urlopen(req1, context=ctx) as response1:
        print("Register Status:", response1.status)
        cookie_header = None
        for key, val in response1.getheaders():
            if key.lower() == 'set-cookie':
                cookie_header = val
                break
        
        print("Received Cookie:", cookie_header)
        if not cookie_header:
            print("No cookie received!")
            exit(1)
            
        # Parse cookie value (only the key=value part)
        cookie_val = cookie_header.split(';')[0]
        
        # Now call /api/auth/me
        me_url = "https://127.0.0.1:8443/api/auth/me"
        req2 = urllib.request.Request(
            me_url,
            headers={'Cookie': cookie_val},
            method='GET'
        )
        
        print("Calling /api/auth/me...")
        with urllib.request.urlopen(req2, context=ctx) as response2:
            print("Me Status:", response2.status)
            print("Me Body:", response2.read().decode('utf-8'))
            
except urllib.error.HTTPError as e:
    print("HTTP Error:", e.code)
    print("Body:", e.read().decode('utf-8'))
except Exception as e:
    print("Error:", e)
