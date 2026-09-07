"""Configure an unused, isolated Jellyfin on localhost:8097 with a generated-music directory."""
import base64
import json
from pathlib import Path
import sys
import time
import urllib.request
import urllib.parse


def request(method: str, path: str, payload: bytes | None, token: str) -> bytes:
    headers = {"Content-Type": "application/json", "User-Agent": "LuSoundTest",
               "Authorization": 'MediaBrowser Client="LuSoundTest", Device="Integration", DeviceId="lusound-test", Version="0.3.0"'}
    if token:
        headers["X-Emby-Token"] = token
    with urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8097" + path, payload, headers, method=method), timeout=30) as response:
        return response.read()


def main(music: Path) -> None:
    info = json.loads(request("GET", "/System/Info/Public", None, ""))
    if info["StartupWizardCompleted"]:
        raise RuntimeError("Refusing to modify a configured server; use a fresh isolated data directory on port 8097")
    request("POST", "/Startup/Configuration", json.dumps({"UICulture": "en-US", "MetadataCountryCode": "US", "PreferredMetadataLanguage": "en"}).encode(), "")
    request("GET", "/Startup/User", None, "")
    request("POST", "/Startup/User", json.dumps({"Name": "admin", "Password": "lusound-isolated-test-only"}).encode(), "")
    request("POST", "/Startup/RemoteAccess", b'{"EnableRemoteAccess":false,"EnableAutomaticPortMapping":false}', "")
    request("POST", "/Startup/Complete", b"", "")
    login = json.loads(request("POST", "/Users/AuthenticateByName", b'{"Username":"admin","Pw":"lusound-isolated-test-only"}', ""))
    token: str = login["AccessToken"]
    user: str = login["User"]["Id"]
    if not isinstance(token, str) or not token or not isinstance(user, str) or not user:
        raise ValueError("Jellyfin login returned invalid credentials")
    query = urllib.parse.urlencode({"name": "Integration", "collectionType": "music", "paths": str(music), "refreshLibrary": "true"})
    request("POST", "/Library/VirtualFolders?" + query, b"{}", token)
    for attempt in range(60):
        items = json.loads(request("GET", "/Items?recursive=true&includeItemTypes=Audio&userId=" + user, None, token))["Items"]
        if items:
            ids = [item["Id"] for item in items]
            if any(not isinstance(item, str) or not item for item in ids):
                raise ValueError("Jellyfin returned invalid song IDs")
            for item_id in ids:
                image = urllib.request.Request("http://127.0.0.1:8097/Items/" + item_id + "/Images/Primary",
                    data=base64.b64encode((music / "folder.png").read_bytes()),
                    headers={"Content-Type": "image/png", "X-Emby-Token": token, "User-Agent": "LuSoundTest"}, method="POST")
                with urllib.request.urlopen(image, timeout=30) as response:
                    response.read()
            request("POST", "/Playlists", json.dumps({"Name": "LuSound cloud test", "Ids": ids, "UserId": user, "MediaType": "Audio"}).encode(), token)
            print("Jellyfin fixture ready with audio, artwork and a playlist.")
            return
        print(f"Waiting for real Jellyfin scan: {attempt + 1}/60", flush=True)
        time.sleep(1)
    raise TimeoutError("Jellyfin did not index the test audio within 60 seconds")


if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve(strict=True))
