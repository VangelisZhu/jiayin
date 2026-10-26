import hashlib
import hmac
import io
import json
import os
import secrets
import threading
import time
import zipfile
import shutil
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlparse
from typing import Literal

import httpx
from fastapi import BackgroundTasks, FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from starlette.background import BackgroundTask
from pydantic import Field

from .files import decode_candidates, embedded_lyrics, safe_path, sha
from .library import LibraryManager, uid
from .models import AnalysisRequest, ApplyRequest, EditRequest, StrictModel
from .navidrome import Navidrome
from .providers import AiAdvisor, LyricsProvider, MetadataProvider, endpoint
from .store import Store, stamp


class Login(StrictModel):
    password: str = Field(min_length=1, max_length=256)


class AiConfig(StrictModel):
    endpoint: str = ""
    model: str = Field(default="", max_length=150)
    key: str | None = Field(default=None, max_length=2000)
    call_limit: int = Field(default=100, ge=1, le=100000)
    token_limit: int = Field(default=1000000, ge=1000, le=100000000)
    max_output_tokens: int = Field(default=1500, ge=256, le=8000)
    input_price: float | None = Field(default=None, ge=0)
    output_price: float | None = Field(default=None, ge=0)
    allow_lyrics: bool = False
    full_library_enabled: bool = False
    output_format: Literal["json_schema", "json_object"] = "json_schema"


class ServerConfig(StrictModel):
    url: str
    client_server: str = ""
    username: str = Field(min_length=1, max_length=200)
    password: str = Field(min_length=1, max_length=1000)


class Binding(StrictModel):
    file_id: str
    server_id: str


class LyricEdit(StrictModel):
    text: str = Field(max_length=1024 * 1024)
    source: str = Field(default="人工修正", max_length=100)


def create_app(root=None, data=None, password=None, transport=None):
    root = Path(root or os.environ["JIAYIN_MUSIC_ROOT"])
    store = Store(Path(data or os.environ["JIAYIN_DATA_ROOT"]))
    library = LibraryManager(root, store)
    for task in store.all("task"):
        if task["status"] == "running":
            task["status"] = "paused" if task["kind"] == "ai" else "failed"
            task["error"] = "服务已重启，请主动继续或重新体检"
            store.put("task", task["id"], task)
    if not store.get("auth", "admin"):
        password = password or os.getenv("JIAYIN_ADMIN_PASSWORD", "")
        password_file = os.getenv("JIAYIN_ADMIN_PASSWORD_FILE")
        if password_file:
            password = Path(password_file).read_text().strip()
        if len(password) < 12:
            raise ValueError("首次启动需设置至少 12 位管理员密码")
        salt = secrets.token_hex(32)
        value = hashlib.scrypt(password.encode(), salt=salt.encode(), n=16384, r=8, p=1).hex()
        store.put("auth", "admin", {"salt": salt, "hash": value})
    advisors = AiAdvisor(store, transport)
    metadata = MetadataProvider(store, transport)
    lyrics = LyricsProvider(store, transport)
    navidrome = Navidrome(store, library, transport)
    auth_attempts, auth_lock = {}, threading.Lock()
    ai_task_lock = threading.Lock()
    @asynccontextmanager
    async def lifespan(instance):
        service = instance.state.corrections
        worker = threading.Thread(target=service.scheduler, daemon=True, name="jiayin-corrections")
        worker.start()
        try:
            yield
        finally:
            service.stopped.set()
            worker.join(timeout=2)
    app = FastAPI(title="家音曲库管理", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.store, app.state.library, app.state.advisor, app.state.navidrome = store, library, advisors, navidrome

    @app.exception_handler(ValueError)
    async def bad_request(request, error):
        return JSONResponse({"detail": str(error)}, status_code=409)

    @app.exception_handler(httpx.HTTPError)
    async def network_error(request, error):
        return JSONResponse({"detail": "外部服务暂不可用，文件未修改，请稍后重试"}, status_code=502)

    @app.middleware("http")
    async def authentication(request, call_next):
        path = request.url.path
        if request.headers.get("content-length", "0").isdigit() and int(request.headers.get("content-length", "0")) > 2 * 1024 * 1024:
            return JSONResponse({"detail": "请求过大"}, status_code=413)
        if path.startswith("/api/"):
            if path.startswith("/api/member/"):
                if request.method != "GET" and request.headers.get("x-jiayin-request") != "1":
                    return JSONResponse({"detail": "缺少客户端请求标识"}, status_code=403)
                origin = request.headers.get("origin")
                if origin and origin not in {str(request.base_url).rstrip("/"), os.getenv("JIAYIN_PUBLIC_ORIGIN", "").rstrip("/")}:
                    return JSONResponse({"detail": "请求来源不匹配"}, status_code=403)
                # Every member route authenticates its device and checks live permissions.
            elif path == "/api/client/catalog":
                given = request.headers.get("authorization", "").removeprefix("Bearer ")
                digest = store.get("auth", "client-token")
                if not digest or not hmac.compare_digest(hashlib.sha256(given.encode()).hexdigest(), digest):
                    return JSONResponse({"detail": "只读连接码无效"}, status_code=401)
            else:
                if request.method != "GET":
                    origin = request.headers.get("origin")
                    configured_origin = os.getenv("JIAYIN_PUBLIC_ORIGIN", "").rstrip("/")
                    allowed = {str(request.base_url).rstrip("/")}
                    if configured_origin:
                        allowed.add(configured_origin)
                    if request.headers.get("x-jiayin-request") != "1" or (origin and origin not in allowed):
                        return JSONResponse({"detail": "请求来源不匹配"}, status_code=403)
                if path != "/api/login" and not store.authenticated(request.cookies.get("jiayin_session", "")):
                    return JSONResponse({"detail": "请登录管理后台"}, status_code=401)
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Cache-Control"] = "no-store" if path.startswith("/api") else "no-cache"
        response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        return response

    @app.post("/api/login")
    def login(body: Login, request: Request, response: Response):
        peer = request.client.host if request.client else "unknown"
        with auth_lock:
            attempts = [t for t in auth_attempts.get(peer, []) if time.monotonic() - t < 300]
            if len(attempts) >= 10:
                raise HTTPException(429, "尝试次数过多，请五分钟后重试")
            record = store.get("auth", "admin")
            actual = hashlib.scrypt(body.password.encode(), salt=record["salt"].encode(), n=16384, r=8, p=1).hex()
            if not hmac.compare_digest(actual, record["hash"]):
                auth_attempts[peer] = attempts + [time.monotonic()]
                raise HTTPException(401, "密码错误")
            auth_attempts.pop(peer, None)
        response.set_cookie("jiayin_session", store.session(), httponly=True, samesite="strict", secure=request.url.scheme == "https", max_age=43200)
        return {"ok": True}

    @app.post("/api/logout")
    def logout(request: Request, response: Response):
        with store.transaction() as db:
            db.execute("DELETE FROM sessions WHERE token=?", (request.cookies.get("jiayin_session", ""),))
        response.delete_cookie("jiayin_session")
        return {"ok": True}

    @app.get("/health")
    def health():
        return {"ok": True}

    @app.get("/api/summary")
    def summary(refresh: bool = False):
        return library.summary(refresh=refresh)

    def scan_task(task_id):
        try:
            def progress(count, errors):
                store.put("task", task_id, {"id": task_id, "kind": "scan", "status": "running",
                                           "progress": f"已读取 {count} 首，{errors} 个文件需要单独检查"})
            result = library.scan(progress)
            store.put("task", task_id, {"id": task_id, "kind": "scan", "status": "completed", "result": result})
        except Exception as exc:
            store.put("task", task_id, {"id": task_id, "kind": "scan", "status": "failed", "error": type(exc).__name__})

    @app.post("/api/scan")
    def scan(background: BackgroundTasks):
        if library.worker.locked() or any(t["status"] == "running" and t["kind"] == "scan" for t in store.all("task")):
            raise ValueError("文件任务进行中，请完成或暂停后再体检")
        task = {"id": uid(), "kind": "scan", "status": "running"}
        store.put("task", task["id"], task)
        background.add_task(scan_task, task["id"])
        return task

    @app.get("/api/tracks")
    def tracks(q: str = "", issue: str = "", offset: int = 0, limit: int = 50):
        matches = {f["file_id"] for f in library.findings() if not issue or f["kind"] == issue} if issue else None
        rows = [r for r in store.all("track") if not r.get("missing") and (matches is None or r["id"] in matches)]
        if q:
            rows = [r for r in rows if q.casefold() in json.dumps(r["metadata"], ensure_ascii=False).casefold() or q.casefold() in r["path"].casefold()]
        return {"total": len(rows), "ids": [r["id"] for r in rows], "items": rows[max(offset, 0):max(offset, 0) + min(max(limit, 1), 100)]}

    @app.get("/api/findings")
    def findings():
        return library.findings()

    @app.post("/api/tracks/selection")
    def selection(body: ApplyRequest):
        return [library.track(i) for i in dict.fromkeys(body.ids)]

    @app.get("/api/groups")
    def groups(kind: Literal["artists", "albums"], q: str = "", offset: int = 0):
        from collections import defaultdict
        grouped = {}
        for row in store.all("track"):
            if row.get("missing"):
                continue
            m = row["metadata"]
            if kind == "artists":
                labels = [(m["artist_ids"][i] if len(m["artist_ids"]) == len(m["artists"]) else name.casefold(), name) for i, name in enumerate(m["artists"])]
            else:
                key = m["release_id"] or json.dumps([m["album"], m["album_artist_ids"] or m["album_artists"], m["date"], m["album_version"]], ensure_ascii=False)
                labels = [(key, m["album"] or "未命名专辑")]
            for key, name in labels:
                if q and q.casefold() not in (name + " " + m["album_version"] + " " + " ".join(m["album_artists"])).casefold():
                    continue
                entry = grouped.setdefault(key, {"id": hashlib.sha256(key.encode()).hexdigest(), "name": name, "ids": [], "date": m["date"], "version": m["album_version"], "album_artists": m["album_artists"]})
                entry["ids"].append(row["id"])
        entries = sorted(grouped.values(), key=lambda g: g["name"].casefold())
        return {"total": len(entries), "items": entries[max(offset, 0):max(offset, 0) + 100]}

    @app.post("/api/changes/tags")
    def edit(body: EditRequest):
        with library.operation:
            return library.draft(body.ids, body.values, body.lock)

    @app.post("/api/changes/move")
    def move(body: ApplyRequest):
        with library.operation:
            return library.move_draft(body.ids)

    @app.get("/api/changes")
    def changes():
        # Journal backup paths/content are internal implementation details.
        return [{**{k: v for k, v in c.items() if k != "effects"}, "can_undo": bool(c.get("effects")) and c["status"] in {"applied", "queued"}} for c in store.all("change")]

    @app.post("/api/changes/{change_id}/reject")
    def reject(change_id: str):
        with library.operation:
            c = store.get("change", change_id)
            if not c or c["status"] not in {"draft", "queued"} or c.get("effects") or library.worker.locked():
                raise ValueError("草稿不存在或已执行")
            c["status"] = "rejected"
            return store.put("change", change_id, c)

    @app.post("/api/tracks/{file_id}/unlock")
    def unlock(file_id: str):
        row = library.track(file_id)
        row["locks"] = []
        store.put("track", file_id, row)
        return {"ok": True}

    @app.get("/api/tasks")
    def tasks():
        return [{k: v for k, v in task.items() if k != "error" or task["status"] == "failed"}
                for task in store.all("task")]

    @app.get("/api/jobs")
    def jobs():
        return store.all("job")

    @app.post("/api/jobs")
    def apply(body: ApplyRequest):
        with library.operation:
            if store.get("config", "navidrome"):
                navidrome.capture()
                snapshot_id = uid()
                navidrome.backup_database(snapshot_id)
            job = library.job(body.ids)
            if store.get("config", "navidrome"):
                store.put("server-state-job", job["id"], store.get("server-state", "latest"))
                store.put("server-before-job", job["id"], {store.get("change", i)["file_id"]:
                    store.get("server-before", store.get("change", i)["file_id"]) for i in job["ids"]})
                job["database_backup"] = snapshot_id + ".sqlite3"
                store.put("job", job["id"], job)
            return job

    @app.post("/api/jobs/undo")
    def undo(body: ApplyRequest):
        return library.job(body.ids, undo=True)

    @app.post("/api/jobs/{job_id}/resume")
    def resume(job_id: str, background: BackgroundTasks):
        if library.worker.locked():
            raise ValueError("其他任务运行中，请稍后继续")
        background.add_task(library.run, job_id)
        return {"ok": True}

    @app.post("/api/jobs/{job_id}/pause")
    def pause(job_id: str):
        return library.pause(job_id)

    @app.get("/api/lyrics/{file_id}")
    def lyric(file_id: str):
        row = library.track(file_id)
        audio = safe_path(library.root, row["path"])
        path = audio.with_suffix(".lrc")
        safe_path(library.root, path.relative_to(library.root).as_posix(), False)
        raw = path.read_bytes() if path.exists() else b""
        if len(raw) > 1024 * 1024:
            raise ValueError("歌词文件过大，请手动检查")
        embedded = embedded_lyrics(audio)
        return {"text": raw.decode("utf-8-sig", "replace") if raw else (embedded[0]["text"] if embedded else ""), "candidates": decode_candidates(raw), "embedded": embedded,
                "translations": [p.name for p in audio.parent.glob(audio.stem + ".*.lrc")], "hash": sha(path) if path.exists() else None}

    @app.post("/api/lyrics/{file_id}")
    def lyric_edit(file_id: str, body: LyricEdit):
        return library.lyric_draft(file_id, body.text, body.source)

    @app.post("/api/lyrics/{file_id}/search")
    def lyric_search(file_id: str):
        return lyrics.search(library.track(file_id))

    @app.get("/api/audio/{file_id}")
    def audio(file_id: str):
        row = library.track(file_id)
        return FileResponse(safe_path(library.root, row["path"]), media_type="audio/" + Path(row["path"]).suffix[1:])

    @app.get("/api/evidence/{file_id}")
    def evidence(file_id: str):
        return [e for e in store.all("evidence") if e["file_id"] == file_id]

    @app.post("/api/evidence/{file_id}/search")
    def evidence_search(file_id: str):
        return metadata.candidates(library.track(file_id))

    @app.get("/api/ai/config")
    def ai_config():
        settings = advisors.settings()
        usage = store.get("usage", "ai", {"calls": 0, "reserved_tokens": 0, "input_tokens": 0, "output_tokens": 0})
        estimated = None
        if settings["input_price"] is not None and settings["output_price"] is not None:
            estimated = (usage["input_tokens"] * settings["input_price"] + usage["output_tokens"] * settings["output_price"]) / 1000000
        return {**settings, "key_saved": bool(store.secret("ai-key")), "usage": usage, "estimated_cost": estimated,
                "sample": store.get("ai-sample", "latest")}

    @app.put("/api/ai/config")
    def ai_save(body: AiConfig):
        config = body.model_dump(exclude={"key"})
        if body.endpoint:
            endpoint(body.endpoint)
        if body.full_library_enabled and not store.get("ai-sample", "latest"):
            raise ValueError("请先完成样本分析并核对结果")
        store.put("config", "ai", config)
        if body.key is not None:
            store.set_secret("ai-key", body.key)
        return ai_config()

    @app.post("/api/ai/test")
    def ai_test():
        return advisors.analyze([], [], "连接测试：只返回空 suggestions 数组。")

    @app.post("/api/lyrics/{file_id}/analyze")
    def lyric_analyze(file_id: str):
        if not advisors.settings()["allow_lyrics"]:
            raise ValueError("请先在设置中允许发送歌词正文；此操作会发送当前原词及候选歌词")
        row = library.track(file_id)
        original = lyric(file_id)
        candidates = lyrics.search(row)
        evidence = [{"id": "lrclib:" + c["id"], "file_id": file_id, "values": {"lyrics": c["text"]}, "duration": c["duration"], "source": "LRCLIB"} for c in candidates]
        result = advisors.analyze([row], evidence, "比较原始歌词和候选，仅当版本明确且候选可读时建议采用该候选的完整 lyrics 字段，必须逐字保持候选及时间戳；不能补写或生成歌词。否则标记 conflict 或 insufficient。", lyric_text=original["text"])
        drafts = []
        with library.operation:
            current = lyric(file_id)
            if current["hash"] != original["hash"] or library.track(file_id)["hash"] != row["hash"]:
                raise ValueError("歌词或歌曲已变化，请重新分析")
            for suggestion in result["suggestions"]:
                if suggestion["status"] == "supported":
                    c = library.lyric_draft(file_id, suggestion["values"]["lyrics"], "AI 选择已有歌词")
                    c["evidence"] = suggestion["evidence"]
                    store.put("change", c["id"], c)
                    drafts.append(c)
        return {"advice": result, "drafts": drafts}

    def analyze_task(task_id, body):
        if not ai_task_lock.acquire(blocking=False):
            store.put("task", task_id, {"id": task_id, "kind": "ai", "status": "failed", "error": "其他分析任务运行中"})
            return
        started = time.monotonic()
        try:
            task = store.get("task", task_id)
            done = set(task.get("done", []))
            all_ids = list(dict.fromkeys(body.ids))
            draft_ids = task.get("draft_ids", [])
            suggestion_count = task.get("suggestions", 0)
            pending = [i for i in all_ids if i not in done]
            for offset in range(0, len(pending), 10):
                rows = [library.track(i) for i in pending[offset:offset + 10]]
                candidates = []
                for row in rows:
                    task = store.get("task", task_id)
                    if task.get("cancel"):
                        task["status"] = "paused"
                        store.put("task", task_id, task)
                        return
                    candidates.extend(metadata.candidates(row))
                if store.get("task", task_id).get("cancel"):
                    task = store.get("task", task_id); task["status"] = "paused"; store.put("task", task_id, task)
                    return
                result = advisors.analyze(rows, candidates, body.instruction)
                for suggestion in result["suggestions"]:
                    store.put("suggestion", uid(), {**suggestion, "task_id": task_id})
                    suggestion_count += 1
                    if suggestion["status"] != "supported":
                        continue
                    with library.operation:
                        before = next(r for r in rows if r["id"] == suggestion["file_id"])
                        current = library.track(before["id"])
                        if current["hash"] != before["hash"]:
                            raise ValueError("分析过程中标签已变化，请重新分析")
                        # Cached/retried responses must not duplicate drafts after interruption.
                        existing = [c for c in store.all("change") if c.get("analysis_task") == task_id and c["file_id"] == suggestion["file_id"] and c.get("analysis_values") == suggestion["values"]]
                        if not existing:
                            for change in library.draft([suggestion["file_id"]], suggestion["values"], True, "ai", suggestion["evidence"]):
                                change["analysis_task"] = task_id
                                change["analysis_values"] = suggestion["values"]
                                store.put("change", change["id"], change)
                                draft_ids.append(change["id"])
                done.update(r["id"] for r in rows)
                task = store.get("task", task_id)
                task.update(done=sorted(done), draft_ids=draft_ids, suggestions=suggestion_count,
                            progress=f"已分析 {len(done)} / {len(all_ids)} 首")
                store.put("task", task_id, task)
            sample = {"count": len(done), "suggestions": suggestion_count, "drafts": len(draft_ids), "seconds": round(time.monotonic() - started, 2), "at": stamp(), "task_id": task_id}
            if len(done) >= min(50, sum(not r.get("missing") for r in store.all("track"))):
                store.put("ai-sample", "latest", sample)
            task = store.get("task", task_id); task.update(status="completed", result=sample)
            task.pop("error", None)
            store.put("task", task_id, task)
        except Exception as exc:
            task = store.get("task", task_id, {"id": task_id, "kind": "ai"})
            task.update(status="failed", error=str(exc) if isinstance(exc, ValueError) else type(exc).__name__)
            store.put("task", task_id, task)
        finally:
            ai_task_lock.release()

    @app.post("/api/ai/analyze")
    def analyze(body: AnalysisRequest, background: BackgroundTasks):
        if ai_task_lock.locked():
            raise ValueError("已有 AI 分析运行")
        if len(body.ids) > 100 and not advisors.settings()["full_library_enabled"]:
            raise ValueError("请先完成 50–100 首样本分析，检查结果后在设置中开启全库分批分析")
        task = {"id": uid(), "kind": "ai", "status": "running", "request": body.model_dump()}
        store.put("task", task["id"], task)
        background.add_task(analyze_task, task["id"], body)
        return task

    @app.post("/api/tasks/{task_id}/resume")
    def ai_resume(task_id: str, background: BackgroundTasks):
        task = store.get("task", task_id)
        if not task or task["kind"] != "ai" or task["status"] not in {"paused", "failed"} or ai_task_lock.locked():
            raise ValueError("此分析任务不可继续")
        body = AnalysisRequest.model_validate(task["request"])
        task.update(status="running", cancel=False)
        task.pop("error", None)
        store.put("task", task_id, task)
        background.add_task(analyze_task, task_id, body)
        return {"ok": True}

    @app.post("/api/tasks/{task_id}/pause")
    def task_pause(task_id: str):
        task = store.get("task", task_id)
        if not task or task["kind"] != "ai" or task["status"] != "running":
            raise ValueError("此任务不可暂停")
        task["cancel"] = True
        store.put("task", task_id, task)
        return {"ok": True}

    @app.get("/api/suggestions")
    def suggestions():
        return store.all("suggestion")

    @app.post("/api/navidrome/connect")
    def server_connect(body: ServerConfig):
        return navidrome.connect(body.url, body.username, body.password, body.client_server)

    @app.get("/api/navidrome")
    def server_config():
        return {"config": store.get("config", "navidrome", {}), "verification": store.get("verification", "latest")}

    @app.get("/api/navidrome/catalog")
    def server_catalog():
        return navidrome.catalog()

    @app.post("/api/navidrome/capture")
    def capture():
        return navidrome.capture()

    @app.post("/api/navidrome/bind")
    def bind(body: Binding):
        return navidrome.bind(body.file_id, body.server_id)

    @app.post("/api/navidrome/scan")
    def server_scan():
        result = navidrome.start_scan()
        for item in store.all("correction"):
            if item["status"] == "awaiting_scan":
                item.update(scan_requested=True, verify_attempts=0, next_check=stamp() + 5)
                store.put("correction", item["id"], item)
        return result

    @app.post("/api/navidrome/verify")
    def verify(job_id: str | None = None):
        results = navidrome.verify(job_id)
        for item in store.all("correction"):
            if item.get("job_id") and (not job_id or item["job_id"] == job_id):
                job = store.get("job", item["job_id"], {})
                if job.get("status") == "verified":
                    item["status"] = "verified"
                    store.put("correction", item["id"], item)
            if job_id:
                verified = store.get("job", job_id, {})
                if verified.get("undo") and verified.get("status") == "verified" and set(item["ids"]) & set(verified["ids"]):
                    item["status"] = "undone"
                    store.put("correction", item["id"], item)
        return results

    @app.post("/api/client/token")
    def client_token():
        token = secrets.token_urlsafe(32)
        store.put("auth", "client-token", hashlib.sha256(token.encode()).hexdigest())
        return {"token": token, "note": "仅此次显示；重新生成会撤销旧连接码"}

    @app.get("/api/client/catalog")
    def client_catalog():
        return navidrome.client_catalog()

    @app.get("/api/backup")
    def backup():
        with library.operation:
            if library.worker.locked():
                raise ValueError("请先暂停文件任务再导出备份")
            exports = store.path / "exports"
            exports.mkdir(exist_ok=True)
            folder = store.path / "backups"
            total = sum(p.stat().st_size for p in folder.rglob("*") if p.is_file()) if folder.exists() else 0
            if shutil.disk_usage(exports).free < total + 16 * 1024 * 1024:
                raise ValueError("导出空间不足；可停止服务后备份整个私有数据目录")
            export_path = exports / (uid() + ".zip")
            with zipfile.ZipFile(export_path, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
                # Deliberate allowlist: never export vault, secrets, auth, session DB or raw config.
                for kind in ("track", "change", "job", "identity", "server-before", "verification"):
                    archive.writestr(kind + ".json", json.dumps(store.all(kind), ensure_ascii=False))
                archive.writestr("correction.json", json.dumps([{k: v for k, v in c.items() if k != "device_digest"} for c in store.all("correction")], ensure_ascii=False))
                for cover in (store.path / "artwork").glob("*.jpg"):
                    if __import__("re").fullmatch(r"[0-9a-f]{64}\.jpg", cover.name) and cover.is_file() and not cover.is_symlink():
                        archive.write(cover, "artwork/" + cover.name)
                if folder.exists():
                    for path in folder.rglob("*"):
                        if path.is_file() and not path.is_symlink():
                            archive.write(path, "backups/" + path.relative_to(folder).as_posix())
            return FileResponse(export_path, media_type="application/zip", filename="jiayin-recovery.zip", background=BackgroundTask(export_path.unlink, missing_ok=True))

    from .corrections import install_corrections
    install_corrections(app, store, library, navidrome, transport)

    static = Path(__file__).parent / "static"
    app.mount("/static", StaticFiles(directory=static), name="static")

    @app.get("/")
    def index():
        return FileResponse(static / "index.html")

    return app
