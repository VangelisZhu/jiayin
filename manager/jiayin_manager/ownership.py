"""Reviewed browsing ownership. Never writes tags, files or the playback queue."""
import hashlib
import json

from fastapi import APIRouter, HTTPException, Request
from pydantic import Field, field_validator

from .library import uid
from .models import StrictModel
from .store import stamp


class Target(StrictModel):
    id: str = Field(default="", max_length=200)
    name: str = Field(min_length=1, max_length=200)

    @field_validator("name")
    @classmethod
    def clean(cls, value):
        value = value.strip()
        if not value or any(ord(c) < 32 for c in value):
            raise ValueError("请填写有效歌手名称")
        return value


class Preview(StrictModel):
    request_id: str = Field(min_length=8, max_length=100, pattern=r"^[A-Za-z0-9-]+$")
    ids: list[str] = Field(min_length=1, max_length=1000)
    target: Target
    note: str = Field(default="", max_length=1000)


class Review(StrictModel):
    accept: bool
    track_ids: list[str] = Field(default_factory=list, max_length=1000)
    reason: str = Field(default="", max_length=1000)


def install_ownership(app, store, library, service):
    router = APIRouter()
    if not store.get("ownership-meta", "identity"):
        store.put("ownership-meta", "identity", uid())

    def put(db, kind, key, value):
        db.execute("INSERT OR REPLACE INTO documents VALUES(?,?,?)", (kind, key, json.dumps(value, ensure_ascii=False)))

    def current(sid):
        return store.get("ownership", sid, {"track_id": sid, "target": None, "sequence": 0})

    def public(item):
        return {**{k: v for k, v in item.items() if k not in {"member_id", "request_id", "payload_hash"}},
                "entries": [{k: v for k, v in e.items() if k not in {"file_id", "binding"}} for e in item["entries"]]}

    def summaries(items, offset):
        if offset < 0:
            raise ValueError("分页位置无效")
        return [{**public(i), "entry_count": len(i["entries"]), "entries": public(i)["entries"][:3]}
                for i in items[::-1][offset:offset + 50]]

    def own(item_id, actor):
        item = store.get("ownership-request", item_id)
        if not item or item["member_id"] != actor["member"]["id"]:
            raise HTTPException(404, "申请不存在")
        return item

    def valid_target(target, actor):
        value = target.model_dump()
        if target.id:
            if len(target.id) > 200 or target.id.startswith(("name:", "group:")):
                raise ValueError("请重新选择已有歌手，或新建归属分组")
            auth = service.credentials(actor["device"])
            remote = service.remote(actor["member"]["username"], auth["token"], auth["salt"],
                                    "getArtist", {"id": target.id}).get("artist", {})
            if remote.get("id") != target.id or remote.get("name") != target.name:
                raise ValueError("目标歌手身份已变化，请重新选择")
        # A named group without a server artist ID is deliberately separate from a real artist.
        return value

    def assert_entry(e):
        row = service.row(e["track_id"], None)
        if row["id"] != e["file_id"] or row["hash"] != e["binding"]:
            raise ValueError("歌曲身份或资料已变化，请重新预览")
        if current(e["track_id"])["sequence"] != e["before_sequence"]:
            raise ValueError("共享归属已更新，请重新预览")

    def publish(db, sid, target, request_id, file_id):
        counter = store.get("ownership-meta", "counter", 0) + 1
        put(db, "ownership-meta", "counter", counter)
        record = {"track_id": sid, "target": target, "sequence": counter, "request_id": request_id,
                  "file_id": file_id}
        put(db, "ownership", sid, record)
        return counter

    @router.get("/api/member/ownership/capabilities")
    def capabilities(request: Request):
        service.actor(request)
        return {"version": 1, "library_id": store.get("ownership-meta", "identity"), "max_tracks": 1000}

    @router.post("/api/member/ownership/preview")
    def preview(body: Preview, request: Request):
        actor = service.actor(request)
        service.writable(actor)
        payload_hash = hashlib.sha256(body.model_dump_json().encode()).hexdigest()
        key = hashlib.sha256((actor["member"]["id"] + body.request_id).encode()).hexdigest()
        with service.lock:
            previous = store.get("ownership-request", key)
            if previous:
                if previous["payload_hash"] != payload_hash:
                    raise ValueError("请求标识已使用，请重新预览")
                return public(previous)
        target = valid_target(body.target, actor)
        rows = [service.row(sid, actor) for sid in dict.fromkeys(body.ids)]
        with library.operation, service.lock:
            # Re-check after network validation; a simultaneous library rebind must not pass.
            entries = []
            for row in rows:
                latest = service.row(row["server_id"], None)
                if latest["id"] != row["id"] or latest["hash"] != row["hash"]:
                    raise ValueError("歌曲资料已变化，请重新预览")
                before = current(row["server_id"])
                entries.append({"track_id": row["server_id"], "title": row["metadata"]["title"],
                                "credit": row["metadata"].get("artist_credit") or " / ".join(row["metadata"]["artists"]),
                                "original_artists": row["metadata"]["artists"], "before_target": before["target"],
                                "before_sequence": before["sequence"], "target": target,
                                "file_id": row["id"], "binding": row["hash"]})
            previous = store.get("ownership-request", key)
            if previous:
                if previous["payload_hash"] != payload_hash:
                    raise ValueError("请求标识已使用")
                return public(previous)
            item = {"id": key, "request_id": body.request_id, "payload_hash": payload_hash,
                    "member_id": actor["member"]["id"], "username": actor["member"]["username"],
                    "status": "draft", "entries": entries, "note": body.note, "at": stamp()}
            store.put("ownership-request", key, item)
            return public(item)

    @router.post("/api/member/ownership/{item_id}/submit")
    def submit(item_id: str, request: Request):
        actor = service.actor(request)
        service.writable(actor)
        with library.operation, service.lock:
            item = own(item_id, actor)
            if item["status"] != "draft":
                return public(item)
            for e in item["entries"]:
                assert_entry(e)
            item["status"] = "pending"  # Even direct writers require admin review here.
            store.put("ownership-request", item_id, item)
            return public(item)

    @router.get("/api/member/ownership/requests")
    def requests(request: Request, offset: int = 0):
        actor = service.actor(request)
        return summaries([i for i in store.all("ownership-request") if i["member_id"] == actor["member"]["id"]], offset)

    @router.get("/api/member/ownership/requests/{item_id}")
    def request_detail(item_id: str, request: Request):
        return public(own(item_id, service.actor(request)))

    @router.post("/api/member/ownership/{item_id}/withdraw")
    def withdraw(item_id: str, request: Request):
        actor = service.actor(request)
        with service.lock:
            item = own(item_id, actor)
            if item["status"] not in {"draft", "pending", "conflict"}:
                raise ValueError("此申请已审核，不能撤回")
            item["status"] = "withdrawn"
            store.put("ownership-request", item_id, item)
            return public(item)

    @router.get("/api/ownership/requests")
    def admin_requests(offset: int = 0):
        return summaries(store.all("ownership-request"), offset)

    @router.get("/api/ownership/requests/{item_id}")
    def admin_detail(item_id: str):
        item = store.get("ownership-request", item_id)
        if not item:
            raise HTTPException(404, "申请不存在")
        return public(item)

    @router.post("/api/ownership/{item_id}/review")
    def review(item_id: str, body: Review):
        with library.operation, service.lock:
            item = store.get("ownership-request", item_id)
            if not item or item["status"] != "pending":
                raise ValueError("申请已处理，请刷新")
            selected = set(body.track_ids)
            if body.accept and (not selected or not selected <= {e["track_id"] for e in item["entries"]}):
                raise ValueError("请选择有效歌曲")
            chosen = [e for e in item["entries"] if e["track_id"] in selected] if body.accept else []
            try:
                targets = {e["target"]["id"]: e["target"]["name"] for e in chosen if e["target"]["id"]}
                for aid, name in targets.items():
                    artist = service.nd.request("getArtist", {"id": aid}).get("artist", {})
                    if artist.get("id") != aid or artist.get("name") != name:
                        raise ValueError("目标歌手身份已变化，请重新预览")
                for e in chosen:
                    assert_entry(e)
            except ValueError as exc:
                item.update(status="conflict", reason=str(exc))
                store.put("ownership-request", item_id, item)
                raise
            with store.transaction() as db:
                for e in item["entries"]:
                    e["result"] = "published" if e in chosen else "rejected"
                    if e in chosen:
                        e["published_sequence"] = publish(db, e["track_id"], e["target"], item_id, e["file_id"])
                item.update(status=("published" if len(chosen) == len(item["entries"]) else "partial") if chosen else "rejected",
                            reason=body.reason, reviewed_at=stamp())
                put(db, "ownership-request", item_id, item)
            return public(item)

    @router.post("/api/ownership/{item_id}/undo")
    def undo(item_id: str):
        with library.operation, service.lock:
            item = store.get("ownership-request", item_id)
            if not item or item["status"] not in {"published", "partial"}:
                raise ValueError("此发布不能撤销")
            chosen = [e for e in item["entries"] if e.get("result") == "published"]
            for e in chosen:
                row = service.row(e["track_id"], None)
                if row["id"] != e["file_id"] or row["hash"] != e["binding"] or current(e["track_id"])["sequence"] != e["published_sequence"]:
                    raise ValueError("后续资料或归属已变化，请重新核对，不能直接回退")
            with store.transaction() as db:
                for e in chosen:
                    publish(db, e["track_id"], e["before_target"], item_id, e["file_id"])
                item.update(status="undone", undone_at=stamp())
                put(db, "ownership-request", item_id, item)
            return public(item)

    @router.get("/api/member/ownership/catalog")
    def catalog(request: Request, after: int = 0):
        actor = service.actor(request)
        with library.operation, service.lock:
            bindings = {}
            for row in store.all("track"):
                if row.get("server_id") and not row.get("missing"):
                    bindings.setdefault(row["server_id"], []).append(row["id"])
            stale = [r for r in store.all("ownership") if r["target"] is not None and bindings.get(r["track_id"]) != [r["file_id"]]]
            if stale:
                with store.transaction() as db:
                    for r in stale:
                        publish(db, r["track_id"], None, r["request_id"], r["file_id"])
            candidates = sorted([r for r in store.all("ownership") if r["sequence"] > max(0, after)], key=lambda r: r["sequence"])[:50]
            rows, cursor = [], after
            for r in candidates:
                cursor = r["sequence"]
                try:
                    row = service.row(r["track_id"], actor)
                    if r["target"] is None or row["id"] == r["file_id"]:
                        rows.append({k: v for k, v in r.items() if k != "file_id"})
                except HTTPException as exc:
                    if exc.status_code != 403:
                        raise
                except ValueError:
                    # Missing/rebound files are not reassigned by names. A future rebind needs review.
                    continue
            return {"library_id": store.get("ownership-meta", "identity"), "tracks": rows,
                    "cursor": cursor, "has_more": len(candidates) == 50}

    app.include_router(router)
