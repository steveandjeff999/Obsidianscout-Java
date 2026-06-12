import urllib.request
import urllib.parse
import json
import ssl
import random
import time

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

url = "https://127.0.0.1:8443/api/auth/register"

def run_test(keep_me_logged_in):
    # Register with a unique username
    username = f"user_{int(time.time())}_{random.randint(1000, 9999)}"
    data = {
        "username": username,
        "email": f"{username}@test.com",
        "teamNumber": 862,
        "password": "testpassword",
        "role": "SCOUT",
        "keepMeLoggedIn": keep_me_logged_in
    }
    
    req = urllib.request.Request(
        url,
        data=json.dumps(data).encode('utf-8'),
        headers={'Content-Type': 'application/json'},
        method='POST'
    )
    
    print(f"--- Running Test with keepMeLoggedIn = {keep_me_logged_in} ---")
    try:
        with urllib.request.urlopen(req, context=ctx) as response:
            print("Status Code:", response.status)
            print("Headers:")
            for key, val in response.getheaders():
                if key.lower() == 'set-cookie':
                    print(f"  ** {key}: {val}")
                else:
                    print(f"  {key}: {val}")
            print("Body:", response.read().decode('utf-8'))
    except Exception as e:
        print("Error:", e)

run_test(False)
time.sleep(1)
run_test(True)
