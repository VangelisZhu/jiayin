"""Independent public metadata adapters. No Plex framework or bundled plugin code."""
import concurrent.futures
import hashlib
import html
import json
import re
import time

import httpx

from .store import stamp


class MusicSources:
    def __init__(self, store, transport=None):
        self.store, self.transport = store, transport

    def get(self, url, params=None, source="netease"):
        headers = {"User-Agent": "JiayinMetadata/1.0", "Referer": "https://y.qq.com/" if source == "qq" else "https://music.163.com/"}
        with httpx.Client(timeout=10, transport=self.transport, trust_env=False, follow_redirects=False) as client:
            response = client.get(url, params=params, headers=headers)
            response.raise_for_status()
            if len(response.content) > 4 * 1024 * 1024:
                raise ValueError("来源响应过大")
            value = response.text.strip()
            if not value.startswith("{"):
                value = value[value.find("{"):value.rfind("}") + 1]
            result = json.loads(value)
            if result.get("code", 0) not in (0, 200):
                raise ValueError("来源拒绝请求")
            return result

    def search(self, query, kind="song", source="all", refresh=False):
        sources = ("qq", "netease") if source == "all" else (source,)
        result, errors = [], []
        def fetch(name):
            key = hashlib.sha256(json.dumps([name, kind, query], ensure_ascii=False).encode()).hexdigest()
            cached = self.store.get("source-cache", key)
            if cached and not refresh and stamp() - cached["at"] < 86400:
                return cached["items"]
            items = self.qq(query, kind) if name == "qq" else self.netease(query, kind)
            for item in items:
                item["cover_url"] = item.get("cover_url", "").replace("http://", "https://", 1)
                item["id"] = hashlib.sha256((name + ":" + kind + ":" + item["source_id"]).encode()).hexdigest()
                item["source"], item["kind"], item["at"] = name, kind, stamp()
                self.store.put("music-candidate", item["id"], item)
            self.store.put("source-cache", key, {"at": stamp(), "items": items})
            return items
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            pending = {pool.submit(fetch, name): name for name in sources}
            for future in concurrent.futures.as_completed(pending):
                try:
                    result.extend(future.result())
                except (httpx.HTTPError, ValueError, KeyError, TypeError):
                    errors.append({"source": pending[future], "message": "来源暂不可用，可使用另一来源或手动编辑"})
        return {"items": result, "errors": errors}

    @staticmethod
    def clean(value):
        return html.unescape(re.sub(r"<[^>]*>", "", str(value or ""))).strip()

    def netease(self, query, kind):
        key, type_id = {"song": ("songs", 1), "album": ("albums", 10), "artist": ("artists", 100)}[kind]
        rows = self.get("https://music.163.com/api/search/get/web", {"s": query, "type": type_id, "limit": 12}).get("result", {}).get(key, [])
        items = []
        for row in rows[:12]:
            album = row.get("album", {})
            artists = row.get("artists", [row["artist"]] if row.get("artist") else [])
            values = {"artists": [self.clean(a["name"]) for a in artists]}
            if kind == "song":
                values.update(title=self.clean(row["name"]), album=self.clean(album.get("name")))
            elif kind == "album":
                values = {"album": self.clean(row["name"]), "album_artists": values["artists"]}
            else:
                values = {"artists": [self.clean(row["name"])]}
            items.append({"source_id": str(row["id"]), "title": self.clean(row["name"]), "values": values,
                          "duration": row.get("duration", 0) / 1000, "cover_url": row.get("picUrl") or album.get("picUrl", ""),
                          "url": "https://music.163.com/#/" + kind + "?id=" + str(row["id"])})
        return items

    def qq(self, query, kind):
        # Smartbox supports all three objects without an audio download endpoint.
        body = self.get("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg", {"key": query, "format": "json"}, "qq")
        rows = body.get("data", {}).get({"song": "song", "album": "album", "artist": "singer"}[kind], {}).get("itemlist", [])
        items = []
        for row in rows[:12]:
            mid = str(row.get("mid", ""))
            if not re.fullmatch(r"[a-zA-Z0-9]+", mid):
                continue
            title = self.clean(row.get("name"))
            singer = self.clean(row.get("singer"))
            values = {"title": title, "artist_credit": singer} if kind == "song" else {"album": title} if kind == "album" else {"artists": [title]}
            # Do not split unstructured singer credits on punctuation.
            items.append({"source_id": mid, "title": title, "values": {k: v for k, v in values.items() if v},
                          "duration": 0, "cover_url": "", "url": "https://y.qq.com/n/ryqq/" + {"song": "songDetail", "album": "albumDetail", "artist": "singer"}[kind] + "/" + mid})
        return items

    def detail(self, candidate_id):
        try:
            return self._detail(candidate_id)
        except (httpx.HTTPError, KeyError, TypeError, ValueError):
            item = self.store.get("music-candidate", candidate_id)
            if not item:
                raise ValueError("候选不存在，请重新搜索") from None
            return {**item, "warnings": ["完整资料暂不可用，当前仅显示已有候选；请核对版本，或使用另一来源"]}

    def _detail(self, candidate_id):
        item = self.store.get("music-candidate", candidate_id)
        if not item:
            raise ValueError("候选已过期，请重新搜索")
        item = dict(item)
        source, kind, sid = item["source"], item["kind"], item["source_id"]
        if source == "netease":
            if kind == "song":
                body = self.get("https://music.163.com/api/song/detail/", {"ids": json.dumps([int(sid)])})
                row = body["songs"][0]
                album = row.get("album", {})
                item["values"] = {"title": row["name"], "artists": [a["name"] for a in row.get("artists", [])], "album": album.get("name", "")}
                item["duration"], item["cover_url"] = row.get("duration", 0) / 1000, album.get("picUrl", "")
                lyrics = self.get("https://music.163.com/api/song/lyric", {"id": sid, "lv": 1, "tv": 1})
                item["lyrics"] = html.unescape(lyrics.get("lrc", {}).get("lyric", ""))
                item["translation"] = html.unescape(lyrics.get("tlyric", {}).get("lyric", ""))
            elif kind == "album":
                row = self.get("https://music.163.com/api/album/" + sid, {"ext": "true", "id": sid, "offset": 0, "total": "true", "limit": 100})["album"]
                item["values"] = {"album": row["name"], "album_artists": [a["name"] for a in row.get("artists", [])]}
                if row.get("publishTime"):
                    item["values"]["date"] = time.strftime("%Y-%m-%d", time.gmtime(row["publishTime"] / 1000))
                item["cover_url"] = row.get("picUrl", "")
                item["description"] = self.clean(row.get("description"))
            else:
                row = self.get("https://music.163.com/api/v1/artist/" + sid)["artist"]
                item["description"], item["cover_url"] = self.clean(row.get("briefDesc")), row.get("picUrl", "")
        elif kind == "album":
            row = self.get("https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg", {"albummid": sid, "format": "json"}, "qq")["data"]
            item["values"] = {"album": row["name"], "album_artists": [row["singername"]]}
            if re.fullmatch(r"\d{4}-\d{2}-\d{2}", row.get("aDate", "")):
                item["values"]["date"] = row["aDate"]
            if isinstance(row.get("genre"), str) and row["genre"].strip():
                item["values"]["genres"] = [row["genre"].strip()]
            language = {"国语": "zho", "粤语": "yue", "英语": "eng", "日语": "jpn", "韩语": "kor", "纯音乐": "zxx"}.get(row.get("lan"))
            if language:
                item["values"]["language"] = language
            item["description"] = self.clean(row.get("desc"))
            item["cover_url"] = "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + sid + ".jpg"
        elif kind == "song":
            # The direct QQ endpoint supplies structured performers and duration.
            body = self.get("https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg", {"songmid": sid, "format": "json"}, "qq")
            row = body["data"][0]
            album = row.get("album", {})
            item["values"] = {"title": row.get("title") or row.get("name"), "artists": [a["name"] for a in row.get("singer", [])], "album": album.get("title") or album.get("name", "")}
            item["duration"] = row.get("interval", 0)
            mid = album.get("mid", "")
            if re.fullmatch(r"[a-zA-Z0-9]+", mid):
                item["cover_url"] = "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + mid + ".jpg"
            lyric = self.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg", {"songmid": sid, "format": "json", "nobase64": 1}, "qq")
            item["lyrics"], item["translation"] = html.unescape(lyric.get("lyric", "")), html.unescape(lyric.get("trans", ""))
        elif kind == "artist":
            row = self.get("https://c.y.qq.com/v8/fcg-bin/fcg_v8_singer_detail_cp.fcg", {"singermid": sid, "format": "json"}, "qq")
            item["description"] = self.clean(row.get("singerBrief"))
            item["cover_url"] = "https://y.gtimg.cn/music/photo_new/T001R500x500M000" + sid + ".jpg"
        item["cover_url"] = item.get("cover_url", "").replace("http://", "https://", 1)
        self.store.put("music-candidate", candidate_id, item)
        return item
