from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Metadata(StrictModel):
    title: str = ""
    artists: list[str] = Field(default_factory=list)
    artist_credit: str = ""
    album: str = ""
    album_artists: list[str] = Field(default_factory=list)
    artist_ids: list[str] = Field(default_factory=list)
    album_artist_ids: list[str] = Field(default_factory=list)
    genres: list[str] = Field(default_factory=list)
    date: str = ""
    original_date: str = ""
    track: str = ""
    disc: str = ""
    language: str = ""
    region: str = ""
    album_version: str = ""
    recording_id: str = ""
    release_id: str = ""
    release_track_id: str = ""
    compilation: bool = False


class EditRequest(StrictModel):
    ids: list[str] = Field(min_length=1, max_length=1000)
    values: dict
    lock: bool = True


class ApplyRequest(StrictModel):
    ids: list[str] = Field(min_length=1, max_length=1000)


class AnalysisRequest(StrictModel):
    ids: list[str] = Field(min_length=1, max_length=10000)
    instruction: str = Field(default="检查元数据，依据候选资料提出修正建议", max_length=2000)


class Suggestion(StrictModel):
    file_id: str = Field(description="原样复制 files 中的 file_id，不使用歌名或文件路径")
    values: dict = Field(description="拟修改字段及原始 JSON 值；只允许同一候选支持的字段。无明确修改时返回空对象")
    evidence: list[str] = Field(description="仅填写 candidates 中的 id，例如 e001。不得放说明文字、网址或虚构编号；无可引用候选时返回空数组")
    reason: str = Field(max_length=2000, description="简短说明匹配依据或冲突原因；说明文字只放在此字段")
    status: Literal["supported", "conflict", "insufficient"]


class Advice(StrictModel):
    suggestions: list[Suggestion] = Field(max_length=100)


EDIT_FIELDS = set(Metadata.model_fields)


def checked_values(values: dict) -> dict:
    if not values or not set(values) <= EDIT_FIELDS:
        raise ValueError("包含不允许修改的字段，或修改内容为空")
    result = Metadata.model_validate(values).model_dump(include=set(values))
    for key, value in result.items():
        strings = value if isinstance(value, list) else [value] if isinstance(value, str) else []
        if len(strings) > 30 or any(len(s) > 1000 or any(ord(c) < 32 for c in s) for s in strings):
            raise ValueError("标签过长或含控制字符")
        if isinstance(value, list):
            result[key] = list(dict.fromkeys(s.strip() for s in value if s.strip()))
        if key in {"date", "original_date"} and value:
            import datetime
            import re
            if not re.fullmatch(r"\d{4}(-\d{2}(-\d{2})?)?", value):
                raise ValueError("日期需使用 YYYY、YYYY-MM 或 YYYY-MM-DD")
            datetime.date.fromisoformat(value + "-01" * (2 - value.count("-")))
        if key in {"track", "disc"} and value and not __import__("re").fullmatch(r"\d{1,5}(/\d{1,5})?", value):
            raise ValueError("曲序和碟号需为数字或 数字/总数")
        if key in {"recording_id", "release_id", "release_track_id", "artist_ids", "album_artist_ids"}:
            import uuid
            for item in value if isinstance(value, list) else [value] if value else []:
                uuid.UUID(item)
    return result
