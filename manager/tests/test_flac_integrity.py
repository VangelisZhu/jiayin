"""Generated FLACs only: tolerating non-audio bytes must never tolerate changed PCM."""
import shutil
import subprocess

import pytest
from mutagen.flac import FLAC

from jiayin_manager.files import audio_hash, write_metadata


@pytest.fixture
def flac(tmp_path):
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        pytest.skip("ffmpeg required")
    def generate(bits=16):
        path = tmp_path / f"generated-{bits}.flac"
        subprocess.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1",
                        "-ac", "2", "-sample_fmt", "s16" if bits == 16 else "s32", "-bits_per_raw_sample", str(bits), str(path)], check=True, capture_output=True)
        assert FLAC(path).info.bits_per_sample == bits
        return path
    return generate


@pytest.mark.parametrize("bits", [16, 24])
def test_complete_flac_with_non_audio_trailer_can_change_tags_without_changing_pcm(flac, bits):
    path = flac(bits)
    before = audio_hash(path)
    with path.open("ab") as out:
        out.write(b"not-an-audio-frame" * 4096)
    assert audio_hash(path) == before
    write_metadata(path, {"album_artists": ["Generated corrected artist"]})
    assert audio_hash(path) == before


@pytest.mark.parametrize("alteration", ["checksum", "samples", "truncated", "no_checksum_and_bad_frames"])
def test_mismatched_or_unverifiable_flac_never_passes_tolerant_validation(flac, alteration):
    path = flac()
    raw = bytearray(path.read_bytes())
    # Native STREAMINFO is the mandatory first metadata block after fLaC.
    assert raw[:4] == b"fLaC"
    if alteration == "checksum":
        raw[26] ^= 1
    elif alteration == "samples":
        raw[25] ^= 1
    elif alteration == "truncated":
        raw = raw[:-300]
    else:
        raw[26:42] = b"\0" * 16
        raw = raw[:-300]
    path.write_bytes(raw)
    with pytest.raises(ValueError):
        audio_hash(path)


@pytest.mark.parametrize("bits", [16, 24])
def test_complete_flac_without_optional_md5_still_preserves_audio_hash(flac, bits):
    path = flac(bits)
    expected = audio_hash(path)
    raw = bytearray(path.read_bytes())
    raw[26:42] = b"\0" * 16
    path.write_bytes(raw)
    assert audio_hash(path) == expected
