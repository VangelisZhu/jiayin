import hashlib
import io
import json
import os
import shutil
import subprocess
import threading
import zipfile
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from mutagen.id3 import COMM, TXXX
from mutagen.wave import WAVE

from jiayin_manager.__main__ import generated_demo
from jiayin_manager.app import create_app
from jiayin_manager.files import audio_hash, decode_candidates, read_metadata, safe_path, sha, target_path, write_metadata
from jiayin_manager.library import LibraryManager
from jiayin_manager.models import Metadata, checked_values
from jiayin_manager.providers import AiAdvisor, MetadataProvider
from jiayin_manager.store import Store


@pytest.fixture
def env(tmp_path):
    root = tmp_path / "music"
    generated_demo(root)
    app = create_app(root, tmp_path / "data", "generated-test-password")
    client = TestClient(app)
    assert client.post("/api/login", json={"password": "generated-test-password"}, headers={"X-Jiayin-Request": "1"}).status_code == 200
    client.headers["X-Jiayin-Request"] = "1"
    app.state.library.scan()
    return app, client, root


def first(app):
    # Filesystem enumeration order differs between Windows and Linux.
    return next(row for row in app.state.store.all("track") if row["path"] == "待整理/01.wav")


def apply_one(app, values):
    lib = app.state.library
    row = first(app)
    c = lib.draft([row["id"]], values)[0]
    job = lib.job([c["id"]])
    lib.run(job["id"])
    return c, app.state.store.get("job", job["id"])


def test_auth_csrf_logout_and_client_separation(env):
    app, client, root = env
    anonymous = TestClient(app)
    assert anonymous.get("/api/tracks").status_code == 401
    assert anonymous.post("/api/login", json={"password": "generated-test-password"}).status_code == 403
    assert client.post("/api/scan", headers={"Origin": "https://other.invalid"}).status_code == 403
    token = client.post("/api/client/token").json()["token"]
    assert anonymous.get("/api/client/catalog", headers={"Authorization": "Bearer " + token}).status_code == 200
    assert anonymous.get("/api/tracks", headers={"Authorization": "Bearer " + token}).status_code == 401
    client.post("/api/client/token")
    assert anonymous.get("/api/client/catalog", headers={"Authorization": "Bearer " + token}).status_code == 401
    client.post("/api/logout")
    assert client.get("/api/summary").status_code == 401


def test_failed_login_throttle(env):
    app, client, root = env
    for _ in range(10):
        assert client.post("/api/login", json={"password": "wrong"}).status_code == 401
    assert client.post("/api/login", json={"password": "wrong"}).status_code == 429


def test_scan_and_five_issue_classes(env):
    app, client, root = env
    assert client.get("/api/summary").json()["tracks"] == 8
    kinds = {f["kind"] for f in app.state.library.findings()}
    assert {"encoding", "lyrics_encoding", "missing_genres", "collaboration", "missing_album"} <= kinds
    original = first(app)
    app.state.library.scan()
    assert first(app)["id"] == original["id"]


def test_corrupt_audio_does_not_abort_scan(env):
    app, client, root = env
    damaged = root / "broken.mp3"
    damaged.write_bytes(b"not an MPEG audio stream")
    for i in range(3):
        shutil.copy2(root / "待整理" / "01.wav", root / f"extra-{i}.wav")
    before = {p: sha(p) for p in root.rglob("*") if p.is_file()}
    progress = []
    result = app.state.library.scan(lambda count, errors: progress.append((count, errors)))
    assert result["count"] == 11
    assert result["errors"] == [{"path": "broken.mp3", "error": "HeaderNotFoundError"}]
    assert progress and all(c + e >= 10 for c, e in progress)
    assert {p: sha(p) for p in before} == before


@pytest.mark.parametrize("value", ["../outside.wav", "/outside.wav", "C:/outside.wav", "a/../../file", "a\\..\\outside.wav"])
def test_path_traversal_rejected(env, value):
    with pytest.raises(ValueError):
        safe_path(env[2], value, False)


def test_symlink_rejected(env, tmp_path):
    _, _, root = env
    try:
        (root / "linked").symlink_to(tmp_path, target_is_directory=True)
    except OSError:
        pytest.skip("OS does not grant symlink creation")
    with pytest.raises(ValueError):
        safe_path(root, "linked/file.wav", False)


def test_draft_does_not_modify_files(env):
    app, client, root = env
    row = first(app)
    old_hash = sha(root / row["path"])
    result = client.post("/api/changes/tags", json={"ids": [row["id"]], "values": {"genres": ["Jazz", "Soul"]}})
    assert result.status_code == 200
    assert sha(root / row["path"]) == old_hash
    assert result.json()[0]["before"]["genres"] == row["metadata"]["genres"]


def test_apply_preserves_audio_other_tags_and_complete_rollback(env):
    app, _, root = env
    row = first(app)
    path = root / row["path"]
    audio = WAVE(path)
    audio.tags.add(COMM(encoding=3, lang="eng", desc="notes", text=["Keep me"]))
    audio.tags.add(TXXX(encoding=3, desc="REPLAYGAIN_TRACK_GAIN", text=["-4 dB"]))
    audio.save()
    app.state.library.scan()
    before, pcm = sha(path), audio_hash(path)
    change, job = apply_one(app, {"title": "新歌名", "artists": ["A", "B", "C"], "album_artists": ["A"], "genres": ["Jazz", "Soul"]})
    assert job["status"] == "awaiting_scan"
    m, _ = read_metadata(path)
    assert m["artists"] == ["A", "B", "C"] and m["album_artists"] == ["A"]
    assert m["artist_credit"] == "A / B / C"
    assert audio_hash(path) == pcm
    assert WAVE(path).tags.getall("COMM")[0].text == ["Keep me"]
    assert str(WAVE(path).tags["TXXX:REPLAYGAIN_TRACK_GAIN"]) == "-4 dB"
    undo = app.state.library.job([change["id"]], undo=True)
    app.state.library.run(undo["id"])
    assert sha(path) == before


def test_external_change_blocks_apply_and_undo(env):
    app, client, root = env
    row = first(app)
    c = app.state.library.draft([row["id"]], {"title": "Draft"})[0]
    write_metadata(root / row["path"], {"title": "External"})
    job = app.state.library.job([c["id"]])
    app.state.library.run(job["id"])
    assert app.state.store.get("job", job["id"])["status"] == "failed"
    assert client.post("/api/changes/" + c["id"] + "/reject").status_code == 200
    app.state.library.scan()
    change, _ = apply_one(app, {"title": "Applied"})
    write_metadata(root / row["path"], {"title": "Later edit"})
    job = app.state.library.job([change["id"]], True)
    app.state.library.run(job["id"])
    assert app.state.store.get("job", job["id"])["status"] == "failed"
    assert read_metadata(root / row["path"])[0]["title"] == "Later edit"


def test_space_failure_does_not_write(env, monkeypatch):
    app, _, root = env
    before = sha(root / first(app)["path"])
    monkeypatch.setattr(shutil, "disk_usage", lambda p: type("Space", (), {"free": 0})())
    _, job = apply_one(app, {"title": "New"})
    assert job["status"] == "failed"
    assert sha(root / first(app)["path"]) == before


def test_restart_recovers_persisted_stage_without_reapplying(env, monkeypatch):
    app, _, root = env
    lib = app.state.library
    row = first(app)
    change = lib.draft([row["id"]], {"title": "Restart"})[0]
    original = lib._recover
    monkeypatch.setattr(lib, "_recover", lambda change: (_ for _ in ()).throw(OSError("simulated power loss")))
    job = lib.job([change["id"]])
    lib.run(job["id"])
    assert app.state.store.get("change", change["id"])["effects"]
    new = LibraryManager(root, app.state.store)
    new.run(job["id"])
    assert read_metadata(root / row["path"])[0]["title"] == "Restart"
    assert app.state.store.get("job", job["id"])["status"] == "awaiting_scan"


def test_pause_after_current_item_and_resume(env, monkeypatch):
    app, _, root = env
    lib, store = app.state.library, app.state.store
    ids = [r["id"] for r in store.all("track")[:2]]
    changes = lib.draft(ids, {"language": "eng"})
    job = lib.job([c["id"] for c in changes])
    original = lib._apply
    count = 0
    def apply(change):
        nonlocal count
        original(change)
        count += 1
        if count == 1:
            lib.pause(job["id"])
    monkeypatch.setattr(lib, "_apply", apply)
    lib.run(job["id"])
    assert store.get("job", job["id"])["status"] == "paused"
    assert len(store.get("job", job["id"])["results"]) == 1
    lib.run(job["id"])
    assert store.get("job", job["id"])["status"] == "awaiting_scan"


def test_move_requires_verification_and_preserves_sidecars_and_playlist(env):
    app, _, root = env
    lib, store = app.state.library, app.state.store
    row = first(app)
    with pytest.raises(ValueError):
        lib.move_draft([row["id"]])
    row["scan_verified"] = True
    store.put("track", row["id"], row)
    before = sha(root / row["path"])
    playlist_before = (root / "示例.m3u8").read_bytes()
    change = lib.move_draft([row["id"]])[0]
    job = lib.job([change["id"]])
    lib.run(job["id"])
    assert store.get("job", job["id"])["status"] == "awaiting_scan"
    target = root / change["destination"]
    assert target.is_file() and sha(target) == before
    assert target.with_suffix(".lrc").is_file()
    assert change["destination"] in (root / "示例.m3u8").read_text("utf-8")
    undo = lib.job([change["id"]], True)
    lib.run(undo["id"])
    assert (root / row["path"]).exists()
    assert (root / "示例.m3u8").read_bytes() == playlist_before


def test_move_refuses_collision(env):
    app, _, root = env
    row = first(app)
    row["scan_verified"] = True
    app.state.store.put("track", row["id"], row)
    target = root / target_path(row)
    target.parent.mkdir(parents=True)
    target.write_text("do not overwrite")
    with pytest.raises(ValueError):
        app.state.library.move_draft([row["id"]])
    assert target.read_text() == "do not overwrite"


def test_encoding_candidates_and_lyric_rollback(env):
    app, client, root = env
    row = next(r for r in app.state.store.all("track") if r["metadata"]["language"] == "jpn")
    original = (root / row["path"]).with_suffix(".lrc").read_bytes()
    candidates = decode_candidates(original)
    assert any(c["encoding"] == "cp932" and "夜の窓" in c["text"] for c in candidates)
    text = next(c["text"] for c in candidates if c["encoding"] == "cp932")
    change = app.state.library.lyric_draft(row["id"], text, "cp932")
    job = app.state.library.job([change["id"]]); app.state.library.run(job["id"])
    assert (root / row["path"]).with_suffix(".lrc").read_text("utf-8") == text
    assert (root / row["path"]).with_suffix(".zh.lrc").is_file()
    job = app.state.library.job([change["id"]], True); app.state.library.run(job["id"])
    assert (root / row["path"]).with_suffix(".lrc").read_bytes() == original


def test_backup_excludes_keys_and_contains_verified_originals(env):
    app, client, root = env
    app.state.store.set_secret("ai-key", "not-for-backup-123")
    change, _ = apply_one(app, {"title": "Changed"})
    archive = client.get("/api/backup")
    assert archive.status_code == 200
    with zipfile.ZipFile(io.BytesIO(archive.content)) as z:
        assert "track.json" in z.namelist()
        assert any(n.startswith("backups/") for n in z.namelist())
        assert not any("secret" in n or "vault" in n or "auth" in n for n in z.namelist())
        assert all(b"not-for-backup-123" not in z.read(n) for n in z.namelist())


def test_ai_secret_never_returned(env):
    app, client, root = env
    result = client.put("/api/ai/config", json={"endpoint": "https://model.example/v1", "model": "example", "key": "not-for-browser"})
    assert result.status_code == 200
    assert result.json()["key_saved"]
    assert "not-for-browser" not in result.text
    assert client.put("/api/ai/config", json={"endpoint": "http://model.example/v1"}).status_code == 409


def evidence(row):
    return {"file_id": row["id"], "id": "mb:real", "duration": row["duration"], "values": {"title": "Verified", "album": "Release"}, "source": "MusicBrainz"}


def advice(row, values=None, refs=None):
    return {"suggestions": [{"file_id": row["id"], "values": values or {"title": "Verified"}, "evidence": refs or ["mb:real"], "reason": "来源支持", "status": "supported"}]}


@pytest.mark.parametrize("case", ["fake_source", "invented_genre", "unknown_file", "bad_field", "locked", "duration", "performance", "injected_tool"])
def test_ai_rejects_unsupported_or_dangerous_suggestions(env, case):
    app, _, root = env
    row = first(app)
    source = evidence(row)
    result = advice(row)
    if case == "fake_source": result["suggestions"][0]["evidence"] = ["fake"]
    if case == "invented_genre": result["suggestions"][0]["values"] = {"genres": ["Invented"]}
    if case == "unknown_file": result["suggestions"][0]["file_id"] = "other"
    if case == "bad_field": result["suggestions"][0]["values"] = {"path": "../../secret"}
    if case == "locked": row["locks"] = ["title"]
    if case == "duration": source["duration"] = 9999
    if case == "performance": source["values"]["title"] = "Song Live"; result["suggestions"][0]["values"] = {"title": "Song Live"}
    if case == "injected_tool": result["tool_calls"] = [{"name": "delete"}]
    with pytest.raises(ValueError): AiAdvisor.validate(result, [row], [source])


def test_ai_valid_patch_is_draft_and_budget_is_reserved(env):
    app, _, root = env
    row = first(app)
    source = evidence(row)
    captured = []
    def fake(request):
        captured.append(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(advice(row))}}], "usage": {"prompt_tokens": 50, "completion_tokens": 30}})
    advisor = AiAdvisor(app.state.store, httpx.MockTransport(fake))
    app.state.store.put("config", "ai", {"endpoint": "https://ai.example/v1", "model": "sample", "call_limit": 1, "token_limit": 100000})
    app.state.store.set_secret("ai-key", "not-in-prompt")
    before = sha(root / row["path"])
    result = advisor.analyze([row], [source], "检查")
    assert result["suggestions"][0]["values"] == {"title": "Verified"}
    assert sha(root / row["path"]) == before
    assert row["path"].encode() not in captured[0] and b"not-in-prompt" not in captured[0]
    # Cached identical advice costs no additional call; distinct request exceeds call limit.
    advisor.analyze([row], [source], "检查")
    with pytest.raises(ValueError): advisor.analyze([row], [source], "再次检查")
    assert len(captured) == 1


@pytest.mark.parametrize("failure", [429, 503, "malformed", "timeout"])
def test_ai_failures_never_modify_files_or_restore_consumed_budget(env, failure):
    app, _, root = env
    row = first(app)
    app.state.store.put("config", "ai", {"endpoint": "https://ai.example/v1", "model": "sample", "call_limit": 1, "token_limit": 100000})
    app.state.store.set_secret("ai-key", "secret")
    def fake(request):
        if failure == "timeout": raise httpx.ReadTimeout("timeout")
        if failure == "malformed": return httpx.Response(200, json={"oops": "wrong"})
        return httpx.Response(failure, text="untrusted provider error secret")
    advisor = AiAdvisor(app.state.store, httpx.MockTransport(fake))
    before = sha(root / row["path"])
    with pytest.raises(ValueError): advisor.analyze([row], [evidence(row)], "检查")
    assert sha(root / row["path"]) == before
    assert app.state.store.get("usage", "ai")["calls"] == 1


def test_tag_edits_cannot_mix_with_moves(env):
    app, _, root = env
    row = first(app); row["scan_verified"] = True
    app.state.store.put("track", row["id"], row)
    move = app.state.library.move_draft([row["id"]])[0]
    tags = app.state.library.draft([row["id"]], {"title": "Other"})[0]
    with pytest.raises(ValueError): app.state.library.job([move["id"], tags["id"]])


def test_remote_ids_are_not_guessed_from_titles(env):
    app, _, root = env
    row = first(app)
    nav = app.state.navidrome
    nav.catalog = lambda: [{"id": "same-title", "title": row["metadata"]["title"], "path": "virtual/other.wav", "duration": row["duration"]}]
    nav.request = lambda method, params=None: {"playlists": {"playlist": []}}
    result = nav.capture()
    assert result["mapped"] == 0
    assert not first(app).get("server_id")


def test_remote_verification_blocks_stale_or_changed_ids(env):
    app, _, root = env
    row = first(app)
    row["server_id"] = "s1"; app.state.store.put("track", row["id"], row)
    nav = app.state.navidrome
    nav.request = lambda method, params=None: {"scanStatus": {"scanning": False}}
    nav.catalog = lambda: [{"id": "s1", "title": row["metadata"]["title"], "album": row["metadata"]["album"], "duration": row["duration"], "artists": [{"name": a} for a in row["metadata"]["artists"]], "genres": [{"name": g} for g in row["metadata"]["genres"]]}]
    assert next(r for r in nav.verify() if r["file_id"] == row["id"])["ok"]
    nav.catalog = lambda: [{"id": "new-id", "title": row["metadata"]["title"], "album": row["metadata"]["album"], "duration": row["duration"]}]
    assert not next(r for r in nav.verify() if r["file_id"] == row["id"])["ok"]


def test_shared_catalog_only_exports_verified_bound_ids(env):
    app, _, root = env
    row = first(app)
    row.update(server_id="s1", scan_verified=True)
    app.state.store.put("track", row["id"], row)
    result = app.state.navidrome.client_catalog()
    assert len(result["tracks"]) == 1
    assert result["tracks"][0]["id"] == "s1"
    assert "path" not in result["tracks"][0]


def test_html_uses_no_remote_dependencies_or_html_injection(env):
    _, client, root = env
    page = client.get("/")
    assert page.status_code == 200
    assert "frame-ancestors 'none'" in page.headers["Content-Security-Policy"]
    js = client.get("/static/app.js").text
    assert "innerHTML" not in js and "eval(" not in js


@pytest.mark.parametrize("suffix", [".mp3", ".flac", ".m4a", ".ogg", ".opus", ".aiff"])
def test_real_format_roundtrip_preserves_decoded_audio(env, suffix, tmp_path, monkeypatch):
    app, _, root = env
    ffmpeg = os.getenv("JIAYIN_FFMPEG") or shutil.which("ffmpeg")
    if not ffmpeg:
        pytest.skip("ffmpeg is required for compressed-format integration tests")
    monkeypatch.setenv("JIAYIN_FFMPEG", ffmpeg)
    path = tmp_path / ("generated" + suffix)
    subprocess.run([ffmpeg, "-v", "error", "-nostdin", "-i", str(root / first(app)["path"]), "-map_metadata", "-1", str(path)], check=True, capture_output=True)
    before = audio_hash(path)
    write_metadata(path, {"title": "夜の窓 · 봄", "artists": ["A", "B", "C"], "album_artists": ["A", "B"],
                          "genres": ["Pop", "Jazz"], "album": "Generated album", "date": "2024-10-24", "disc": "1", "track": "2/8", "language": "jpn", "region": "日本", "compilation": True})
    metadata, _ = read_metadata(path)
    assert metadata["artists"] == ["A", "B", "C"]
    assert metadata["album_artists"] == ["A", "B"]
    assert metadata["genres"] == ["Pop", "Jazz"]
    assert metadata["title"] == "夜の窓 · 봄"
    assert metadata["region"] == "日本"
    assert metadata["compilation"]
    assert audio_hash(path) == before


def test_partial_move_can_be_rolled_back(env, monkeypatch):
    app, _, root = env
    lib, store = app.state.library, app.state.store
    row = first(app); row["scan_verified"] = True
    store.put("track", row["id"], row)
    change = lib.move_draft([row["id"]])[0]
    original_put = store.put
    def put(kind, key, value):
        result = original_put(kind, key, value)
        if kind == "change" and key == change["id"] and value.get("effects") and value["effects"][0].get("done"):
            raise OSError("interrupt after first moved file")
        return result
    monkeypatch.setattr(store, "put", put)
    job = lib.job([change["id"]]); lib.run(job["id"])
    monkeypatch.setattr(store, "put", original_put)
    assert (root / change["destination"]).exists()
    undo = lib.job([change["id"]], True); lib.run(undo["id"])
    assert (root / row["path"]).exists()
    assert app.state.store.get("job", undo["id"])["status"] == "awaiting_scan"


def test_unrelated_queued_file_task_is_not_overwritten(env):
    app, _, _ = env
    row = first(app)
    one = app.state.library.draft([row["id"]], {"title": "First"})[0]
    two = app.state.library.draft([row["id"]], {"title": "Second"})[0]
    app.state.library.job([one["id"]])
    with pytest.raises(ValueError): app.state.library.job([two["id"]])


def test_ai_lyrics_requires_opt_in_and_cannot_invent_lines(env):
    app, client, _ = env
    row = first(app)
    assert client.post("/api/lyrics/" + row["id"] + "/analyze").status_code == 409
    source = {"file_id": row["id"], "id": "lrclib:1", "duration": row["duration"], "values": {"lyrics": "[00:00.00]Existing lyric"}, "source": "LRCLIB"}
    valid = advice(row, {"lyrics": "[00:00.00]Existing lyric"}, ["lrclib:1"])
    assert AiAdvisor.validate(valid, [row], [source], lyrics=True)
    invented = advice(row, {"lyrics": "[00:00.00]Invented lyric"}, ["lrclib:1"])
    with pytest.raises(ValueError): AiAdvisor.validate(invented, [row], [source], lyrics=True)


def test_database_snapshot_is_consistent_and_not_in_public_export(env, tmp_path, monkeypatch):
    import sqlite3
    app, client, _ = env
    source = tmp_path / "navidrome.db"
    with sqlite3.connect(source) as db:
        db.execute("CREATE TABLE example(value TEXT)")
        db.execute("INSERT INTO example VALUES('private-credential-example')")
    monkeypatch.setenv("JIAYIN_NAVIDROME_DB", str(source))
    filename = app.state.navidrome.backup_database("generated-test")
    with sqlite3.connect(app.state.store.path / "database-backups" / filename) as db:
        assert db.execute("SELECT value FROM example").fetchone()[0] == "private-credential-example"
    with zipfile.ZipFile(io.BytesIO(client.get("/api/backup").content)) as archive:
        assert not any("database" in name for name in archive.namelist())


def test_completed_task_resume_does_not_apply_twice(env):
    app, _, root = env
    change, job = apply_one(app, {"title": "Once"})
    before = sha(root / first(app)["path"])
    with pytest.raises(ValueError): app.state.library.run(job["id"])
    assert sha(root / first(app)["path"]) == before
