#!/usr/bin/env python3
"""Package STAR Launcher APKs into conservative, Android-compatible ZIP archives.

Uses ZIP_STORED (compression method 0), disables ZIP64 extensions, avoids
streaming Data Descriptors (GPBF bit 3 = 0), and writes identical CRC-32 and
size fields into both Local File Headers and the Central Directory.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import os
import stat
import sys
import tempfile
import zipfile
from pathlib import Path


MAX_CLASSIC_ZIP_BYTES = 0xFFFFFFFF
MAX_SIGNED_32_BYTES = 0x7FFFFFFF


def compute_sha256(path: Path) -> str:
    """Return lowercase hex SHA-256 digest of a file."""
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def write_sha256_file(target: Path, sha_path: Path) -> str:
    """Write a standard sha256sum-compatible checksum file and return digest."""
    digest = compute_sha256(target)
    sha_path.write_text(f"{digest}  {target.name}\n", encoding="utf-8")
    return digest


def verify_sha256_file(target: Path, sha_path: Path) -> str:
    """Verify target matches the first digest recorded in sha_path."""
    expected_line = sha_path.read_text(encoding="utf-8").strip().splitlines()[0]
    expected = expected_line.split()[0].lower()
    actual = compute_sha256(target)
    if actual != expected:
        raise ValueError(
            f"SHA-256 mismatch for {target.name}: expected {expected}, got {actual}"
        )
    return actual


def validate_apk_archive(apk_path: Path) -> None:
    """Sanity-check the input APK before packaging it."""
    if not apk_path.is_file():
        raise FileNotFoundError(f"APK file not found: {apk_path}")
    size = apk_path.stat().st_size
    if size <= 0:
        raise ValueError(f"APK file is empty: {apk_path}")
    if size > MAX_SIGNED_32_BYTES:
        raise ValueError(
            f"APK size ({size} bytes) exceeds 2 GiB classic 32-bit ZIP compatibility limit"
        )
    with zipfile.ZipFile(apk_path, "r") as zf:
        bad_entry = zf.testzip()
        if bad_entry is not None:
            raise zipfile.BadZipFile(
                f"Input APK failed internal CRC check at entry: {bad_entry}"
            )
        names = set(zf.namelist())
        for required in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
            if required not in names:
                raise ValueError(f"Input APK missing required entry: {required}")


def safe_dos_date_time(src_path: Path) -> tuple[int, int, int, int, int, int]:
    """Return a ZIP-safe DOS timestamp tuple clamped to [1980, 2107]."""
    epoch_env = os.environ.get("SOURCE_DATE_EPOCH")
    if epoch_env:
        ts = int(epoch_env)
    else:
        ts = int(src_path.stat().st_mtime)
    dt = datetime.datetime.fromtimestamp(ts, tz=datetime.timezone.utc)
    if dt.year < 1980:
        return (1980, 1, 1, 0, 0, 0)
    if dt.year > 2107:
        return (2107, 12, 31, 23, 59, 58)
    # DOS timestamps have 2-second granularity.
    return (dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second & ~1)


def build_zip_info(src_path: Path, arcname: str) -> zipfile.ZipInfo:
    """Build a conservative ZipInfo with ASCII flat filename and 0644 permissions."""
    if "/" in arcname or "\\" in arcname or arcname in ("", ".", ".."):
        raise ValueError(f"Archive entry must be a flat relative filename: {arcname!r}")
    try:
        arcname.encode("ascii")
    except UnicodeEncodeError as exc:
        raise ValueError(f"Archive entry must be ASCII-only for max compatibility: {arcname!r}") from exc

    info = zipfile.ZipInfo(filename=arcname, date_time=safe_dos_date_time(src_path))
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3  # UNIX attributes, universally supported
    info.create_version = 20
    info.extract_version = 10  # PKZIP 1.0 (STORED)
    info.flag_bits = 0
    info.external_attr = ((stat.S_IFREG | 0o644) & 0xFFFF) << 16
    return info


def package_release_zip(
    apk_path: Path,
    out_zip: Path,
    extra_files: list[Path],
    compression: str = "stored",
) -> tuple[Path, Path]:
    """Create a conservative ZIP archive and its .sha256 sidecar atomically."""
    validate_apk_archive(apk_path)

    if compression == "stored":
        compress_mode = zipfile.ZIP_STORED
    elif compression == "deflate":
        compress_mode = zipfile.ZIP_DEFLATED
    else:
        raise ValueError(f"Unsupported compression mode: {compression}")

    entries: list[Path] = [apk_path]
    seen_names = {apk_path.name}
    for extra in extra_files:
        if not extra.is_file():
            raise FileNotFoundError(f"Extra file not found: {extra}")
        if extra.name in seen_names:
            raise ValueError(f"Duplicate archive entry name: {extra.name}")
        seen_names.add(extra.name)
        entries.append(extra)

    total_payload = sum(p.stat().st_size for p in entries)
    if total_payload >= MAX_SIGNED_32_BYTES:
        raise ValueError(
            f"Total archive payload ({total_payload} bytes) exceeds 2 GiB classic ZIP limit"
        )

    out_zip.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        prefix=f".{out_zip.name}.", suffix=".tmp", dir=str(out_zip.parent)
    )
    os.close(fd)
    tmp_path = Path(tmp_name)

    try:
        with zipfile.ZipFile(
            tmp_path,
            mode="w",
            compression=compress_mode,
            allowZip64=False,
        ) as zf:
            for src in entries:
                info = build_zip_info(src, src.name)
                if compress_mode == zipfile.ZIP_DEFLATED:
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.extract_version = 20
                data = src.read_bytes()
                zf.writestr(info, data, compress_type=info.compress_type)

        if tmp_path.stat().st_size >= MAX_CLASSIC_ZIP_BYTES:
            raise ValueError("Resulting ZIP exceeds classic 4 GiB limit")

        os.replace(tmp_path, out_zip)
    finally:
        if tmp_path.exists():
            tmp_path.unlink()

    sha_path = Path(f"{out_zip}.sha256")
    write_sha256_file(out_zip, sha_path)
    return out_zip, sha_path


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Package STAR Launcher APK into a conservative Android-compatible ZIP."
    )
    parser.add_argument("--apk", required=True, type=Path, help="Path to the APK file")
    parser.add_argument(
        "--apk-sha256",
        type=Path,
        default=None,
        help="Optional .sha256 file for the APK (verified and included in ZIP if provided)",
    )
    parser.add_argument("--out", required=True, type=Path, help="Output ZIP file path")
    parser.add_argument(
        "--extra",
        action="append",
        type=Path,
        default=[],
        help="Additional files (e.g. release notes) to include at archive root",
    )
    parser.add_argument(
        "--compression",
        choices=("stored", "deflate"),
        default="stored",
        help="Compression method (default: stored / ZIP_STORED for max Android compatibility)",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    extras = list(args.extra)
    if args.apk_sha256 is not None:
        if not args.apk_sha256.is_file():
            raise FileNotFoundError(f"APK SHA-256 file not found: {args.apk_sha256}")
        verify_sha256_file(args.apk, args.apk_sha256)
        extras.insert(0, args.apk_sha256)

    out_zip, sha_path = package_release_zip(
        apk_path=args.apk,
        out_zip=args.out,
        extra_files=extras,
        compression=args.compression,
    )
    print(f"Packaged: {out_zip} ({out_zip.stat().st_size:,} bytes)")
    print(f"Checksum: {sha_path} -> {sha_path.read_text(encoding='utf-8').strip()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
