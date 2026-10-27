"""Member-scoped correction workflow; all public client inputs are untrusted."""
import hashlib
import secrets
import threading
from typing import Literal

import httpx
from fastapi import APIRouter, BackgroundTasks, HTTPException, Request
from fastapi.responses import Response
from pydantic import Field

from .files import safe_path, sha
from .library import uid
from .models import StrictModel, checked_values
from .music_sources import MusicSources
from .store import stamp


class Pair(StrictModel):
    username: str = Field(min_length=1, max_length=200)
    token: str = Field(pattern=r"^[a-fA-F0-9]{32}$")
    salt: str = Field(min_length=1, max_length=100)
    device: str = Field(min_length=1, max_length=100)


class MemberEdit(StrictModel):
    role: Literal["readonly", "request", "direct"]
    enabled: bool = True


class Search(StrictModel):
    query: str = Field(min_length=1, max_length=200)
    kind: Literal["song", "album", "artist"] = "song"
    source: Literal["all", "qq", "netease"] = "all"
    refresh: bool = False


class Preview(StrictModel):
    ids: list[str] = Field(min_length=1, max_length=100)
    values: dict = Field(default_factory=dict)
    candidate_id: str = ""
    lyrics: str | None = Field(default=None, max_length=1024 * 1024)
    translation: str | None = Field(default=None, max_length=1024 * 1024)
    cover: bool = False
    note: str = Field(default="", max_length=1000)


class Review(StrictModel):
    accept: bool
    change_ids: list[str] = Field(default_factory=list, max_length=300)
    reason: str = Field(default="", max_length=1000)
    fields: dict[str, list[str]] = Field(default_factory=dict, max_length=300)


class PlaybackLease(StrictModel):
    ids: list[str] = Field(default_factory=list, max_length=1)


class SourceConfig(StrictModel):
    enabled: list[Literal["qq", "netease"]] = Field(default_factory=lambda: ["qq", "netease"], max_length=2)


class CorrectionService:
    def __init__(self, app, store, library, navidrome, transport=None):
        self.app, self.store, self.lib, self.nd, self.transport = app, store, library, navidrome, transport
        self.sources = MusicSources(store, transport)
        self.lock = threading.RLock()
        self.execution = threading.Lock()
        self.active_item = None
        self.stopped = threading.Event()
        latest = max((r.get("published_revision", 0) for r in store.all("track")), default=0)
        if latest > store.get("revision", "counter", 0):
            store.put("revision", "counter", latest)
        self.attempts = {}
        library.correction_guard = self.guard
        # Never silently replay a write after restart.
        for item in store.all("correction"):
            if item["status"] in {"executing", "approved", "waiting_playback"}:
                item["status"] = "paused"
                store.put("correction", item["id"], item)

    def remote(self, username, token, salt, method, extra=None):
        config = self.store.get("config", "navidrome")
        if not config:
            raise ValueError("管理员尚未配置音乐服务器")
        form = {"u": username, "t": token, "s": salt, "v": "1.16.1", "c": "JiayinCorrections", "f": "json", **(extra or {})}
        try:
            with httpx.Client(timeout=12, transport=self.transport, trust_env=False, follow_redirects=False) as client:
                r = client.post(config["url"] + "/rest/" + method + ".view", data=form)
                if r.status_code != 200 or len(r.content) > 2 * 1024 * 1024:
                    raise ValueError("音乐服务器验证失败")
                body = r.json()["subsonic-response"]
                if body.get("status") != "ok":
                    raise HTTPException(403, "音乐账户无效或没有此歌曲的访问权限")
                return body
        except (httpx.HTTPError, KeyError, TypeError):
            raise HTTPException(503, "音乐服务器暂不可达，请稍后重试") from None

    def credentials(self, device):
        import json
        secret = self.store.secret("device-auth:" + device["id"])
        if not secret:
            raise HTTPException(401, "设备已撤销，请重新连接")
        return json.loads(secret)

    def actor(self, request, allow_pending=False):
        raw = request.headers.get("authorization", "").removeprefix("Bearer ")
        device = self.store.get("member-token", hashlib.sha256(raw.encode()).hexdigest()) if raw else None
        if not device or device.get("revoked") or device["expires"] < stamp():
            raise HTTPException(401, "整理连接已失效，请重新连接")
        member = self.store.get("member", device["member_id"])
        if not member or not member["enabled"]:
            raise HTTPException(403, "此整理账户已停用")
        if not allow_pending and not member["approved"]:
            raise HTTPException(403, "等待管理员批准加入")
        auth = self.credentials(device)
        self.remote(member["username"], auth["token"], auth["salt"], "ping")
        return {"member": member, "device": device}

    def writable(self, actor):
        if actor and actor["member"]["role"] == "readonly":
            raise HTTPException(403, "此账户只有只读权限")

    def row(self, server_id, actor):
        rows = [r for r in self.store.all("track") if r.get("server_id") == server_id and not r.get("missing")]
        if len(rows) != 1:
            raise ValueError("此歌曲尚未可靠绑定 NAS 文件，请由管理员核对")
        if actor:
            a = self.credentials(actor["device"])
            self.remote(actor["member"]["username"], a["token"], a["salt"], "getSong", {"id": server_id})
        return rows[0]

    def public(self, item):
        # No paths, file journal, tokens or raw identities leave the client boundary.
        value = {k: v for k, v in item.items() if k not in {"file_ids", "device_digest"}}
        job = self.store.get("job", item.get("job_id", ""))
        if job:
            results = job.get("results", {})
            value["progress"] = {"total": len(job["ids"]), "completed": sum(bool(r.get("ok")) for r in results.values()),
                                 "failed": sum(not r.get("ok") for r in results.values())}
            # A completed file job is authoritative even if its parent response was interrupted.
            if value["status"] == "executing" and self.active_item != item["id"] and job["status"] in {"failed", "paused", "awaiting_scan"}:
                # A newly-created job is initially paused before run() acquires the writer.
                if job["status"] != "paused" or results:
                    value["status"] = job["status"]
            if value["status"] == "failed":
                value["error"] = value.get("error") or self.job_error(job)
                value["stage"] = "执行失败，已停止本次修改"
        return value

    @staticmethod
    def job_error(job):
        errors = list(dict.fromkeys(r.get("error", "文件修改未完成") for r in job.get("results", {}).values() if not r.get("ok")))
        return "；".join(errors[:3])[:1000] or "文件任务未完成，请在任务与回退页面核对"

    def preview(self, body, actor):
        self.writable(actor)
        values = checked_values(body.values) if body.values else {}
        candidate = self.store.get("music-candidate", body.candidate_id) if body.candidate_id else None
        if body.candidate_id and not candidate:
            raise ValueError("候选不存在，请重新检索")
        rows = [self.row(i, actor) for i in dict.fromkeys(body.ids)]
        if len(rows) > 1 and "title" in values:
            raise ValueError("歌名只能逐首纠正，不能批量套用")
        if not values and body.lyrics is None and body.translation is None and not body.cover:
            raise ValueError("请先选择需要修改的内容")
        if (body.lyrics is not None or body.translation is not None) and len(rows) != 1:
            raise ValueError("歌词只能逐首预览，不能套用到整张专辑")
        if actor and any(set(values) & set(r["locks"]) for r in rows):
            raise ValueError("字段已被人工锁定，请向管理员申请解除")
        if candidate and candidate.get("kind") == "artist" and "artists" in values:
            if any(len(r["metadata"]["artists"]) > 1 for r in rows):
                raise ValueError("合唱歌曲请逐项编辑完整演唱者，不可用单个歌手替换")
        artwork = self.fetch_cover(candidate.get("cover_url", "")) if body.cover and candidate else None
        if body.cover and not artwork:
            raise ValueError("所选候选没有可用封面")
        changes, display = [], []
        with self.lib.operation:
            for row in rows:
                items = self.lib.draft([row["id"]], values, source="manual") if values else []
                if artwork:
                    if not items:
                        items = [self.lib.artwork_draft(row["id"], artwork)]
                    else:
                        items[0]["artwork"] = artwork
                        self.store.put("change", items[0]["id"], items[0])
                if body.lyrics is not None:
                    items.append(self.lib.lyric_draft(row["id"], body.lyrics, "资料纠正"))
                if body.translation is not None:
                    items.append(self.lib.lyric_draft(row["id"], body.translation, "资料纠正·译文", translation=True))
                for c in items:
                    c["source"] = candidate["source"] if candidate else "manual"
                    c["evidence"] = [body.candidate_id] if candidate else []
                    self.store.put("change", c["id"], c)
                    display.append({"id": c["id"], "server_id": row["server_id"], "title": row["metadata"]["title"], "kind": c["kind"],
                                    "before": c["before"], "values": c["values"], "cover": bool(c.get("artwork"))})
                changes.extend(items)
            if not changes:
                raise ValueError("所选资料与当前信息相同")
            item = {"id": uid(), "member_id": actor["member"]["id"] if actor else "admin", "username": actor["member"]["username"] if actor else "管理员",
                    "device_digest": actor["device"]["digest"] if actor else "", "ids": [c["id"] for c in changes],
                    "file_ids": [r["id"] for r in rows], "preview": display, "status": "draft", "note": body.note,
                    "source": {"id": candidate["source_id"], "provider": candidate["source"], "url": candidate["url"]} if candidate else None,
                    "at": stamp(), "version": 1}
            for c in changes:
                c["correction_id"] = item["id"]
                self.store.put("change", c["id"], c)
            self.store.put("correction", item["id"], item)
        return self.public(item)

    def fetch_cover(self, url):
        from urllib.parse import urlparse
        host = (urlparse(url).hostname or "").lower()
        if urlparse(url).scheme != "https" or not (host.endswith(".music.126.net") or host in {"y.gtimg.cn", "y.qq.com"}):
            raise ValueError("封面地址不是受支持的来源")
        from PIL import Image
        import io
        with httpx.Client(timeout=15, transport=self.transport, trust_env=False, follow_redirects=False) as client:
            with client.stream("GET", url) as r:
                r.raise_for_status()
                raw = bytearray()
                for chunk in r.iter_bytes():
                    raw.extend(chunk)
                    if len(raw) > 5 * 1024 * 1024:
                        raise ValueError("封面超过 5 MB")
        try:
            with Image.open(io.BytesIO(raw)) as image:
                if image.width * image.height > 25000000:
                    raise ValueError("封面尺寸过大")
                image.convert("RGB").save(output := io.BytesIO(), "JPEG", quality=92)
                data = output.getvalue()
        except (OSError, Image.DecompressionBombError):
            raise ValueError("封面不是有效图片") from None
        key = hashlib.sha256(data).hexdigest()
        assets = self.store.path / "artwork"
        assets.mkdir(exist_ok=True)
        (assets / (key + ".jpg")).write_bytes(data)
        return key

    def owned(self, item_id, actor):
        item = self.store.get("correction", item_id)
        if not item or (actor and item["member_id"] != actor["member"]["id"]):
            raise HTTPException(404, "修改记录不存在")
        return item

    def submit(self, item_id, actor, background):
        self.writable(actor)
        with self.lock, self.lib.operation:
            item = self.owned(item_id, actor)
            if item["status"] != "draft":
                return self.public(item)  # idempotent, including duplicate network requests
            for fid in item["file_ids"]:
                self.row(self.lib.track(fid)["server_id"], actor)
            direct = not actor or actor["member"]["role"] == "direct"
            item["status"] = "approved" if direct else "pending"
            item["submitted_at"] = stamp()
            item["approval"] = "direct" if actor and direct else "admin" if not actor else ""
            self.store.put("correction", item_id, item)
            if direct:
                background.add_task(self.execute, item_id)
            return self.public(item)

    def guard(self, change):
        item = self.store.get("correction", change.get("correction_id", ""))
        if not item:
            return
        if item["status"] != "executing":
            raise ValueError("修改尚未批准或已停止")
        if item.get("approval") == "admin":
            return
        device = self.store.get("member-token", item.get("device_digest", ""))
        member = self.store.get("member", item["member_id"])
        if not device or device.get("revoked") or device["expires"] < stamp() or not member or not member["enabled"] or not member["approved"] or member["role"] != "direct":
            raise ValueError("账户权限已变化，请管理员重新审核")
        if set(change["values"]) & set(self.lib.track(change["file_id"])["locks"]):
            # Locks created by earlier changes in this same request are permitted.
            own = {field for cid in item.get("accepted_ids", item["ids"]) for previous in [self.store.get("change", cid)] if previous and previous["file_id"] == change["file_id"] and previous["status"] == "applied" for field in previous["values"]}
            if (set(change["values"]) & set(self.lib.track(change["file_id"])["locks"])) - own:
                raise ValueError("字段在预览后被锁定，请管理员审核")
        self.row(self.lib.track(change["file_id"])["server_id"], {"member": member, "device": device})

    def execute(self, item_id):
        if not self.execution.acquire(blocking=False):
            return
        self.active_item = item_id
        try:
            self._execute(item_id)
        finally:
            self.active_item = None
            self.execution.release()

    def _execute(self, item_id):
        with self.lock:
            item = self.store.get("correction", item_id)
            if item["status"] not in {"approved", "waiting_playback", "paused", "failed"}:
                return
            affected = {self.lib.track(fid)["server_id"] for fid in item["file_ids"]}
            if any(lease["expires"] > stamp() and affected & set(lease["ids"]) for lease in self.store.all("playback-lease")):
                item["status"] = "waiting_playback"
                self.store.put("correction", item_id, item)
                return
            item["status"] = "executing"
            item["stage"] = "检查权限与文件版本"
            item.pop("error", None)
            item.pop("message", None)
            self.store.put("correction", item_id, item)
        try:
            with self.lib.operation:
                for cid in item.get("accepted_ids", item["ids"]):
                    self.guard(self.store.get("change", cid))
                if not item.get("job_id"):
                    item["stage"] = "记录服务器基线并建立备份"
                    self.store.put("correction", item_id, item)
                    self.nd.capture()
                    snapshot = uid()
                    self.nd.backup_database(snapshot)
                    job = self.lib.job(item.get("accepted_ids", item["ids"]))
                    job["database_backup"] = snapshot + ".sqlite3"
                    self.store.put("job", job["id"], job)
                    self.store.put("server-state-job", job["id"], self.store.get("server-state", "latest"))
                    self.store.put("server-before-job", job["id"], {fid: self.store.get("server-before", fid) for fid in item["file_ids"]})
                    item["job_id"] = job["id"]
                    self.store.put("correction", item_id, item)
            item["stage"] = "备份已完成，正在写入并校验音频"
            self.store.put("correction", item_id, item)
            self.lib.run(item["job_id"])
            job = self.store.get("job", item["job_id"])
            item["status"] = "awaiting_scan" if job["status"] == "awaiting_scan" else job["status"]
            item.pop("error", None)
            item.pop("message", None)
            if item["status"] == "failed":
                item["stage"] = "执行失败，已停止本次修改"
                item["error"] = self.job_error(job)
            if item["status"] == "awaiting_scan":
                item["stage"] = "文件写入完成，等待服务器扫描与核对"
                try:
                    self.nd.start_scan()
                    item["scan_requested"] = True
                    item["next_check"] = stamp() + 5
                except (ValueError, httpx.HTTPError):
                    item["message"] = "文件已写入，等待管理员启动扫描并核对"
        except Exception as exc:
            item["status"] = "failed"
            item["stage"] = "执行失败，已停止本次修改"
            item["error"] = str(exc) if isinstance(exc, ValueError) else "执行未完成，请管理员检查后重试"
        self.store.put("correction", item_id, item)

    def scheduler(self):
        while not self.stopped.wait(3):
            for device in self.store.all("member-token"):
                if device.get("revoked") or device["expires"] < stamp():
                    if self.store.get("secret", "device-auth:" + device["id"]):
                        self.store.delete("secret", "device-auth:" + device["id"])
            for item in self.store.all("correction"):
                if item["status"] in {"approved", "waiting_playback"}:
                    try:
                        self.execute(item["id"])
                    except Exception:
                        # A disappeared track must not kill scheduling for other requests.
                        item["status"], item["error"] = "failed", "目标曲目已变化，请重新核对"
                        self.store.put("correction", item["id"], item)
                elif item["status"] == "awaiting_scan" and item.get("scan_requested") and item.get("next_check", 0) <= stamp() and item.get("verify_attempts", 0) < 3:
                    try:
                        if self.nd.request("getScanStatus").get("scanStatus", {}).get("scanning"):
                            continue
                        result = self.nd.verify(item["job_id"])
                        if result and all(r["ok"] for r in result):
                            item["status"] = "verified"
                            item.pop("message", None)
                        else:
                            item["verify_attempts"] = item.get("verify_attempts", 0) + 1
                            item["message"] = "扫描后资料尚未完全对应，请管理员在任务页核对"
                    except Exception:
                        item["verify_attempts"] = item.get("verify_attempts", 0) + 1
                        item["message"] = "自动核对暂未完成，可在任务页手动核对"
                    item["next_check"] = stamp() + 10
                    self.store.put("correction", item["id"], item)


def install_corrections(app, store, library, navidrome, transport=None):
    service = CorrectionService(app, store, library, navidrome, transport)
    app.state.corrections = service
    router = APIRouter()

    @router.post("/api/member/pair")
    def pair(body: Pair, request: Request):
        peer = request.client.host if request.client else "unknown"
        with service.lock:
            attempts = [t for t in service.attempts.get(peer, []) if stamp() - t < 300]
            if len(attempts) >= 10:
                raise HTTPException(429, "连接尝试过多，请稍后重试")
            service.attempts[peer] = attempts + [stamp()]
        service.remote(body.username, body.token, body.salt, "ping")
        identity = service.remote(body.username, body.token, body.salt, "getUser", {"username": body.username}).get("user", {})
        username = identity.get("username")
        if not username:
            raise ValueError("服务器未提供可验证的账户身份")
        member_id = hashlib.sha256(username.encode()).hexdigest()
        member = store.get("member", member_id) or {"id": member_id, "username": username, "role": "request", "approved": False, "enabled": True}
        if not member["enabled"]:
            raise HTTPException(403, "此整理账户已停用")
        store.put("member", member_id, member)
        token = secrets.token_urlsafe(32)
        digest = hashlib.sha256(token.encode()).hexdigest()
        device = {"id": uid(), "digest": digest, "member_id": member_id, "name": body.device, "expires": stamp() + 30 * 86400, "revoked": False}
        store.put("member-token", digest, device)
        import json
        store.set_secret("device-auth:" + device["id"], json.dumps({"token": body.token, "salt": body.salt}))
        config = store.get("config", "navidrome", {})
        return {"token": token, "member": member, "server": config.get("client_server", config.get("url", "")), "api_version": 2}

    @router.get("/api/member/me")
    def me(request: Request):
        actor = service.actor(request, allow_pending=True)
        config = store.get("config", "navidrome", {})
        return {"member": actor["member"], "device_id": actor["device"]["id"], "server": config.get("client_server", config.get("url", "")), "api_version": 2}

    @router.post("/api/member/playback")
    def playback(body: PlaybackLease, request: Request):
        actor = service.actor(request)
        for sid in body.ids:
            service.row(sid, actor)
        store.put("playback-lease", actor["device"]["id"], {"ids": body.ids, "expires": stamp() + 45})
        return {"ok": True}

    @router.post("/api/member/logout")
    def logout(request: Request):
        actor = service.actor(request, allow_pending=True)
        device = actor["device"]
        device["revoked"] = True
        store.put("member-token", device["digest"], device)
        store.delete("secret", "device-auth:" + device["id"])
        return {"ok": True}

    def actor_for(request):
        return service.actor(request) if request.url.path.startswith("/api/member/") else None

    @router.post("/api/member/sources/search")
    @router.post("/api/sources/search")
    def search(body: Search, request: Request):
        service.writable(actor_for(request))
        enabled = store.get("config", "music-sources", {"enabled": ["qq", "netease"]})["enabled"]
        names = enabled if body.source == "all" else [body.source] if body.source in enabled else []
        if not body.query.strip():
            raise ValueError("请填写搜索词")
        result = {"items": [], "errors": []}
        for name in names:
            partial = service.sources.search(body.query.strip(), body.kind, name, body.refresh)
            result["items"].extend(partial["items"])
            result["errors"].extend(partial["errors"])
        if not names:
            result["errors"] = [{"source": body.source, "message": "管理员已停用此来源，仍可手动编辑"}]
        return result

    @router.get("/api/sources-config")
    def sources_config():
        return store.get("config", "music-sources", {"enabled": ["qq", "netease"]})

    @router.put("/api/sources-config")
    def save_sources(body: SourceConfig):
        return store.put("config", "music-sources", {"enabled": list(dict.fromkeys(body.enabled))})

    @router.get("/api/member/sources/{candidate_id}")
    @router.get("/api/sources/{candidate_id}")
    def detail(candidate_id: str, request: Request):
        service.writable(actor_for(request))
        candidate = store.get("music-candidate", candidate_id)
        if not candidate or candidate["source"] not in store.get("config", "music-sources", {"enabled": ["qq", "netease"]})["enabled"]:
            raise ValueError("此来源已停用或候选不存在")
        return service.sources.detail(candidate_id)

    @router.get("/api/sources/{candidate_id}/cover")
    def cover(candidate_id: str):
        candidate = store.get("music-candidate", candidate_id)
        if not candidate:
            raise HTTPException(404, "候选不存在")
        asset = service.fetch_cover(candidate.get("cover_url", ""))
        return Response((store.path / "artwork" / (asset + ".jpg")).read_bytes(), media_type="image/jpeg")

    @router.get("/api/member/track/{server_id}")
    @router.get("/api/correction-track/{server_id}")
    def track(server_id: str, request: Request):
        row = service.row(server_id, actor_for(request))
        return {"id": server_id, "metadata": row["metadata"], "duration": row["duration"], "locked": row["locks"], "revision": row["hash"]}

    @router.post("/api/member/corrections/preview")
    @router.post("/api/corrections/preview")
    def preview(body: Preview, request: Request):
        return service.preview(body, actor_for(request))

    @router.post("/api/member/corrections/{item_id}/submit")
    @router.post("/api/corrections/{item_id}/submit")
    def submit(item_id: str, request: Request, background: BackgroundTasks):
        return service.submit(item_id, actor_for(request), background)

    @router.get("/api/member/corrections")
    @router.get("/api/corrections")
    def items(request: Request, offset: int = 0, limit: int = 50):
        actor = actor_for(request)
        rows = [i for i in store.all("correction") if not actor or i["member_id"] == actor["member"]["id"]]
        if not actor:
            return [service.public(i) for i in rows]
        selected = [service.public(i) for i in list(reversed(rows))[max(0, offset):max(0, offset) + min(100, max(1, limit))]]
        return [{**{k: i[k] for k in ("id", "status", "stage", "error", "message", "review", "at") if k in i},
                 "preview": [{"title": p["title"], "kind": p["kind"]} for p in i["preview"][:5]]} for i in reversed(selected)]

    @router.get("/api/member/corrections/{item_id}")
    def item(item_id: str, request: Request):
        return service.public(service.owned(item_id, actor_for(request)))

    @router.post("/api/corrections/{item_id}/review")
    def review(item_id: str, body: Review, background: BackgroundTasks):
        with service.lock, library.operation:
            item = service.owned(item_id, None)
            if item["status"] not in {"pending", "failed", "paused"}:
                raise ValueError("该申请不在可审核状态")
            chosen = body.change_ids or item["ids"]
            if not set(chosen) <= set(item["ids"]):
                raise ValueError("审核项不属于此申请")
            narrowed = {}
            if body.accept:
                for cid, fields in body.fields.items():
                    c = store.get("change", cid)
                    if cid not in chosen or not c or c["status"] != "draft" or not set(fields) <= set(c["values"]) | ({"cover"} if c.get("artwork") else set()):
                        raise ValueError("审核字段不属于此草稿，或任务已有执行记录")
                    if not fields:
                        raise ValueError("至少批准一个字段，或取消选择此项目")
                    selected = set(fields)
                    if "artists" in selected:
                        selected |= {"artist_credit", "artist_ids"} & set(c["values"])
                    if "album_artists" in selected:
                        selected |= {"album_artist_ids"} & set(c["values"])
                    if selected & {"album", "album_artists", "album_version", "date"}:
                        selected |= {"release_id", "release_track_id"} & set(c["values"])
                    narrowed[cid] = selected
                for cid, selected in narrowed.items():
                    c = store.get("change", cid)
                    c["values"] = {k: v for k, v in c["values"].items() if k in selected}
                    c["before"] = {k: v for k, v in c["before"].items() if k in selected}
                    if "cover" not in selected:
                        c.pop("artwork", None)
                    store.put("change", cid, c)
            item["review"] = {"reason": body.reason, "at": stamp(), "accepted_ids": chosen if body.accept else [], "fields": {k: sorted(v) for k, v in narrowed.items()}}
            item["accepted_ids"] = chosen
            item["approval"] = "admin"
            item["status"] = "approved" if body.accept else "rejected"
            store.put("correction", item_id, item)
            for cid in item["ids"]:
                c = store.get("change", cid)
                if (not body.accept or cid not in chosen) and c["status"] == "draft":
                    c["status"] = "rejected"
                    store.put("change", cid, c)
            if body.accept:
                background.add_task(service.execute, item_id)
            return service.public(item)

    @router.get("/api/members")
    def members():
        return [{**m, "devices": [{k: d[k] for k in ("id", "name", "revoked", "expires")} for d in store.all("member-token") if d["member_id"] == m["id"]]} for m in store.all("member")]

    @router.post("/api/corrections/{item_id}/retry")
    def retry(item_id: str, background: BackgroundTasks):
        with service.lock:
            item = service.owned(item_id, None)
            if item["status"] not in {"failed", "paused"} or not item.get("job_id"):
                raise ValueError("此任务需先审核，或重新生成修改预览")
            item["approval"], item["status"] = "admin", "approved"
            store.put("correction", item_id, item)
            background.add_task(service.execute, item_id)
        return service.public(item)

    @router.put("/api/members/{member_id}")
    def update_member(member_id: str, body: MemberEdit):
        with library.operation:
            member = store.get("member", member_id)
            if not member:
                raise HTTPException(404, "成员不存在")
            member.update(body.model_dump(), approved=True)
            store.put("member", member_id, member)
            return member

    @router.post("/api/devices/{device_id}/revoke")
    def revoke(device_id: str):
        with library.operation:
            for device in store.all("member-token"):
                if device["id"] == device_id:
                    device["revoked"] = True
                    store.put("member-token", device["digest"], device)
                    store.delete("secret", "device-auth:" + device_id)
        return {"ok": True}

    @router.get("/api/member/catalog")
    def catalog(request: Request, after: int = 0):
        actor = service.actor(request)
        # Only return corrections to IDs visible to this account.
        changed = {fid for item in store.all("correction") if item["status"] in {"verified", "undone"} for fid in item["file_ids"]}
        candidates = sorted([library.track(fid) for fid in changed if library.track(fid).get("published_revision", 0) > max(0, after)], key=lambda r: r["published_revision"])[:50]
        rows, cursor = [], after
        for row in candidates:
            cursor = row["published_revision"]
            try:
                service.row(row["server_id"], actor)
                rows.append({"id": row["server_id"], "metadata": row.get("published_metadata", {}), "revision": row.get("published_hash", row["hash"]), "sequence": row["published_revision"], "cover_art": row.get("published_cover", ""), "refs": row.get("published_refs", {})})
            except HTTPException as exc:
                if exc.status_code != 403:
                    raise
        return {"tracks": rows, "cursor": cursor, "has_more": len(candidates) == 50}

    app.include_router(router)
    return service
