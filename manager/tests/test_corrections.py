"""Generated libraries and fake upstream services; no private credentials or music."""
import hashlib
import io
import json
import shutil
import subprocess

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from jiayin_manager.__main__ import generated_demo
from jiayin_manager.app import create_app
from jiayin_manager.files import audio_hash, sha
from jiayin_manager.music_sources import MusicSources


@pytest.fixture
def setup(tmp_path):
    music = tmp_path / "music"
    generated_demo(music)
    def upstream(request):
        from urllib.parse import parse_qs
        form = parse_qs(request.content.decode()) if request.method == "POST" else dict(request.url.params)
        if request.url.host == "music.test":
            name = form.get("u", [""])[0]
            allowed = form.get("t", [""])[0] == "a" * 32
            if request.url.path.endswith("getSong.view"):
                allowed &= form.get("id", [""])[0] == ("s1" if name == "alice" else "s2")
            return httpx.Response(200, json={"subsonic-response": {"status": "ok" if allowed else "failed", "user": {"username": name}}})
        return httpx.Response(503)
    app = create_app(music, tmp_path / "data", "generated-admin-password", httpx.MockTransport(upstream))
    app.state.library.scan()
    rows = sorted(app.state.store.all("track"), key=lambda r: r["path"])
    for i, r in enumerate(rows):
        r["server_id"] = "s" + str(i + 1)
        app.state.store.put("track", r["id"], r)
    app.state.store.put("config", "navidrome", {"url": "http://music.test", "client_server": "https://music.example", "username": "service"})
    admin = TestClient(app, headers={"X-Jiayin-Request": "1"})
    admin.post("/api/login", json={"password": "generated-admin-password"})
    return app, admin, music, rows


def pair(app, admin, username="alice", role="request", approve=True):
    client = TestClient(app, headers={"X-Jiayin-Request": "1"})
    response = client.post("/api/member/pair", json={"username": username, "token": "a" * 32, "salt": "generated", "device": "generated phone"})
    assert response.status_code == 200, response.text
    body = response.json()
    client.headers["Authorization"] = "Bearer " + body["token"]
    if approve:
        assert admin.put("/api/members/" + body["member"]["id"], json={"role": role}).status_code == 200
    return client, body["member"]["id"]


def test_pending_readonly_and_old_token_do_not_grant_writes(setup):
    app, admin, _, _ = setup
    client, member = pair(app, admin, approve=False)
    assert client.get("/api/member/me").json()["member"]["approved"] is False
    assert client.post("/api/member/sources/search", json={"query": "generated"}).status_code == 403
    admin.put("/api/members/" + member, json={"role": "readonly"})
    assert client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "new"}}).status_code == 403
    old = admin.post("/api/client/token").json()["token"]
    client.headers["Authorization"] = "Bearer " + old
    assert client.get("/api/member/me").status_code == 401
    assert client.post("/api/jobs", json={"ids": ["invented"]}).status_code == 401


def test_requests_are_owned_and_cannot_bypass_review(setup):
    app, admin, _, _ = setup
    a, _ = pair(app, admin)
    b, _ = pair(app, admin, "bob")
    assert a.get("/api/member/track/s2").status_code == 403
    r = a.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "Corrected"}})
    assert r.status_code == 200, r.text
    item = r.json()
    assert "path" not in json.dumps(item)
    url = "/api/member/corrections/" + item["id"] + "/submit"
    assert a.post(url).json()["status"] == "pending"
    assert a.post(url).json()["status"] == "pending"
    assert len(app.state.store.all("job")) == 0
    assert b.post(url).status_code == 404
    assert b.get("/api/member/corrections").json() == []
    assert a.post("/api/corrections/" + item["id"] + "/review", json={"accept": True}).status_code == 401
    assert a.post("/api/changes/move", json={"ids": []}).status_code == 401
    assert admin.post("/api/corrections/" + item["id"] + "/review", json={"accept": False, "reason": "wrong release"}).json()["status"] == "rejected"


def test_disabled_device_and_live_role_guard(setup):
    app, admin, _, rows = setup
    client, mid = pair(app, admin, role="direct")
    item = client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "Corrected"}}).json()
    record = app.state.store.get("correction", item["id"])
    record.update(status="executing", approval="direct")
    app.state.store.put("correction", item["id"], record)
    c = app.state.store.get("change", item["ids"][0])
    app.state.corrections.guard(c)
    admin.put("/api/members/" + mid, json={"role": "request"})
    with pytest.raises(ValueError, match="权限"):
        app.state.corrections.guard(c)
    device = client.get("/api/member/me").json()["device_id"]
    admin.post("/api/devices/" + device + "/revoke")
    assert client.get("/api/member/me").status_code == 401
    assert all("device-auth" not in str(s) for s in app.state.store.all("secret"))


def test_approved_tags_lyrics_translation_and_byte_exact_undo(setup):
    app, admin, root, rows = setup
    client, _ = pair(app, admin)
    row = rows[0]
    # Explicitly choose fields that are not locked; generated PCM is unchanged.
    path = root / row["path"]
    before, pcm = path.read_bytes(), audio_hash(path)
    item = client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "Corrected"}, "lyrics": "[00:00.00]Original", "translation": "[00:00.00]译文"}).json()
    client.post("/api/member/corrections/" + item["id"] + "/submit")
    # Test the real executor without a real Navidrome or database mount.
    app.state.navidrome.capture = lambda: {}
    app.state.navidrome.backup_database = lambda _: "generated"
    app.state.navidrome.start_scan = lambda: {}
    response = admin.post("/api/corrections/" + item["id"] + "/review", json={"accept": True})
    assert response.status_code == 200, response.text
    record = app.state.store.get("correction", item["id"])
    job = app.state.store.get("job", record["job_id"])
    assert record["status"] == "awaiting_scan", record
    assert all(v["ok"] for v in job["results"].values()), job
    assert path.with_suffix(".zh.lrc").read_text("utf-8").endswith("译文")
    assert audio_hash(path) == pcm
    # Undo sidecars first, then container; restores original bytes.
    undo = app.state.library.job(list(reversed(item["ids"])), undo=True)
    for cid in item["ids"]:
        c = app.state.store.get("change", cid)
        c.pop("correction_id", None)
        app.state.store.put("change", cid, c)
    app.state.library.run(undo["id"])
    assert path.read_bytes() == before


def test_preview_conflict_never_overwrites_external_change(setup):
    app, admin, root, rows = setup
    client, _ = pair(app, admin)
    item = client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "Corrected"}}).json()
    from jiayin_manager.files import write_metadata
    write_metadata(root / rows[0]["path"], {"title": "External edit"})
    client.post("/api/member/corrections/" + item["id"] + "/submit")
    app.state.navidrome.capture = lambda: {}
    app.state.navidrome.backup_database = lambda _: "generated"
    admin.post("/api/corrections/" + item["id"] + "/review", json={"accept": True})
    record = app.state.store.get("correction", item["id"])
    assert record["status"] == "failed"
    assert app.state.store.get("track", rows[0]["id"])["metadata"]["title"] != "Corrected"


@pytest.mark.parametrize("bad_stage", ["source", "temporary"])
def test_failed_audio_validation_is_reported_to_admin_and_member_without_replacing_source(setup, monkeypatch, bad_stage):
    app, admin, root, rows = setup
    client, _ = pair(app, admin)
    original = (root / rows[0]["path"]).read_bytes()
    item = client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "Corrected"}}).json()
    client.post("/api/member/corrections/" + item["id"] + "/submit")
    app.state.navidrome.capture = lambda: {}
    app.state.navidrome.backup_database = lambda _: "generated"
    def check_audio(path):
        if (path.name.startswith(".jiayin-")) == (bad_stage == "temporary"):
            raise ValueError("音频完整性验证失败")
        return audio_hash(path)
    monkeypatch.setattr("jiayin_manager.library.audio_hash", check_audio)
    assert admin.post("/api/corrections/" + item["id"] + "/review", json={"accept": True}).status_code == 200
    assert (root / rows[0]["path"]).read_bytes() == original
    for who, route in [(admin, "/api/corrections"), (client, "/api/member/corrections")]:
        result = who.get(route).json()[0]
        assert result["status"] == "failed"
        assert ("原音频" if bad_stage == "source" else "暂存音频") in result["error"]
        assert "file_ids" not in result and "device_digest" not in result
    assert not list(root.rglob(".jiayin-*"))


def test_parent_status_reads_terminal_job_failure_and_progress_without_writes(setup):
    app, admin, _, _ = setup
    client, _ = pair(app, admin)
    item = client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "Corrected"}}).json()
    record = app.state.store.get("correction", item["id"])
    record.update(status="executing", job_id="generated-job", stage="正在写入")
    app.state.store.put("correction", item["id"], record)
    app.state.store.put("job", "generated-job", {"id": "generated-job", "ids": item["ids"], "status": "failed", "results": {item["ids"][0]: {"ok": False, "error": "音频完整性验证失败"}}})
    result = admin.get("/api/corrections").json()[0]
    assert result["status"] == "failed" and "音频完整性" in result["error"]
    assert result["progress"] == {"total": 1, "completed": 0, "failed": 1}
    assert app.state.store.get("correction", item["id"]) == record
    assert client.get("/api/member/corrections").json()[0]["status"] == "failed"


def test_new_paused_job_does_not_falsely_report_parent_as_paused(setup):
    app, _, _, _ = setup
    app.state.store.put("job", "generated", {"id": "generated", "ids": ["c"], "status": "paused", "results": {}})
    value = app.state.corrections.public({"id": "parent", "status": "executing", "job_id": "generated"})
    assert value["status"] == "executing"


def test_retry_preflight_does_not_display_previous_failure_as_current_status(setup):
    app, _, _, _ = setup
    app.state.store.put("job", "generated", {"id": "generated", "ids": ["c"], "status": "failed", "results": {"c": {"ok": False, "error": "Previous attempt"}}})
    service = app.state.corrections
    service.active_item = "retry"
    assert service.public({"id": "retry", "status": "executing", "job_id": "generated"})["status"] == "executing"
    service.active_item = None
    assert service.public({"id": "retry", "status": "executing", "job_id": "generated"})["status"] == "failed"


def test_one_source_failure_other_succeeds_and_translation(setup):
    app, _, _, _ = setup
    def source(request):
        if request.url.host == "c.y.qq.com":
            return httpx.Response(503)
        if "search" in request.url.path:
            return httpx.Response(200, json={"code": 200, "result": {"songs": [{"id": 1, "name": "Generated", "artists": [{"name": "A"}, {"name": "B"}], "album": {"name": "Live"}, "duration": 120000}]}})
        if "lyric" in request.url.path:
            return httpx.Response(200, json={"code": 200, "lrc": {"lyric": "[00:00]日本語"}, "tlyric": {"lyric": "[00:00]中文"}})
        return httpx.Response(200, json={"code": 200, "songs": [{"name": "Generated", "artists": [{"name": "A"}, {"name": "B"}], "album": {"name": "Live"}, "duration": 120000}]})
    sources = MusicSources(app.state.store, httpx.MockTransport(source))
    result = sources.search("Generated")
    assert len(result["items"]) == 1 and len(result["errors"]) == 1
    detail = sources.detail(result["items"][0]["id"])
    assert detail["values"]["artists"] == ["A", "B"]
    assert detail["translation"] == "[00:00]中文"


def test_cover_ssrf_and_preserve_pcm(setup):
    app, _, root, rows = setup
    with pytest.raises(ValueError):
        app.state.corrections.fetch_cover("http://127.0.0.1/secrets")
    raw = io.BytesIO()
    Image.new("RGB", (8, 8), "green").save(raw, "JPEG")
    service = app.state.corrections
    service.transport = httpx.MockTransport(lambda _: httpx.Response(200, content=raw.getvalue()))
    asset = service.fetch_cover("https://y.gtimg.cn/generated.jpg")
    lib = app.state.library
    row = rows[0]
    path = root / row["path"]
    original = path.read_bytes()
    pcm = audio_hash(path)
    change = lib.artwork_draft(row["id"], asset)
    job = lib.job([change["id"]])
    lib.run(job["id"])
    assert lib.store.get("job", job["id"])["results"][change["id"]]["ok"]
    assert audio_hash(path) == pcm
    undo = lib.job([change["id"]], undo=True)
    lib.run(undo["id"])
    assert path.read_bytes() == original


def test_direct_submit_is_idempotent_and_waits_for_playback(setup):
    app, admin, root, rows = setup
    client, _ = pair(app, admin, role="direct")
    before = (root / rows[0]["path"]).read_bytes()
    item = client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "Generated correction"}}).json()
    assert client.post("/api/member/playback", json={"ids": ["s1"]}).status_code == 200
    url = "/api/member/corrections/" + item["id"] + "/submit"
    client.post(url)
    assert app.state.store.get("correction", item["id"])["status"] == "waiting_playback"
    assert (root / rows[0]["path"]).read_bytes() == before
    assert not app.state.store.all("job")
    client.post(url)
    client.post("/api/member/playback", json={"ids": []})
    app.state.navidrome.capture = lambda: {}
    app.state.navidrome.backup_database = lambda _: "generated"
    app.state.navidrome.start_scan = lambda: {}
    app.state.corrections.execute(item["id"])
    assert app.state.store.get("correction", item["id"])["status"] == "awaiting_scan"
    assert len(app.state.store.all("job")) == 1
    client.post(url)
    assert len(app.state.store.all("job")) == 1


def test_partial_review_does_not_apply_unselected_lyrics(setup):
    app, admin, root, rows = setup
    client, _ = pair(app, admin)
    path = (root / rows[0]["path"]).with_suffix(".lrc")
    original = path.read_bytes() if path.exists() else None
    item = client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "Corrected"}, "lyrics": "[00:00]Different"}).json()
    client.post("/api/member/corrections/" + item["id"] + "/submit")
    app.state.navidrome.capture = lambda: {}
    app.state.navidrome.backup_database = lambda _: "generated"
    app.state.navidrome.start_scan = lambda: {}
    chosen = [p["id"] for p in item["preview"] if p["kind"] == "tags"]
    admin.post("/api/corrections/" + item["id"] + "/review", json={"accept": True, "change_ids": chosen})
    assert (path.read_bytes() if path.exists() else None) == original
    assert app.state.store.get("change", next(p["id"] for p in item["preview"] if p["kind"] == "lyrics"))["status"] == "rejected"


def test_member_catalog_only_publishes_verified_visible_revisions(setup):
    app, admin, root, rows = setup
    client, _ = pair(app, admin)
    for i, row in enumerate(rows[:2]):
        row["published_revision"], row["published_cover"] = i + 1, "cover" + str(i)
        app.state.store.put("track", row["id"], row)
        app.state.store.put("correction", "generated" + str(i), {"id": str(i), "member_id": "admin", "file_ids": [row["id"]], "status": "verified"})
    response = client.get("/api/member/catalog").json()
    assert [r["id"] for r in response["tracks"]] == ["s1"]
    assert response["cursor"] == 2
    assert client.get("/api/member/catalog?after=2").json()["tracks"] == []
    assert "path" not in json.dumps(response)


def test_forged_roles_paths_and_changes_are_rejected(setup):
    app, admin, _, _ = setup
    client, _ = pair(app, admin)
    for extra in ({"role": "direct"}, {"path": "../outside.wav"}, {"username": "admin"}):
        assert client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "New"}, **extra}).status_code == 422
    assert client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"path": "../outside.wav"}}).status_code == 409
    assert client.post("/api/member/pair", json={"username": "alice", "token": "b" * 32, "salt": "generated", "device": "bad"}).status_code == 403


def test_review_can_accept_only_title_and_preserve_genres(setup):
    app, admin, _, rows = setup
    client, _ = pair(app, admin)
    genres = rows[0]["metadata"]["genres"]
    item = client.post("/api/member/corrections/preview", json={"ids": ["s1"], "values": {"title": "Corrected", "genres": ["Jazz"]}}).json()
    client.post("/api/member/corrections/" + item["id"] + "/submit")
    app.state.navidrome.capture = lambda: {}
    app.state.navidrome.backup_database = lambda _: "generated"
    app.state.navidrome.start_scan = lambda: {}
    cid = item["ids"][0]
    response = admin.post("/api/corrections/" + item["id"] + "/review", json={"accept": True, "fields": {cid: ["title"]}})
    assert response.status_code == 200, response.text
    updated = app.state.store.get("track", rows[0]["id"])
    assert updated["metadata"]["title"] == "Corrected"
    assert updated["metadata"]["genres"] == genres


@pytest.mark.parametrize("extension", ["mp3", "flac", "m4a", "ogg", "opus", "aiff"])
def test_embedded_cover_all_supported_containers_preserve_audio_and_rollback(setup, extension):
    if not shutil.which("ffmpeg"):
        pytest.skip("ffmpeg container coverage runs in Linux CI")
    app, _, root, rows = setup
    target = root / ("generated-cover." + extension)
    subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-i", str(root / rows[0]["path"]), "-map_metadata", "-1", str(target)], check=True)
    lib = app.state.library
    lib.scan()
    row = next(r for r in app.state.store.all("track") if r["path"] == target.name)
    original, pcm = target.read_bytes(), audio_hash(target)
    raw = io.BytesIO()
    Image.new("RGB", (8, 8), "blue").save(raw, "JPEG")
    service = app.state.corrections
    service.transport = httpx.MockTransport(lambda _: httpx.Response(200, content=raw.getvalue()))
    asset = service.fetch_cover("https://y.gtimg.cn/generated.jpg")
    change = lib.artwork_draft(row["id"], asset)
    job = lib.job([change["id"]])
    lib.run(job["id"])
    assert lib.store.get("job", job["id"])["results"][change["id"]]["ok"]
    assert audio_hash(target) == pcm
    undo = lib.job([change["id"]], undo=True)
    lib.run(undo["id"])
    assert target.read_bytes() == original
