import json
import os
import shutil
import threading
import uuid
from collections import Counter, defaultdict
from pathlib import Path

from mutagen import MutagenError

from .files import EXTENSIONS, audio_hash, broken, read_metadata, safe_path, sha, target_path, write_metadata, write_artwork
from .models import checked_values
from .store import stamp


def uid():
    return uuid.uuid4().hex


class LibraryManager:
    def __init__(self, root: Path, store):
        self.root = root.resolve(strict=True)
        self.store = store
        if store.path.is_relative_to(self.root) or self.root.is_relative_to(store.path):
            raise ValueError("音乐与后台数据目录必须独立")
        self.operation = threading.RLock()
        self.worker = threading.Lock()
        # Interrupted operations remain paused until explicitly resumed. Reconcile journal first.
        for job in store.all("job"):
            if job["status"] == "running":
                job["status"] = "paused"
                store.put("job", job["id"], job)

    def track(self, file_id):
        row = self.store.get("track", file_id)
        if not row or row.get("missing"):
            raise ValueError("曲目不存在或文件已丢失，请重新体检")
        return row

    def scan(self, progress=None):
        with self.operation:
            prior = {r["path"]: r for r in self.store.all("track")}
            seen, errors = set(), []
            for folder, dirs, files in os.walk(self.root, followlinks=False):
                dirs[:] = [d for d in dirs if not d.startswith(".") and not (Path(folder) / d).is_symlink()]
                for name in files:
                    if name.startswith(".") or Path(name).suffix.lower() not in EXTENSIONS:
                        continue
                    relative = (Path(folder) / name).relative_to(self.root).as_posix()
                    try:
                        path = safe_path(self.root, relative)
                        metadata, duration = read_metadata(path)
                        old = prior.get(relative, {})
                        file_hash = sha(path)
                        row = {**old, "id": old.get("id", uid()), "path": relative, "metadata": metadata,
                               "duration": duration, "hash": file_hash, "size": path.stat().st_size,
                               "scan_verified": old.get("scan_verified", False) and old.get("hash") == file_hash,
                               "missing": False, "scanned_at": stamp(), "locks": old.get("locks", [])}
                        self.store.put("track", row["id"], row)
                        seen.add(relative)
                    except (ValueError, OSError, UnicodeError, MutagenError, EOFError) as exc:
                        errors.append({"path": relative, "error": type(exc).__name__})
                    if progress and (len(seen) + len(errors)) % 10 == 0:
                        progress(len(seen), len(errors))
            for relative, row in prior.items():
                if relative not in seen:
                    row["missing"] = True
                    self.store.put("track", row["id"], row)
            self.store.put("scan", "latest", {"at": stamp(), "count": len(seen), "errors": errors})
            return {"count": len(seen), "errors": errors}

    def findings(self):
        findings, albums = [], defaultdict(list)
        for row in self.store.all("track"):
            if row.get("missing"):
                findings.append({"file_id": row["id"], "kind": "missing_file", "detail": "原文件不存在，等待核对身份"})
                continue
            m = row["metadata"]
            kinds = []
            if any(broken(str(v)) for v in m.values()):
                kinds.append(("encoding", "标签包含替换符或非法控制字符"))
            for field, label in [("title", "歌名"), ("artists", "歌曲歌手"), ("album", "专辑"), ("album_artists", "专辑歌手"), ("genres", "曲风"), ("region", "整理地区"), ("language", "语种")]:
                if not m[field]:
                    kinds.append(("missing_" + field, f"缺少{label}"))
            if len(m["artists"]) == 1 and any(s in m["artists"][0].lower() for s in (" feat. ", " ft. ", " / ", "; ")):
                kinds.append(("collaboration", "署名可能含多位演唱者，需要核实，不能按符号直接拆分"))
            if row.get("scan_verified") and m["album_artists"] and m["album"] and m["track"]:
                try:
                    if row["path"] != target_path(row):
                        kinds.append(("directory", "实际路径与已确认标签建议路径不同"))
                except ValueError:
                    pass
            lrc = safe_path(self.root, row["path"], False).with_suffix(".lrc")
            if lrc.exists():
                safe_path(self.root, lrc.relative_to(self.root).as_posix())
                try:
                    text = lrc.read_text("utf-8-sig")
                    if broken(text):
                        kinds.append(("lyrics_encoding", "歌词文本已损坏，需恢复或匹配候选"))
                except UnicodeError:
                    kinds.append(("lyrics_encoding", "歌词不是有效 UTF-8，需预览解码候选"))
            for kind, detail in kinds:
                findings.append({"file_id": row["id"], "kind": kind, "detail": detail})
            if m["album"]:
                albums[m["album"].strip().casefold()].append(row)
        for group in albums.values():
            signatures = {(tuple(r["metadata"]["album_artists"]), r["metadata"]["release_id"], r["metadata"]["album_version"]) for r in group}
            if len(signatures) > 1:
                for r in group:
                    findings.append({"file_id": r["id"], "kind": "album_review", "detail": "同名专辑存在不同署名或发行版本：可能合理，需核对，不自动合并"})
        return findings

    def draft(self, ids, values, lock=True, source="manual", evidence=None):
        values = dict(values)
        if "artists" in values:
            values.setdefault("artist_credit", " / ".join(values["artists"]))
            values.setdefault("artist_ids", [])
        if "album_artists" in values:
            values.setdefault("album_artist_ids", [])
        values = checked_values(values)
        changes = []
        for file_id in dict.fromkeys(ids):
            row = self.track(file_id)
            if source != "manual" and set(values) & set(row["locks"]):
                raise ValueError("关联字段已被人工锁定，请重新检查建议")
            allowed = {k: v for k, v in values.items() if source == "manual" or k not in row["locks"]}
            if any(k in allowed and allowed[k] != row["metadata"][k] for k in ("album", "album_artists", "album_version", "date")):
                if source != "manual" and {"release_id", "release_track_id"} & set(row["locks"]):
                    raise ValueError("关联发行身份已被人工锁定，请先核对")
                for identity in ("release_id", "release_track_id"):
                    allowed.setdefault(identity, "")
            diff = {k: v for k, v in allowed.items() if row["metadata"][k] != v}
            if not diff:
                continue
            change = {"id": uid(), "file_id": file_id, "kind": "tags", "path": row["path"],
                      "before_hash": row["hash"], "before": {k: row["metadata"][k] for k in diff},
                      "values": diff, "lock": lock, "source": source, "evidence": evidence or [],
                      "status": "draft", "at": stamp()}
            self.store.put("change", change["id"], change)
            changes.append(change)
        return changes

    def move_draft(self, ids):
        changes, destinations = [], set()
        for file_id in dict.fromkeys(ids):
            row = self.track(file_id)
            if not row.get("scan_verified"):
                raise ValueError("请先完成此曲目的服务器扫描与身份核对，再生成目录迁移")
            destination = target_path(row)
            if destination == row["path"]:
                continue
            path = safe_path(self.root, destination, must_exist=False)
            if path.exists() or destination.casefold() in destinations:
                raise ValueError("目标路径重名，请修正版本标识；不会覆盖")
            destinations.add(destination.casefold())
            change = {"id": uid(), "file_id": file_id, "kind": "move", "path": row["path"], "destination": destination,
                      "before_hash": row["hash"], "before": {"path": row["path"]}, "values": {"path": destination},
                      "lock": False, "source": "directory", "evidence": [], "status": "draft", "at": stamp()}
            self.store.put("change", change["id"], change)
            changes.append(change)
        return changes

    def artwork_draft(self, file_id, asset):
        row = self.track(file_id)
        change = {"id": uid(), "file_id": file_id, "kind": "artwork", "path": row["path"],
                  "before_hash": row["hash"], "before": {"cover": "当前内嵌封面"}, "values": {},
                  "artwork": asset, "lock": False, "source": "manual", "evidence": [], "status": "draft", "at": stamp()}
        self.store.put("change", change["id"], change)
        return change

    def lyric_draft(self, file_id, text, origin, translation=False):
        if not isinstance(text, str) or not text.strip() or len(text.encode()) > 1024 * 1024 or broken(text):
            raise ValueError("歌词为空、过长或仍含损坏字符")
        row = self.track(file_id)
        audio = safe_path(self.root, row["path"])
        path = audio.with_suffix(".zh.lrc" if translation else ".lrc")
        safe_path(self.root, path.relative_to(self.root).as_posix(), False)
        if path.exists() and path.stat().st_size > 1024 * 1024:
            raise ValueError("原歌词文件过大，请单独检查")
        change = {"id": uid(), "file_id": file_id, "kind": "lyrics", "path": path.relative_to(self.root).as_posix(),
                  "before_hash": sha(path) if path.exists() else None, "audio_hash": row["hash"],
                  "before": {"lyrics": path.read_bytes().decode("utf-8", "replace") if path.exists() else ""},
                  "values": {"lyrics": text}, "translation": translation, "lock": False, "source": origin, "evidence": [], "status": "draft", "at": stamp()}
        self.store.put("change", change["id"], change)
        return change

    def job(self, ids, undo=False):
        with self.operation:
            changes = [self.store.get("change", i) for i in dict.fromkeys(ids)]
            if not changes or any(c is None for c in changes):
                raise ValueError("修改草稿不存在")
            if len({c["kind"] == "move" for c in changes}) > 1:
                raise ValueError("标签与目录迁移必须分批执行")
            if len({(c["file_id"], c["kind"], c.get("translation", False)) for c in changes}) != len(changes):
                raise ValueError("同一曲目有重叠修改，请先选择一个草稿")
            if any((c["status"] not in {"applied", "queued"} or not c.get("effects")) if undo else c["status"] != "draft" for c in changes):
                raise ValueError("草稿已执行或正在其他任务中")
            selected = {c["id"] for c in changes}
            files = {c["file_id"] for c in changes}
            if any(c["id"] not in selected and c["file_id"] in files and c["status"] in {"queued", "undo_queued"} for c in self.store.all("change")):
                raise ValueError("同一文件已有未完成任务，请先继续或回退原任务")
            job = {"id": uid(), "ids": [c["id"] for c in changes], "undo": undo, "status": "paused", "results": {}, "at": stamp()}
            for c in changes:
                c["status"] = "undo_queued" if undo else "queued"
                self.store.put("change", c["id"], c)
            return self.store.put("job", job["id"], job)

    def run(self, job_id):
        if not self.worker.acquire(blocking=False):
            return  # A second simultaneous resume remains paused; no second writer is started.
        try:
            job = self.store.get("job", job_id)
            if not job or job["status"] not in {"paused", "failed"}:
                raise ValueError("任务不可继续")
            job["status"] = "running"
            self.store.put("job", job_id, job)
            for change_id in job["ids"]:
                if self.store.get("job", job_id)["status"] == "paused":
                    return
                if job["results"].get(change_id, {}).get("ok"):
                    continue
                try:
                    with self.operation:
                        change = self.store.get("change", change_id)
                        if change.get("correction_id") and not job["undo"]:
                            self.correction_guard(change)
                        if change["kind"] == "lyrics":
                            # Earlier approved tag/cover writes in this exact job may change
                            # the container hash; only accept their journalled final hash.
                            for previous_id, result in job["results"].items():
                                previous = self.store.get("change", previous_id)
                                if result.get("ok") and previous["file_id"] == change["file_id"] and previous["kind"] in {"tags", "artwork"} and previous["before_hash"] == change["audio_hash"]:
                                    change["audio_hash"] = previous["effects"][0]["after"]
                        if change["status"] == "rejected":
                            job["results"][change_id] = {"ok": True, "skipped": True}
                            self.store.put("job", job_id, job)
                            continue
                        self._undo(change) if job["undo"] else self._apply(change)
                    job["results"][change_id] = {"ok": True}
                except Exception as exc:
                    # Never include network URLs, credentials, absolute paths or arbitrary provider bodies.
                    job["results"][change_id] = {"ok": False, "error": str(exc) if isinstance(exc, ValueError) else type(exc).__name__}
                live = self.store.get("job", job_id)
                job["status"] = live["status"]
                self.store.put("job", job_id, job)
            job["status"] = "awaiting_scan" if all(r["ok"] for r in job["results"].values()) else "failed"
            self.store.put("job", job_id, job)
        finally:
            self.worker.release()

    def pause(self, job_id):
        job = self.store.get("job", job_id)
        if not job or job["status"] != "running":
            raise ValueError("任务当前未运行")
        job["status"] = "paused"
        self.store.put("job", job_id, job)
        return job

    def _stage_effect(self, change, path, content=None, move_to=None):
        relative = path.relative_to(self.root).as_posix()
        safe_path(self.root, relative, False)
        before = sha(path) if path.exists() else None
        backup = self.store.path / "backups" / change["id"] / str(len(change.get("effects", [])))
        backup.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            if shutil.disk_usage(backup.parent).free < path.stat().st_size + 8 * 1024 * 1024:
                raise ValueError("备份空间不足")
            shutil.copy2(path, backup)
            if sha(backup) != before:
                raise ValueError("备份验证失败")
        return {"path": relative, "destination": move_to, "before": before, "after": None,
                "backup": backup.relative_to(self.store.path).as_posix(), "done": False}

    def _apply(self, change):
        row = self.track(change["file_id"])
        if change.get("effects"):
            self._recover(change)
            return
        path = safe_path(self.root, change["path"], False)
        if (sha(path) if path.exists() else None) != change["before_hash"]:
            raise ValueError("文件在预览后已变化，请重新分析")
        if change["kind"] == "lyrics" and sha(safe_path(self.root, row["path"])) != change["audio_hash"]:
            raise ValueError("歌曲在歌词预览后已变化")
        effects = []
        change["old_locks"] = row["locks"]
        if change["kind"] == "move":
            if not row.get("scan_verified"):
                raise ValueError("当前标签尚未完成服务器扫描核对")
            target = safe_path(self.root, change["destination"], False)
            if target.exists():
                raise ValueError("目标已存在，不会覆盖")
            effects.append(self._stage_effect(change, path, move_to=change["destination"]))
            # Lyrics move with their track; shared artwork is copied and retained at source.
            for sidecar in sorted(path.parent.glob(path.stem + "*.lrc")):
                if sidecar.stem != path.stem and not sidecar.name.startswith(path.stem + "."):
                    continue
                destination = target.with_name(target.stem + sidecar.name[len(path.stem):])
                if destination.exists():
                    raise ValueError("歌词目标重名")
                change["effects"] = effects
                effects.append(self._stage_effect(change, sidecar, move_to=destination.relative_to(self.root).as_posix()))
            for name in ("cover.jpg", "cover.png", "folder.jpg"):
                image = path.parent / name
                dest = target.parent / name
                if image.is_file() and not dest.exists():
                    if image.stat().st_size > 16 * 1024 * 1024:
                        raise ValueError("共用封面过大，请先单独整理")
                    safe_path(self.root, image.relative_to(self.root).as_posix())
                    change["effects"] = effects
                    effect = self._stage_effect(change, dest, content=image.read_bytes())
                    effect["new_content"] = image.read_bytes().hex()
                    effect["after"] = sha(image)
                    effects.append(effect)
            # Preserve UTF-8 relative-path M3U references. Other encodings require manual review.
            for playlist in self.root.rglob("*.m3u*"):
                if playlist.suffix.lower() not in {".m3u", ".m3u8"}:
                    continue
                safe_path(self.root, playlist.relative_to(self.root).as_posix())
                raw = playlist.read_bytes()
                try:
                    text = raw.decode("utf-8-sig")
                except UnicodeError:
                    raise ValueError("存在非 UTF-8 播放列表，请先规范编码再迁移目录")
                lines, altered = [], False
                for line in text.splitlines(keepends=True):
                    value = line.strip()
                    candidate = (playlist.parent / value.replace("\\", "/")).resolve()
                    if value and not value.startswith("#") and candidate == path:
                        newline = "\r\n" if line.endswith("\r\n") else "\n" if line.endswith("\n") else ""
                        line = os.path.relpath(target, playlist.parent).replace("\\", "/") + newline
                        altered = True
                    lines.append(line)
                if altered:
                    change["effects"] = effects
                    effect = self._stage_effect(change, playlist)
                    effect["new_content"] = "".join(lines).encode().hex()
                    effect["after"] = __import__("hashlib").sha256(bytes.fromhex(effect["new_content"])).hexdigest()
                    effects.append(effect)
        else:
            effects.append(self._stage_effect(change, path))
            temporary = path.parent / (".jiayin-" + change["id"] + path.suffix)
            if path.exists() and shutil.disk_usage(path.parent).free < path.stat().st_size + 8 * 1024 * 1024:
                raise ValueError("音乐目录暂存空间不足")
            try:
                if change["kind"] in {"tags", "artwork"}:
                    before_audio = audio_hash(path)
                    shutil.copy2(path, temporary)
                    if change["kind"] == "tags":
                        write_metadata(temporary, change["values"])
                    if change.get("artwork"):
                        asset = change["artwork"]
                        if not __import__("re").fullmatch(r"[a-f0-9]{64}", asset):
                            raise ValueError("封面标识无效")
                        cover = self.store.path / "artwork" / (asset + ".jpg")
                        if not cover.is_file() or sha(cover) != asset:
                            raise ValueError("封面暂存缺失或校验失败")
                        write_artwork(temporary, cover.read_bytes())
                    if audio_hash(temporary) != before_audio:
                        raise ValueError("标签修改影响了音频，已阻止写入")
                    actual, _ = read_metadata(temporary)
                    if any(actual[k] != v for k, v in change["values"].items()):
                        raise ValueError("标签回读与预览不一致，已阻止写入")
                else:
                    temporary.write_bytes(change["values"]["lyrics"].encode("utf-8"))
                effects[0]["after"] = sha(temporary)
                effects[0]["stage"] = temporary.relative_to(self.root).as_posix()
                change["effects"] = effects
                self.store.put("change", change["id"], change)
                self._recover(change)
                return
            finally:
                # Keep a persisted stage on failure so an interrupted job can resume.
                if temporary.exists() and not self.store.get("change", change["id"], {}).get("effects"):
                    temporary.unlink()
        for e in effects:
            if e["destination"]:
                e["after"] = e["before"]
        change["effects"] = effects
        self.store.put("change", change["id"], change)
        self._recover(change)

    def _recover(self, change):
        for effect in change["effects"]:
            source = safe_path(self.root, effect["path"], False)
            target = safe_path(self.root, effect["destination"], False) if effect["destination"] else source
            current = sha(target) if target.exists() else None
            if current == effect["after"] and (source == target or not source.exists()):
                effect["done"] = True
            elif source != target and current == effect["after"] and source.exists() and sha(source) == effect["before"]:
                # Recover a crash after the no-overwrite hardlink but before unlink/journal.
                source.unlink()
                effect["done"] = True
            elif effect["done"]:
                raise ValueError("任务中断后文件又被修改，请人工核对")
            else:
                if (sha(source) if source.exists() else None) != effect["before"]:
                    raise ValueError("执行时文件发生变化，停止当前项")
                target.parent.mkdir(parents=True, exist_ok=True)
                safe_path(self.root, target.relative_to(self.root).as_posix(), False)
                if source != target:
                    if target.exists():
                        raise ValueError("迁移目标已存在")
                    # link+unlink refuses overwrite; mounted library must support hardlinks.
                    os.link(source, target)
                    source.unlink()
                else:
                    stage = safe_path(self.root, effect["stage"], False) if effect.get("stage") else source.parent / (".jiayin-" + uid() + source.suffix)
                    if not effect.get("stage"):
                        stage.write_bytes(bytes.fromhex(effect["new_content"]))
                    if sha(stage) != effect["after"]:
                        raise ValueError("暂存文件验证失败")
                    os.replace(stage, target)
                effect["done"] = True
            self.store.put("change", change["id"], change)
        row = self.track(change["file_id"])
        if change["kind"] == "move":
            row["path"] = change["destination"]
        if change["kind"] != "lyrics":
            path = safe_path(self.root, row["path"])
            row["metadata"], row["duration"] = read_metadata(path)
            row["hash"] = sha(path)
            row["size"] = path.stat().st_size
            row["scan_verified"] = False
        if change.get("lock"):
            row["locks"] = sorted(set(row["locks"]) | set(change["values"]))
        self.store.put("track", row["id"], row)
        change["status"] = "applied"
        change["applied_at"] = stamp()
        self.store.put("change", change["id"], change)

    def _undo(self, change):
        # Preflight all effects before restoring any; interrupted undo is journalled per effect.
        for effect in change["effects"]:
            original = safe_path(self.root, effect["path"], False)
            target = safe_path(self.root, effect["destination"] or effect["path"], False)
            if effect.get("undone"):
                if (sha(original) if original.exists() else None) != effect["before"]:
                    raise ValueError("回退中断后原文件又被修改")
                continue
            original_hash = sha(original) if original.exists() else None
            target_hash = sha(target) if target.exists() else None
            if change.get("undo_started") and original_hash == effect["before"] and (original == target or target_hash is None):
                effect["undone"] = True
                self.store.put("change", change["id"], change)
                continue
            if not effect.get("done") and original_hash == effect["before"] and (original == target or target_hash is None):
                effect["undone"] = True  # This effect never reached its write during partial apply.
                continue
            if not target.exists() or sha(target) != effect["after"]:
                raise ValueError("回退冲突：文件已有后续修改")
            if effect["destination"] and original.exists() and not (change.get("undo_started") and original_hash == effect["before"]):
                raise ValueError("回退原路径已被占用")
            if effect["before"] is not None:
                backup = self.store.path / effect["backup"]
                if not backup.is_file() or sha(backup) != effect["before"]:
                    raise ValueError("回退备份缺失或验证失败")
        change["undo_started"] = True
        self.store.put("change", change["id"], change)
        for effect in reversed(change["effects"]):
            if effect.get("undone"):
                continue
            original = safe_path(self.root, effect["path"], False)
            target = safe_path(self.root, effect["destination"] or effect["path"], False)
            if effect["destination"]:
                original.parent.mkdir(parents=True, exist_ok=True)
                if not original.exists():
                    os.link(target, original)
                target.unlink()
            elif effect["before"] is None:
                target.unlink()
            else:
                temporary = original.parent / (".jiayin-undo-" + change["id"] + original.suffix)
                shutil.copy2(self.store.path / effect["backup"], temporary)
                os.replace(temporary, original)
            effect["undone"] = True
            self.store.put("change", change["id"], change)
        row = self.track(change["file_id"])
        if change["kind"] == "move":
            row["path"] = change["path"]
        if change["kind"] != "lyrics":
            path = safe_path(self.root, row["path"])
            row["metadata"], row["duration"] = read_metadata(path)
            row["hash"] = sha(path)
        row["locks"] = change.get("old_locks", row["locks"])
        row["scan_verified"] = False
        self.store.put("track", row["id"], row)
        change["status"] = "undone"
        self.store.put("change", change["id"], change)

    def summary(self):
        rows = [r for r in self.store.all("track") if not r.get("missing")]
        findings = self.findings()
        return {"tracks": len(rows), "albums": len({r["metadata"]["release_id"] or (r["metadata"]["album"], tuple(r["metadata"]["album_artists"]), r["metadata"]["date"], r["metadata"]["album_version"]) for r in rows}),
                "artists": len({a for r in rows for a in r["metadata"]["artists"]}), "issues": dict(Counter(f["kind"] for f in findings)),
                "scan": self.store.get("scan", "latest"), "drafts": sum(c["status"] == "draft" for c in self.store.all("change"))}
