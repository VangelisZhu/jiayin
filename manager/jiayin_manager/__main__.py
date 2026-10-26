import argparse
import hashlib
import json
import os
import re
import secrets
import struct
import wave
import zipfile
from pathlib import Path

from .files import write_metadata
from .store import Store


def generated_demo(root: Path):
    root.mkdir(parents=True, exist_ok=True)
    if any(root.iterdir()):
        raise ValueError("生成示例仅允许使用空目录")
    examples = [
        ("海岸信号", ["林间", "叶舟"], "沿海来信", ["林间"], ["流行", "独立"], "2023", "华语", "zho", ""),
        ("海岸信号", ["林间", "叶舟"], "晴日选集", ["林间"], ["流行", "独立"], "2025", "华语", "zho", "精选版"),
        ("海岸信号 (Live)", ["林间"], "海边现场", ["林间"], ["民谣"], "2024", "华语", "zho", "现场版"),
        ("夜の窓", ["遠野 灯"], "風の便り", ["遠野 灯"], ["J-Pop"], "2022", "日本", "jpn", ""),
        ("沿途�", ["River feat. Stone"], "", [], [], "", "", "", ""),
        ("Morning Steps", ["North & South"], "First Light", ["North & South"], ["Jazz", "Soul"], "2021", "欧美", "eng", ""),
        ("봄의 편지", ["한봄"], "봄날", ["한봄"], [], "2020", "", "kor", ""),
        ("归途", ["叶舟"], "共享时光", ["Various Artists"], ["民谣"], "2023", "华语", "zho", "合集"),
    ]
    for index, (title, artists, album, album_artists, genres, date, region, language, version) in enumerate(examples):
        path = root / "待整理" / f"{index + 1:02}.wav"
        path.parent.mkdir(parents=True, exist_ok=True)
        # Original generated tones, no copyrighted music or personal library data.
        with wave.open(str(path), "wb") as audio:
            audio.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
            audio.writeframes(b"".join(struct.pack("<h", int(2000 * __import__("math").sin(n * (index + 1) * .08))) for n in range(24000)))
        write_metadata(path, {"title": title, "artists": artists, "album": album, "album_artists": album_artists,
                              "genres": genres, "date": date, "track": str(index + 1), "disc": "1", "region": region,
                              "language": language, "album_version": version, "compilation": version == "合集"})
        if index == 3:
            path.with_suffix(".lrc").write_bytes("[00:00.00]夜の窓を開けて\n[00:01.50]風の便りを待つ\n".encode("cp932"))
            path.with_suffix(".zh.lrc").write_text("[00:00.00]打开夜晚的窗\n[00:01.50]等待风的来信\n", encoding="utf-8")
        elif index == 0:
            path.with_suffix(".lrc").write_text("[00:00.00]海岸线传来信号\n[00:01.50]沿途的风轻轻吹\n", encoding="utf-8")
    (root / "示例.m3u8").write_text("#EXTM3U\n待整理/01.wav\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="家音曲库管理")
    sub = parser.add_subparsers(dest="command", required=True)
    serve = sub.add_parser("serve")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8780)
    demo = sub.add_parser("demo")
    demo.add_argument("directory", type=Path)
    password = sub.add_parser("password")
    password.add_argument("data", type=Path)
    restore = sub.add_parser("restore")
    restore.add_argument("backup", type=Path)
    restore.add_argument("data", type=Path)
    args = parser.parse_args()
    if args.command == "serve":
        import uvicorn
        uvicorn.run("jiayin_manager.app:create_app", factory=True, host=args.host, port=args.port, workers=1, access_log=False)
    elif args.command == "demo":
        generated_demo(args.directory)
        print("已生成示例曲库；全部为合成音频与示例标签。")
    elif args.command == "password":
        import getpass
        value = getpass.getpass("新管理员密码（至少12位）: ")
        if len(value) < 12 or value != getpass.getpass("再次输入: "):
            raise ValueError("密码过短或不一致")
        store = Store(args.data)
        salt = secrets.token_hex(32)
        store.put("auth", "admin", {"salt": salt, "hash": hashlib.scrypt(value.encode(), salt=salt.encode(), n=16384, r=8, p=1).hex()})
        with store.transaction() as db:
            db.execute("DELETE FROM sessions")
        print("密码已更新，已有登录会话已失效。")
    else:
        if args.data.exists() and any(args.data.iterdir()):
            raise ValueError("恢复目标必须为空目录；先停止服务并保留原数据目录")
        kinds = {"track", "change", "job", "identity", "server-before", "verification"}
        with zipfile.ZipFile(args.backup) as archive:
            for info in archive.infolist():
                if info.filename not in {k + ".json" for k in kinds} | {"correction.json"} and not re.fullmatch(r"backups/[0-9a-f]{32}/[0-9]+|artwork/[0-9a-f]{64}\.jpg", info.filename):
                    raise ValueError("备份中包含不允许的路径")
            store = Store(args.data)
            for kind in kinds:
                values = json.loads(archive.read(kind + ".json"))
                for value in values:
                    key = value.get("id") or value.get("file_id") or "latest"
                    store.put(kind, key, value)
            if "correction.json" in archive.namelist():
                for value in json.loads(archive.read("correction.json")):
                    if value["status"] in {"approved", "executing", "waiting_playback"}:
                        value["status"] = "paused"
                    store.put("correction", value["id"], value)
            for info in archive.infolist():
                if info.filename.startswith(("backups/", "artwork/")):
                    target = args.data / info.filename
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(info) as source, target.open("wb") as output:
                        __import__("shutil").copyfileobj(source, output)
        print("恢复记录与文件副本已导入。请重新配置管理密码、服务连接及密钥；不会自动覆盖音乐文件。")


if __name__ == "__main__":
    main()
