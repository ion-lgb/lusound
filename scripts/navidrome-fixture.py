"""Run an isolated real Navidrome for Android instrumentation tests (Python 3 stdlib)."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import wave
from typing import TypedDict


class MediaId(TypedDict):
    id: str


class AlbumList(TypedDict, total=False):
    album: list[MediaId]


class Album(TypedDict):
    song: list[MediaId]


class FixtureResponse(TypedDict, total=False):
    status: str
    albumList2: AlbumList
    album: Album


def request(endpoint: str, params: dict[str, str]) -> FixtureResponse:
    salt = "lusound-test"
    query = {"u": "admin", "s": salt, "t": hashlib.md5(("lusound-isolated-test-only" + salt).encode()).hexdigest(),
             "v": "1.16.1", "c": "LuSoundTest", "f": "json", **params}
    url = "http://127.0.0.1:4534/rest/" + endpoint + ".view?" + urllib.parse.urlencode(query)
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "LuSoundTest"}), timeout=5) as response:
        body: FixtureResponse = json.load(response)["subsonic-response"]
    if body["status"] != "ok":
        raise RuntimeError(f"Fixture {endpoint} failed: {body}")
    for entries in (body.get("albumList2", {}).get("album", []), body.get("album", {"song": []})["song"]):
        if not isinstance(entries, list) or any(not isinstance(item, dict) or not isinstance(item.get("id"), str) or not item["id"] for item in entries):
            raise ValueError(f"Fixture {endpoint} returned invalid media IDs")
    return body


def main(binary: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="lusound-navidrome-") as folder:
        root = Path(folder)
        music = root / "music"
        music.mkdir()
        with wave.open(str(music / "LuSound integration.wav"), "wb") as audio:
            audio.setparams((1, 2, 44100, 0, "NONE", "not compressed"))
            audio.writeframes(b"".join(struct.pack("<h", int(math.sin(i * 2 * math.pi * 440 / 44100) * 1000)) for i in range(44100 * 15)))
        config = root / "navidrome.toml"
        config.write_text(f'Address = "127.0.0.1"\nPort = 4534\nMusicFolder = "{music}"\nDataFolder = "{root / "data"}"\nEnableInsightsCollector = false\nDevAutoCreateAdminPassword = "lusound-isolated-test-only"\n[Scanner]\nExtractor = "taglib"\n')
        process = subprocess.Popen([str(binary), "--configfile", str(config)])
        try:
            for attempt in range(30):
                if process.poll() is not None:
                    raise RuntimeError(f"Navidrome exited with status {process.returncode}")
                try:
                    albums = request("getAlbumList2", {"type": "alphabeticalByName"})["albumList2"].get("album", [])
                    if albums:
                        songs = request("getAlbum", {"id": albums[0]["id"]})["album"]["song"]
                        request("createPlaylist", {"name": "LuSound cloud test", "songId": songs[0]["id"]})
                        break
                except (urllib.error.URLError, ConnectionError) as error:
                    print(f"Fixture startup retry {attempt + 1}: {error}", flush=True)
                time.sleep(1)
            else:
                raise TimeoutError("Navidrome did not expose generated audio within 30 seconds")
            print("Fixture ready. Run adb reverse tcp:4534 tcp:4534, then connectedDebugAndroidTest. Ctrl-C stops and deletes fixture.", flush=True)
            if process.wait() != 0:
                raise RuntimeError(f"Navidrome exited with status {process.returncode}")
        finally:
            process.terminate()
            process.wait(timeout=10)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    arguments = parser.parse_args()
    main(arguments.binary.resolve(strict=True))
