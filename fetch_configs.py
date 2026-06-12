import sys
import json
import urllib.request
import urllib.error

def fetch_configs(base_url="http://localhost:8080"):
    print(f"Connecting to ObsidianScout server at {base_url}...")

    # 1. Login to obtain JWT Token
    login_url = f"{base_url}/api/mobile/auth/login"
    login_data = {
        "username": "Seth Herod",
        "password": "5454",
        "team_number": 5454
    }
    
    req_body = json.dumps(login_data).encode("utf-8")
    req = urllib.request.Request(
        login_url,
        data=req_body,
        headers={"Content-Type": "application/json"},
        method="POST"
    )

    try:
        with urllib.request.urlopen(req) as response:
            res_body = json.loads(response.read().decode("utf-8"))
            token = res_body.get("token")
            if not token:
                print("Error: Login response did not contain a token.")
                return
            print("Login successful! Token acquired.")
    except urllib.error.URLError as e:
        print(f"Error during login request: {e}")
        print("Please check that the server is running.")
        sys.exit(1)

    # Helper function to perform authenticated GET requests
    def get_config(endpoint_path, name):
        url = f"{base_url}{endpoint_path}"
        print(f"\nFetching {name} from {endpoint_path}...")
        req = urllib.request.Request(
            url,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json"
            },
            method="GET"
        )
        try:
            with urllib.request.urlopen(req) as response:
                content = json.loads(response.read().decode("utf-8"))
                print(f"SUCCESS: Fetched {name}!")
                print(json.dumps(content, indent=2))
                return content
        except urllib.error.HTTPError as e:
            print(f"HTTP Error {e.code} fetching {name}: {e.read().decode('utf-8')}")
        except urllib.error.URLError as e:
            print(f"Network Error fetching {name}: {e}")

    # 2. Fetch Match Config
    get_config("/api/mobile/config/game", "Match Configuration")

    # 3. Fetch Qualitative Config
    get_config("/api/mobile/config/qualitative", "Qualitative Configuration")

    # 4. Fetch Pit Config
    get_config("/api/mobile/config/pit", "Pit Configuration")

if __name__ == "__main__":
    url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
    fetch_configs(url)
