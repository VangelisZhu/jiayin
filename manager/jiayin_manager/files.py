import hashlib
import os
import re
import subprocess
import unicodedata
import wave
from pathlib import Path, PurePosixPath

import mutagen
from mutagen.id3 import ID3, Frames, TXXX
from mutagen.mp4 import MP4, MP4FreeForm

from .models import Metadata


EXTENSIONS = {".mp3", ".flac", ".m4a", ".mp4", ".ogg", ".opus", ".wav", ".aiff", ".aif"}
TEXT_TAGS = {
    "title": ("TIT2", "title", "\xa9nam"), "artist_credit": ("TPE1", "artist", "\xa9ART"),
    "album": ("TALB", "album", "\xa9alb"), "date": ("TDRC", "date", "\xa9day"),
    "original_date": ("TDOR", "originaldate", "originaldate"), "track": ("TRCK", "tracknumber", "trkn"),
    "disc": ("TPOS", "discnumber", "disk"), "language": ("TLAN", "language", "language"),
    "region": ("TXXX:JIAYIN_REGION", "jiayin_region", "JIAYIN_REGION"),
    "album_version": ("TXXX:ALBUMVERSION", "albumversion", "ALBUMVERSION"),
    "recording_id": ("TXXX:MusicBrainz Recording Id", "musicbrainz_trackid", "MusicBrainz Track Id"),
    "release_id": ("TXXX:MusicBrainz Album Id", "musicbrainz_albumid", "MusicBrainz Album Id"),
    "release_track_id": ("TXXX:MusicBrainz Release Track Id", "musicbrainz_releasetrackid", "MusicBrainz Release Track Id"),
    "artists": ("TXXX:ARTISTS", "artists", "ARTISTS"),
    "album_artists": ("TXXX:ALBUMARTISTS", "albumartists", "ALBUMARTISTS"), "genres": ("TCON", "genre", "\xa9gen"),
}
TEXT_TAGS.update({"artist_ids": ("TXXX:MusicBrainz Artist Id", "musicbrainz_artistid", "MusicBrainz Artist Id"),
                  "album_artist_ids": ("TXXX:MusicBrainz Album Artist Id", "musicbrainz_albumartistid", "MusicBrainz Album Artist Id")})
LIST_FIELDS = {"artists", "album_artists", "genres", "artist_ids", "album_artist_ids"}


def sha(path: Path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def safe_path(root: Path, relative: str, must_exist=True):
    # Reject traversal and links before resolving; Windows drive names are also forbidden on Linux.
    parts = PurePosixPath(relative.replace("\\", "/")).parts
    if not parts or any(p in {"..", "."} or ":" in p for p in parts) or relative.startswith(("/", "\\")):
        raise ValueError("路径不在音乐根目录内")
    path = root
    for part in parts:
        path = path / part
        if path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction()):
            raise ValueError("不允许跟随符号链接或目录联接")
    resolved = path.resolve()
    if not resolved.is_relative_to(root.resolve()) or (must_exist and not resolved.is_file()):
        raise ValueError("文件不存在或超出音乐根目录")
    return resolved


def read_metadata(path: Path):
    audio = mutagen.File(path)
    if audio is None or audio.info is None:
        raise ValueError("无法识别音频格式")
    tags = audio.tags or {}
    id3 = isinstance(tags, ID3)
    mp4 = isinstance(audio, MP4)
    values = {}
    for field, keys in TEXT_TAGS.items():
        key = keys[0 if id3 else 2 if mp4 else 1]
        if mp4 and key not in {"\xa9nam", "\xa9ART", "\xa9alb", "\xa9day", "trkn", "disk", "aART", "\xa9gen"}:
            key = "----:com.apple.iTunes:" + key
        item = tags.get(key, [])
        if id3:
            item = getattr(item, "text", [])
        if mp4 and field in {"track", "disc"}:
            item = [("/".join(str(v) for v in item[0]) if item[0][1] else str(item[0][0]))] if item else []
        strings = [v.decode("utf-8", "strict") if isinstance(v, bytes) else str(v) for v in item]
        values[field] = strings if field in LIST_FIELDS else (strings[0] if strings else "")
    if not values["album_artists"]:
        item = tags.get("TPE2" if id3 else "aART" if mp4 else "albumartist", [])
        values["album_artists"] = list(getattr(item, "text", item))
    if not values["artists"]:
        values["artists"] = [values["artist_credit"]] if values["artist_credit"] else []
    if not values["artist_credit"]:
        values["artist_credit"] = " / ".join(values["artists"])
    if id3:
        # Picard commonly stores the recording ID in UFID rather than TXXX.
        ufid = tags.get("UFID:http://musicbrainz.org")
        if ufid and not values["recording_id"]:
            values["recording_id"] = ufid.data.decode("utf-8", "strict")
        values["compilation"] = str(tags.get("TCMP", "0")) == "1"
    else:
        raw = tags.get("cpil" if mp4 else "compilation", [False])
        values["compilation"] = str(raw[0] if isinstance(raw, list) else raw).lower() in {"1", "true"}
    return Metadata(**values).model_dump(), round(audio.info.length, 3)


def write_metadata(path: Path, values: dict):
    values = dict(values)
    if "artists" in values and "artist_credit" not in values:
        values["artist_credit"] = " / ".join(values["artists"])
    if "artists" in values and "artist_ids" not in values:
        values["artist_ids"] = []
    if "album_artists" in values and "album_artist_ids" not in values:
        values["album_artist_ids"] = []
    audio = mutagen.File(path)
    if audio is None:
        raise ValueError("不支持的音频")
    if audio.tags is None:
        audio.add_tags()
    tags = audio.tags
    id3, mp4 = isinstance(tags, ID3), isinstance(audio, MP4)
    for field, value in values.items():
        if field == "compilation":
            if id3:
                tags.add(Frames["TCMP"](encoding=3, text=["1" if value else "0"]))
            else:
                tags["cpil" if mp4 else "compilation"] = [value if mp4 else "1" if value else "0"]
            continue
        keys = TEXT_TAGS[field]
        key = keys[0 if id3 else 2 if mp4 else 1]
        strings = value if isinstance(value, list) else [value] if value else []
        if id3:
            tags.delall(key)
            if strings:
                if key.startswith("TXXX:"):
                    tags.add(TXXX(encoding=3, desc=key[5:], text=strings))
                else:
                    tags.add(Frames[key](encoding=3, text=strings))
            if field == "recording_id":
                tags.delall("UFID:http://musicbrainz.org")
        else:
            if mp4:
                if field in {"track", "disc"}:
                    digits = (strings[0].split("/") + ["0"])[:2] if strings else []
                    strings = [tuple(int(n or "0") for n in digits)] if digits else []
                elif key not in {"\xa9nam", "\xa9ART", "\xa9alb", "\xa9day", "aART", "\xa9gen"}:
                    key = "----:com.apple.iTunes:" + key
                    strings = [MP4FreeForm(v.encode("utf-8")) for v in strings]
            if strings:
                tags[key] = strings
            elif key in tags:
                del tags[key]
    if "album_artists" in values:
        display = " / ".join(values["album_artists"])
        if id3:
            tags.delall("TPE2")
            if display:
                tags.add(Frames["TPE2"](encoding=3, text=[display]))
        elif mp4:
            tags["aART"] = [display]
        else:
            tags["albumartist"] = [display]
    audio.save()


def audio_hash(path: Path):
    if path.suffix.lower() == ".wav":
        try:
            with wave.open(str(path), "rb") as audio:
                digest = hashlib.sha256(str((audio.getnchannels(), audio.getsampwidth(), audio.getframerate())).encode())
                length = 0
                for chunk in iter(lambda: audio.readframes(65536), b""):
                    digest.update(chunk)
                    length += len(chunk)
                if length != audio.getnframes() * audio.getnchannels() * audio.getsampwidth():
                    raise ValueError("WAV 音频数据不完整")
                return digest.hexdigest()
        except wave.Error:
            pass  # Compressed WAV is validated by ffmpeg instead.
    # RFC 9639 STREAMINFO authenticates all decoded, signed little-endian samples.
    # Some playable FLAC containers have non-audio trailing data which ffmpeg treats
    # as a bad packet. Accept that only when both the complete sample count and the
    # embedded PCM MD5 match. Never use tolerant decoding without those guarantees.
    from mutagen.flac import FLAC
    audio = mutagen.File(path)
    if audio is None or audio.info is None:
        raise ValueError("无法识别音频，已停止校验")
    formats = {8: ("s8", "pcm_s8"), 16: ("s16le", "pcm_s16le"), 24: ("s24le", "pcm_s24le"), 32: ("s32le", "pcm_s32le")}
    info = audio.info if isinstance(audio, FLAC) and audio.info.bits_per_sample in formats else None
    tolerant = bool(info and info.md5_signature and info.total_samples)
    raw_format, codec = formats[info.bits_per_sample] if info else ("s32le", "pcm_s32le")
    executable = os.getenv("JIAYIN_FFMPEG", "ffmpeg")
    process = subprocess.Popen([executable, "-v", "error", "-nostdin", *([] if tolerant else ["-xerror"]), "-i", str(path), "-map", "0:a:0", "-vn", "-f", raw_format, "-acodec", codec, "-"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    timed_out = __import__("threading").Event()
    def stop():
        timed_out.set()
        process.kill()
    timeout = __import__("threading").Timer(max(60, audio.info.length * 3 + 30), stop)
    timeout.start()
    digest = hashlib.sha256()
    checksum, length = hashlib.md5(), 0
    if info:
        digest.update(f"FLAC:{info.channels}:{info.bits_per_sample}:{info.sample_rate}:".encode())
    try:
        for chunk in iter(lambda: process.stdout.read(1024 * 1024), b""):
            digest.update(chunk)
            if info:
                checksum.update(chunk)
                length += len(chunk)
        result = process.wait(timeout=30)
        if timed_out.is_set() or result < 0:
            raise ValueError("音频完整性校验超时或被中断")
        if info:
            expected = info.total_samples * info.channels * (info.bits_per_sample // 8)
            # Some decoders return zero even for a truncated final frame. A missing
            # MD5 must not disable the independent STREAMINFO sample-count check.
            if (info.total_samples and length != expected) or (info.md5_signature and int(checksum.hexdigest(), 16) != info.md5_signature):
                raise ValueError("FLAC 完整采样或内置校验值不一致")
        if not tolerant and result != 0:
            raise ValueError("音频完整性验证失败")
    finally:
        timeout.cancel()
        if process.poll() is None:
            process.kill()
        process.stdout.close()
    return digest.hexdigest()


def write_artwork(path: Path, raw: bytes):
    """Replace only front artwork, preserving other embedded pictures and tags."""
    import base64
    from mutagen.flac import FLAC, Picture
    from mutagen.id3 import APIC
    from mutagen.mp4 import MP4Cover
    audio = mutagen.File(path)
    if audio is None:
        raise ValueError("无法识别封面目标格式")
    if audio.tags is None:
        audio.add_tags()
    tags = audio.tags
    if isinstance(tags, ID3):
        retained = [p for p in tags.getall("APIC") if p.type != 3]
        tags.delall("APIC")
        for picture in retained:
            tags.add(picture)
        tags.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Jiayin front", data=raw))
    elif isinstance(audio, MP4):
        tags["covr"] = [MP4Cover(raw, imageformat=MP4Cover.FORMAT_JPEG)]
    else:
        picture = Picture()
        picture.type, picture.mime, picture.data = 3, "image/jpeg", raw
        if isinstance(audio, FLAC):
            retained = [p for p in audio.pictures if p.type != 3]
            audio.clear_pictures()
            for old in retained:
                audio.add_picture(old)
            audio.add_picture(picture)
        else:
            retained = []
            for value in tags.get("metadata_block_picture", []):
                try:
                    if Picture(base64.b64decode(value)).type != 3:
                        retained.append(value)
                except (ValueError, TypeError):
                    raise ValueError("已有封面结构损坏，请先人工核对") from None
            tags["metadata_block_picture"] = retained + [base64.b64encode(picture.write()).decode("ascii")]
    audio.save()


def broken(text: str):
    return "\ufffd" in text or any(unicodedata.category(c) in {"Cs", "Cc"} and c not in "\n\r\t" for c in text)


def decode_candidates(raw: bytes):
    candidates = []
    for encoding in ("utf-8-sig", "utf-16", "gb18030", "cp932", "euc-kr"):
        try:
            value = raw.decode(encoding, "strict")
            if not broken(value) and value not in [c["text"] for c in candidates]:
                candidates.append({"encoding": encoding, "text": value})
        except (UnicodeError, LookupError):
            continue
    return candidates


def embedded_lyrics(path: Path):
    audio = mutagen.File(path)
    tags = audio.tags or {}
    tracks = []
    if isinstance(tags, ID3):
        for item in tags.getall("USLT"):
            tracks.append({"language": item.lang, "description": item.desc, "text": item.text, "synced": False})
        for item in tags.getall("SYLT"):
            if item.format != 2:
                continue
            lines = []
            for value, timestamp in item.text:
                seconds = timestamp / 1000
                lines.append(f"[{int(seconds // 60):02}:{seconds % 60:05.2f}]{value}")
            tracks.append({"language": item.lang, "description": item.desc, "text": "\n".join(lines), "synced": True})
    else:
        for value in tags.get("\xa9lyr" if isinstance(audio, MP4) else "lyrics", []):
            tracks.append({"language": "", "description": "embedded", "text": str(value), "synced": "[" in str(value)})
    return tracks


def segment(value: str):
    result = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value).strip().rstrip(". ")[:120]
    if not result or result.upper().split(".")[0] in {"CON", "PRN", "AUX", "NUL", *[f"COM{i}" for i in range(1, 10)], *[f"LPT{i}" for i in range(1, 10)]}:
        raise ValueError("目录或文件名为空或使用系统保留名称")
    return result


def target_path(row):
    m = row["metadata"]
    if not m["album_artists"] or not m["album"] or not m["title"]:
        raise ValueError("整理目录前请补齐专辑歌手、专辑和歌名")
    album = m["album"] + (f" [{m['date'][:4]}]" if m["date"] else "") + (f" [{m['album_version']}]" if m["album_version"] else "")
    number = m["track"].split("/")[0]
    disc = m["disc"].split("/")[0] or "1"
    if not number.isdigit() or not disc.isdigit():
        raise ValueError("整理目录前请确认曲序与碟号")
    filename = f"{int(disc):02}-{int(number):02} {segment(m['title'])}" + Path(row["path"]).suffix.lower()
    return "/".join([segment(" & ".join(m["album_artists"])), segment(album), filename])
