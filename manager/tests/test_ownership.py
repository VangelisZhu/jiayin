"""Ownership uses generated fixtures; no user files, personal endpoints or credentials."""
import json
import threading

import pytest

from test_corrections import setup, pair


def draft(client, ids=None, name="Main Artist", request_id="generated-request-1"):
    r = client.post("/api/member/ownership/preview", json={"request_id": request_id, "ids": ids or ["s1"], "target": {"name": name}})
    assert r.status_code == 200, r.text
    return r.json()


def submit(client, item):
    r = client.post("/api/member/ownership/" + item["id"] + "/submit", json={})
    assert r.status_code == 200, r.text
    return r.json()


def approve(admin, item, ids=None):
    return admin.post("/api/ownership/" + item["id"] + "/review", json={"accept": True, "track_ids": ids or [e["track_id"] for e in item["entries"]]})


def test_review_preserves_files_and_requires_admin_even_direct_writer(setup):
    app, admin, music, rows = setup
    client, _ = pair(app, admin, role="direct")
    before = {p: p.read_bytes() for p in music.rglob("*") if p.is_file()}
    original = app.state.store.get("track", rows[0]["id"])
    item = draft(client)
    assert submit(client, item)["status"] == "pending"
    assert client.get("/api/member/ownership/catalog").json()["tracks"] == []
    assert client.post("/api/ownership/" + item["id"] + "/review", json={"accept": True, "track_ids": ["s1"]}).status_code == 401
    result = approve(admin, item)
    assert result.status_code == 200, result.text
    assert result.json()["status"] == "published"
    assert all(p.read_bytes() == value for p, value in before.items())
    assert app.state.store.get("track", rows[0]["id"]) == original
    assert app.state.store.all("job") == []
    assert app.state.store.all("change") == []
    record = client.get("/api/member/ownership/catalog").json()["tracks"][0]
    assert record["target"] == {"id": "", "name": "Main Artist"}
    assert "file_id" not in record
    assert "binding" not in json.dumps(result.json())


def test_own_requests_and_song_visibility(setup):
    app, admin, _, _ = setup
    a, _ = pair(app, admin)
    b, _ = pair(app, admin, "bob")
    assert a.post("/api/member/ownership/preview", json={"request_id": "foreign-track", "ids": ["s2"], "target": {"name": "Main"}}).status_code == 403
    item = draft(a)
    assert b.post("/api/member/ownership/" + item["id"] + "/submit", json={}).status_code == 404
    assert b.get("/api/member/ownership/requests").json() == []
    submit(a, item)
    approve(admin, item)
    catalog = b.get("/api/member/ownership/catalog").json()
    assert catalog["tracks"] == [] and catalog["cursor"] > 0


def test_request_retries_are_idempotent_and_payload_change_rejected(setup):
    app, admin, _, _ = setup
    c, _ = pair(app, admin)
    a = draft(c)
    assert draft(c)["id"] == a["id"]
    changed = c.post("/api/member/ownership/preview", json={"request_id": "generated-request-1", "ids": ["s1"], "target": {"name": "Different"}})
    assert changed.status_code == 409
    assert submit(c, a)["status"] == submit(c, a)["status"] == "pending"
    assert len(app.state.store.all("ownership-request")) == 1
    assert approve(admin, a).status_code == 200
    assert approve(admin, a).status_code == 409


def test_readonly_revoked_and_pending_member_cannot_submit(setup):
    app, admin, _, _ = setup
    c, mid = pair(app, admin, approve=False)
    assert c.get("/api/member/ownership/capabilities").status_code == 403
    admin.put("/api/members/" + mid, json={"role": "readonly"})
    assert c.get("/api/member/ownership/capabilities").status_code == 200
    assert c.post("/api/member/ownership/preview", json={"request_id": "generated-readonly", "ids": ["s1"], "target": {"name": "Main"}}).status_code == 403
    admin.put("/api/members/" + mid, json={"role": "request"})
    item = draft(c)
    device = c.get("/api/member/me").json()["device_id"]
    admin.post("/api/devices/" + device + "/revoke")
    assert c.post("/api/member/ownership/" + item["id"] + "/submit", json={}).status_code == 401


def test_conflicting_review_stops_without_overwriting(setup):
    app, admin, _, _ = setup
    c, _ = pair(app, admin)
    a = draft(c)
    b = draft(c, name="Other Artist", request_id="generated-request-2")
    submit(c, a); submit(c, b)
    assert approve(admin, a).status_code == 200
    assert approve(admin, b).status_code == 409
    assert app.state.store.get("ownership-request", b["id"])["status"] == "conflict"
    assert c.get("/api/member/ownership/catalog").json()["tracks"][0]["target"]["name"] == "Main Artist"


def test_rebind_or_metadata_change_requires_new_preview(setup):
    app, admin, _, rows = setup
    c, _ = pair(app, admin)
    a = draft(c); submit(c, a)
    row = app.state.store.get("track", rows[0]["id"])
    row["hash"] = "different-version"
    app.state.store.put("track", row["id"], row)
    assert approve(admin, a).status_code == 409
    assert app.state.store.all("ownership") == []


def test_partial_approval_snapshot_and_undo_tombstone(setup):
    app, admin, _, rows = setup
    c, _ = pair(app, admin)
    # A broad fixture actor sees two generated songs; production always checks getSong.
    original_row = app.state.corrections.row
    app.state.corrections.row = lambda sid, actor: original_row(sid, None)
    item = draft(c, ids=["s1", "s2", "s1"])
    assert len(item["entries"]) == 2
    submit(c, item)
    result = approve(admin, item, ["s1"])
    assert result.json()["status"] == "partial"
    assert [e["result"] for e in result.json()["entries"]] == ["published", "rejected"]
    first = c.get("/api/member/ownership/catalog").json()
    assert len(first["tracks"]) == 1
    undo = admin.post("/api/ownership/" + item["id"] + "/undo", json={})
    assert undo.status_code == 200, undo.text
    next_page = c.get("/api/member/ownership/catalog?after=" + str(first["cursor"])).json()
    assert len(next_page["tracks"]) == 1 and next_page["tracks"][0]["target"] is None
    assert next_page["cursor"] > first["cursor"]


def test_later_publication_blocks_older_undo(setup):
    _, admin, _, _ = setup
    c, _ = pair(setup[0], admin)
    a = draft(c); submit(c, a); approve(admin, a)
    b = draft(c, name="Newest", request_id="generated-newest"); submit(c, b); approve(admin, b)
    assert admin.post("/api/ownership/" + a["id"] + "/undo", json={}).status_code == 409
    assert c.get("/api/member/ownership/catalog").json()["tracks"][0]["target"]["name"] == "Newest"


def test_rejection_and_withdrawal_leave_shared_state_untouched(setup):
    app, admin, _, _ = setup
    c, _ = pair(app, admin)
    a = draft(c); submit(c, a)
    assert admin.post("/api/ownership/" + a["id"] + "/review", json={"accept": False, "reason": "check main artist"}).json()["status"] == "rejected"
    b = draft(c, request_id="generated-withdraw"); submit(c, b)
    assert c.post("/api/member/ownership/" + b["id"] + "/withdraw", json={}).json()["status"] == "withdrawn"
    assert approve(admin, b).status_code == 409
    assert app.state.store.all("ownership") == []


def test_existing_target_must_be_verified_on_server(setup):
    app, admin, _, _ = setup
    c, _ = pair(app, admin)
    for target in [{"id": "invented", "name": "Fake"}, {"id": "name:fake", "name": "Fake"}]:
        r = c.post("/api/member/ownership/preview", json={"request_id": "generated-target", "ids": ["s1"], "target": target})
        assert r.status_code == 409, r.text


def test_verified_existing_target_is_checked_again_during_review(setup):
    app, admin, _, _ = setup
    c, _ = pair(app, admin)
    original = app.state.corrections.remote
    app.state.corrections.remote = lambda u, t, s, method, extra=None: {"artist": {"id": "main-id", "name": "Main"}} if method == "getArtist" else original(u, t, s, method, extra)
    r = c.post("/api/member/ownership/preview", json={"request_id": "generated-verified-target", "ids": ["s1"], "target": {"id": "main-id", "name": "Main"}})
    assert r.status_code == 200, r.text
    item = r.json(); submit(c, item)
    app.state.corrections.nd.request = lambda method, params: {"artist": {"id": "main-id", "name": "Renamed"}}
    assert approve(admin, item).status_code == 409
    assert app.state.store.all("ownership") == []


def test_rebound_song_retracts_old_ownership_in_incremental_feed(setup):
    app, admin, _, rows = setup
    c, _ = pair(app, admin)
    item = draft(c); submit(c, item); approve(admin, item)
    first = c.get("/api/member/ownership/catalog").json()
    a = app.state.store.get("track", rows[0]["id"]); a["server_id"] = "replaced-old"
    b = app.state.store.get("track", rows[1]["id"]); b["server_id"] = "s1"
    app.state.store.put("track", a["id"], a); app.state.store.put("track", b["id"], b)
    result = c.get("/api/member/ownership/catalog?after=" + str(first["cursor"])).json()
    assert len(result["tracks"]) == 1 and result["tracks"][0]["target"] is None


def test_request_summaries_are_bounded_and_full_detail_is_authorized(setup):
    app, admin, _, _ = setup
    c, _ = pair(app, admin)
    item = draft(c)
    record = app.state.store.get("ownership-request", item["id"])
    record["entries"] *= 100
    app.state.store.put("ownership-request", item["id"], record)
    summary = c.get("/api/member/ownership/requests").json()[0]
    assert summary["entry_count"] == 100 and len(summary["entries"]) == 3
    assert len(c.get("/api/member/ownership/requests/" + item["id"]).json()["entries"]) == 100
    other, _ = pair(app, admin, "bob")
    assert other.get("/api/member/ownership/requests/" + item["id"]).status_code == 404


def test_shared_feed_pages_without_missing_items(setup):
    app, admin, _, rows = setup
    c, _ = pair(app, admin)
    source = app.state.store.get("track", rows[0]["id"])
    for row in app.state.store.all("track"):
        app.state.store.delete("track", row["id"])
    generated = {}
    for i in range(51):
        row = {**source, "id": "generated-file-" + str(i), "server_id": "generated-song-" + str(i)}
        app.state.store.put("track", row["id"], row)
        generated[row["server_id"]] = row
    app.state.corrections.row = lambda sid, actor: generated[sid]
    item = draft(c, ids=list(generated)); submit(c, item)
    assert approve(admin, item).status_code == 200
    first = c.get("/api/member/ownership/catalog").json()
    assert len(first["tracks"]) == 50 and first["has_more"]
    second = c.get("/api/member/ownership/catalog?after=" + str(first["cursor"])).json()
    assert len(second["tracks"]) == 1 and not second["has_more"]
    assert len({r["track_id"] for r in first["tracks"] + second["tracks"]}) == 51


def test_publication_failure_rolls_back_rules_and_review_together(setup, monkeypatch):
    app, admin, _, _ = setup
    c, _ = pair(app, admin)
    item = draft(c); submit(c, item)
    original = app.state.store.transaction
    from contextlib import contextmanager
    @contextmanager
    def fail_commit():
        with original() as db:
            yield db
            raise RuntimeError("generated commit failure")
    monkeypatch.setattr(app.state.store, "transaction", fail_commit)
    with pytest.raises(Exception):
        approve(admin, item)
    assert app.state.store.get("ownership-request", item["id"])["status"] == "pending"
    assert app.state.store.all("ownership") == []
    assert app.state.store.get("ownership-meta", "counter", 0) == 0


def test_concurrent_approvals_publish_only_one_version(setup):
    app, admin, _, _ = setup
    c, _ = pair(app, admin)
    a = draft(c); b = draft(c, name="Other", request_id="generated-parallel")
    submit(c, a); submit(c, b)
    results = []
    threads = [threading.Thread(target=lambda item=i: results.append(approve(admin, item).status_code)) for i in [a, b]]
    for t in threads: t.start()
    for t in threads: t.join()
    assert sorted(results) == [200, 409]
    assert app.state.store.get("ownership-meta", "counter") == 1
