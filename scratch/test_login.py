import urllib.request
import urllib.parse
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

url = "https://127.0.0.1:8443/api/auth/login"
data = {
    "username": "admin",
    "teamNumber": 0,
    "password": "change-me",
    "keepMeLoggedIn": False
}

req = urllib.request.Request(
    url,
    data=json.dumps(data).encode('utf-8'),
    headers={'Content-Type': 'application/json'},
    method='POST'
)

try:
    with urllib.request.urlopen(req, context=ctx) as response:
        print("Status Code:", response.status)
        print("Headers:")
        for key, val in response.getheaders():
            print(f"  {key}: {val}")
        print("Body:")
        print(response.read().decode('utf-8'))
except urllib.error.HTTPError as e:
    print("HTTP Error:", e.code)
    print("Headers:")
    for key, val in e.headers.items():
        print(f"  {key}: {val}")
    print("Body:")
    print(e.read().decode('utf-8'))
except Exception as e:
    print("Error:", e)
