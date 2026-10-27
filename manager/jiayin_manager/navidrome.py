import hashlib
import secrets
import os
import sqlite3
import time
from pathlib import Path

import httpx

from .providers import endpoint
from .store import stamp


class Navidrome:
    def __init__(self, store, library, transport=None):
        self.store, self.library, self.transport = store, library, transport

    def request(self, method, params=None):
        config = self.store.get("config", "navidrome", {})
        auth = self.store.secret("navidrome-auth")
        if not config or not auth:
            raise ValueError("请先连接 Navidrome 以核对扫描和歌曲身份")
        token, salt = auth.split(":", 1)
        form = {"u": config["username"], "t": token, "s": salt, "v": "1.16.1", "c": "JiayinManager", "f": "json", **(params or {})}
        with httpx.Client(timeout=30, follow_redirects=False, transport=self.transport, trust_env=False) as client:
            try:
                response = client.post(config["url"] + "/rest/" + method + ".view", data=form)
                if response.status_code != 200 or len(response.content) > 16 * 1024 * 1024:
                    raise ValueError("Navidrome 请求失败")
                body = response.json()["subsonic-response"]
                if body.get("status") != "ok":
                    raise ValueError("Navidrome 拒绝请求，请检查账户权限")
                return body
            except (httpx.HTTPError, KeyError, TypeError):
                raise ValueError("Navidrome 连接或响应失败") from None

    def connect(self, url, username, password, client_server=""):
        url = endpoint(url, local=True)
        client_server = endpoint(client_server or url, local=True)
        if not username or not password:
            raise ValueError("请填写服务器账户和密码")
        salt = secrets.token_hex(12)
        token = hashlib.md5((password + salt).encode()).hexdigest()
        previous, previous_auth = self.store.get("config", "navidrome"), self.store.secret("navidrome-auth")
        self.store.put("config", "navidrome", {"url": url, "username": username, "client_server": client_server})
        self.store.set_secret("navidrome-auth", token + ":" + salt)
        try:
            ping = self.request("ping")
            if ping.get("type", "").lower() != "navidrome":
                raise ValueError("首版管理后台仅对 Navidrome 开放扫描与迁移")
            if previous and (previous["url"] != url or previous["username"] != username):
                raise ValueError("此后台已绑定其他服务器账户；请使用独立数据目录部署另一实例")
            self.store.put("config", "navidrome", {"url": url, "username": username, "client_server": client_server, "version": ping.get("serverVersion", "未知")})
        except Exception:
            if previous:
                self.store.put("config", "navidrome", previous)
            else:
                self.store.delete("config", "navidrome")
            self.store.set_secret("navidrome-auth", previous_auth)
            raise
        return self.capture()

    def catalog(self):
        songs, offset = [], 0
        while True:
            batch = self.request("search3", {"query": "", "songCount": "500", "songOffset": str(offset), "artistCount": "0", "albumCount": "0"}).get("searchResult3", {}).get("song", [])
            songs.extend(batch)
            if len(batch) < 500:
                return songs
            offset += 500
            if offset > 500000:
                raise ValueError("曲库规模超出首版上限")

    def capture(self):
        songs = self.catalog()
        physical = self.physical_paths(songs)
        by_path = {}
        for song in songs:
            path = song.get("path", "").replace("\\", "/").lstrip("/")
            by_path.setdefault(path, []).append(song)
        mapped = 0
        for row in self.store.all("track"):
            if row.get("missing"):
                continue
            candidates = physical.get(row["path"], []) if physical is not None else by_path.get(row["path"], [])
            if physical is not None:
                candidates = [s for s in candidates if s.get("size") == row["size"]]
            if not candidates and row.get("server_id"):
                candidates = [s for s in songs if s["id"] == row["server_id"]]
            # Paths may be virtual. Do not infer identity from title/artist alone.
            if len(candidates) != 1 or abs(candidates[0].get("duration", 0) - row["duration"]) > 3:
                continue
            song = candidates[0]
            row["server_id"] = song["id"]
            row.setdefault("baseline_server_id", song["id"])
            self.store.put("track", row["id"], row)
            self.store.put("server-before", row["id"], song)
            mapped += 1
        self.store.put("server-snapshot", "latest", {"at": stamp(), "songs": songs})
        playlists = {}
        for p in self.request("getPlaylists").get("playlists", {}).get("playlist", []):
            detail = self.request("getPlaylist", {"id": p["id"]}).get("playlist", {})
            playlists[p["id"]] = {"name": p.get("name", ""), "ids": [s["id"] for s in detail.get("entry", [])]}
        self.store.put("server-state", "latest", {"at": stamp(), "songs": {s["id"]: {"starred": bool(s.get("starred")), "play_count": s.get("playCount", 0)} for s in songs}, "playlists": playlists})
        return {"count": len(songs), "mapped": mapped, "unmapped": sum(not r.get("missing") and not r.get("server_id") for r in self.store.all("track"))}

    def physical_paths(self, songs):
        """Read actual paths from the explicitly mounted DB; API paths may be virtual.

        Only IDs visible to this connected API account, with matching size and
        duration in both sources, may be considered. Never join on song names.
        """
        configured = os.getenv("JIAYIN_NAVIDROME_DB", "")
        if not configured:
            return None
        if not Path(configured).is_file():
            raise ValueError("只读 Navidrome 数据库挂载不存在，无法核对实际路径")
        visible = {s["id"]: s for s in songs}
        result = {}
        try:
            with sqlite3.connect(Path(configured).resolve().as_uri() + "?mode=ro", uri=True, timeout=30) as db:
                for server_id, path, duration, size in db.execute("SELECT id,path,duration,size FROM media_file"):
                    song = visible.get(server_id)
                    if not song or song.get("size") != size or abs(song.get("duration", 0) - duration) > 3:
                        continue
                    # Only music-root-relative paths are accepted. Absolute paths
                    # require an explicit root mapping rather than prefix guessing.
                    path = str(path).replace("\\", "/")
                    if path.startswith("/") or any(p in {"", ".", ".."} or ":" in p for p in path.split("/")):
                        continue
                    result.setdefault(path, []).append(song)
        except sqlite3.Error:
            raise ValueError("无法读取 Navidrome 实际路径；请检查只读数据库挂载与版本") from None
        return result

    def backup_database(self, job_id):
        configured = os.getenv("JIAYIN_NAVIDROME_DB", "")
        if not configured or not Path(configured).is_file():
            raise ValueError("写入真实曲库前需配置只读 Navidrome 数据库挂载，以建立一致性备份")
        target = self.store.path / "database-backups" / (job_id + ".sqlite3")
        target.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(Path(configured).resolve().as_uri() + "?mode=ro", uri=True, timeout=30) as source:
            with sqlite3.connect(target) as destination:
                source.backup(destination)
                if destination.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                    raise ValueError("服务器数据库备份验证失败")
        os.chmod(target, 0o600)
        return target.name

    def bind(self, file_id, server_id):
        row = self.library.track(file_id)
        songs = self.catalog()
        song = next((s for s in songs if s["id"] == server_id), None)
        if not song or abs(song.get("duration", 0) - row["duration"]) > 3:
            raise ValueError("服务器曲目不存在或时长不符")
        if any(r.get("server_id") == server_id and r["id"] != file_id and not r.get("missing") for r in self.store.all("track")):
            raise ValueError("此服务器曲目已绑定其他文件")
        row["server_id"] = server_id
        if not any(c["file_id"] == file_id and c.get("effects") for c in self.store.all("change")):
            row["baseline_server_id"] = server_id
        self.store.put("track", row["id"], row)
        self.store.put("server-before", row["id"], song)
        return {"bound": True}

    def start_scan(self):
        return self.request("startScan").get("scanStatus", {})

    def verify(self, job_id=None):
        with self.library.operation:
            return self._verify(job_id)

    def _verify(self, job_id=None):
        status = self.request("getScanStatus").get("scanStatus", {})
        if status.get("scanning"):
            raise ValueError("Navidrome 仍在扫描，请稍后核对")
        songs = self.catalog()
        by_id = {s["id"]: s for s in songs}
        results = []
        job = self.store.get("job", job_id) if job_id else None
        if job and job["status"] not in {"awaiting_scan", "verified"}:
            raise ValueError("请先完成文件任务，再进行服务器核对")
        job_changes = [self.store.get("change", i) for i in job["ids"]] if job else []
        metadata_job = bool(job_changes) and all(c["kind"] in {"tags", "lyrics", "artwork"} for c in job_changes)
        before_job = self.store.get("server-before-job", job_id, {}) if job else {}
        baseline = self.store.get("server-state-job", job_id) if job_id else None
        baseline = baseline or self.store.get("server-state", "latest", {})
        playlist_ok = True
        for pid, previous in baseline.get("playlists", {}).items():
            current = self.request("getPlaylist", {"id": pid}).get("playlist", {})
            if previous["ids"] != [s["id"] for s in current.get("entry", [])]:
                playlist_ok = False
        ids = {self.store.get("change", i)["file_id"] for i in job["ids"]} if job else None
        for row in self.store.all("track"):
            if row.get("missing") or (ids is not None and row["id"] not in ids):
                continue
            song = by_id.get(row.get("server_id"))
            m = row["metadata"]
            ok = bool(song and song["id"] == row.get("baseline_server_id", song["id"]) and song.get("title", "") == m["title"] and song.get("album", "") == m["album"] and abs(song.get("duration", 0) - row["duration"]) <= 3)
            if ok and (song.get("artists") or len(m["artists"]) > 1):
                ok = {a["name"] for a in song.get("artists", [])} == set(m["artists"])
            if ok and (song.get("genres") or len(m["genres"]) > 1):
                ok = {g["name"] for g in song.get("genres", [])} == set(m["genres"])
            if ok and song.get("albumArtists"):
                ok = {a["name"] for a in song["albumArtists"]} == set(m["album_artists"])
            metadata_ok = ok
            lyric_only = job_changes and all(c["kind"] in {"lyrics", "artwork"} for c in job_changes if c["file_id"] == row["id"])
            if lyric_only:
                # A lyric encoding conversion must preserve the original server
                # tags. Existing tag discrepancies (e.g. inferred album artist)
                # are independent and must not masquerade as a lyric failure.
                previous_song = before_job.get(row["id"]) or self.store.get("server-before", row["id"])
                fields = ("id", "title", "album", "artist", "duration", "artists", "albumArtists", "genres", "genre")
                ok = bool(song and previous_song and all(song.get(k) == previous_song.get(k) for k in fields))
            user_state_changed = False
            if ok:
                previous = baseline.get("songs", {}).get(song["id"])
                user_state_changed = not (playlist_ok and (previous is None or previous["starred"] == bool(song.get("starred")) and song.get("playCount", 0) >= previous["play_count"]))
                # Tag/lyric/artwork jobs never call user-state write APIs. Once the
                # same song identity and approved fields are verified, concurrent
                # listening, favorites or playlist edits must remain the user's
                # current choices, rather than block publishing corrected tags.
                if not metadata_job:
                    ok = not user_state_changed
            row["scan_verified"] = bool(ok and metadata_ok)
            self.store.put("track", row["id"], row)
            detail = ("歌词任务：原服务器 ID 与标签保持一致" if lyric_only else "身份与字段已核对") if ok else "ID 或标签未对应，保留待核对；不会按同名自动迁移"
            if ok and user_state_changed:
                detail += "；收藏或歌单快照有变化，保留服务器当前状态"
            results.append({"file_id": row["id"], "ok": ok, "detail": detail})
            if ok:
                self.store.put("identity", row["id"], {"file_id": row["id"], "server_id": song["id"], "hash": row["hash"], "at": stamp()})
                if job_id and (row.get("published_hash") != row["hash"] or row.get("published_job") != job_id):
                    revision = self.store.get("revision", "counter", 0) + 1
                    self.store.put("revision", "counter", revision)
                    row.update(published_revision=revision, published_hash=row["hash"], published_job=job_id,
                               published_cover=song.get("coverArt", ""), published_metadata=m if metadata_ok else {},
                               published_refs={k: song.get(k) for k in ("albumId", "artistId", "artists", "albumArtists") if song.get(k) is not None})
                    self.store.put("track", row["id"], row)
        if job and results and all(r["ok"] for r in results):
            job["status"] = "verified"
            self.store.put("job", job_id, job)
        self.store.put("verification", "latest", {"at": stamp(), "results": results})
        return results

    def client_catalog(self):
        config = self.store.get("config", "navidrome", {})
        return {"server": config.get("client_server") or config.get("url", ""), "username": config.get("username", ""), "tracks": [
            {"id": r["server_id"], "region": r["metadata"]["region"], "language": r["metadata"]["language"], "albumVersion": r["metadata"]["album_version"]}
            for r in self.store.all("track") if r.get("scan_verified") and r.get("server_id") and not r.get("missing")]}
