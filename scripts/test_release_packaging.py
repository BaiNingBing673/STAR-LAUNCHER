#!/usr/bin/env python3
"""Unit tests for conservative ZIP packaging and verification scripts."""

from __future__ import annotations

import os
import struct
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path

from package_release_zip import compute_sha256, package_release_zip, write_sha256_file
from verify_release_package import (
    verify_apk_contents,
    verify_zip_and_extracted_apk,
    verify_zip_binary_structure,
)


def make_minimal_apk(path: Path) -> None:
    """Create a minimal structurally valid APK-like ZIP for unit tests."""
    with zipfile.ZipFile(path, "w", allowZip64=False) as zf:
        zf.writestr("AndroidManifest.xml", b"manifest-placeholder", compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("classes.dex", b"dex\n035\x00" + os.urandom(256), compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("resources.arsc", b"ARSC" + b"\x00" * 64, compress_type=zipfile.ZIP_STORED)
        for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
            zf.writestr(f"lib/{abi}/libpojavexec.so", b"\x7fELF" + os.urandom(64), compress_type=zipfile.ZIP_DEFLATED)


def write_streaming_dd_zip(apk_path: Path, out_zip: Path, method: int = 8) -> None:
    """Simulate GitHub Actions upload-artifact streaming ZIP with Data Descriptor (GPBF 0x0008)."""
    data = apk_path.read_bytes()
    crc = zlib.crc32(data) & 0xFFFFFFFF
    name = apk_path.name.encode("ascii")
    if method == 8:
        comp_obj = zlib.compressobj(6, zlib.DEFLATED, -zlib.MAX_WBITS)
        payload = comp_obj.compress(data) + comp_obj.flush()
    else:
        payload = data

    lfh = struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0x0008, method, 0, 0x5421, 0, 0, 0, len(name), 0)
    dd = struct.pack("<IIII", 0x08074B50, crc, len(payload), len(data))
    cd_offset = len(lfh) + len(name) + len(payload) + len(dd)
    cd = struct.pack(
        "<IHHHHHHIIIHHHHHII",
        0x02014B50,
        20,
        20,
        0x0008,
        method,
        0,
        0x5421,
        crc,
        len(payload),
        len(data),
        len(name),
        0,
        0,
        0,
        0,
        0o100644 << 16,
        0,
    )
    eocd = struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, 1, 1, len(cd) + len(name), cd_offset, 0)
    out_zip.write_bytes(lfh + name + payload + dd + cd + name + eocd)


class ReleasePackagingTests(unittest.TestCase):
    def test_conservative_stored_zip_passes_all_checks(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            apk = tmp / "STAR-Launcher-v3.0.1.apk"
            make_minimal_apk(apk)
            apk_sha = tmp / "STAR-Launcher-v3.0.1.apk.sha256"
            digest = write_sha256_file(apk, apk_sha)

            out_zip = tmp / "STAR-Launcher-v3.0.1.zip"
            package_release_zip(apk, out_zip, [apk_sha], compression="stored")

            entries = verify_zip_binary_structure(
                out_zip,
                require_stored=True,
                forbid_data_descriptor=True,
                forbid_zip64=True,
            )
            self.assertEqual([e["name"] for e in entries], [apk.name, apk_sha.name])
            for e in entries:
                self.assertEqual(e["method"], 0)
                self.assertEqual(e["flags"], 0)
                self.assertEqual(e["version_needed"], 10)

            verify_zip_and_extracted_apk(
                zip_path=out_zip,
                require_stored=True,
                expected_apk_name=apk.name,
                expected_apk_sha256=digest,
            )

    def test_rejects_streaming_deflate_with_data_descriptor(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            apk = tmp / "STAR-Launcher-v3.0.1.apk"
            make_minimal_apk(apk)
            bad_zip = tmp / "bad-dd-deflate.zip"
            write_streaming_dd_zip(apk, bad_zip, method=8)

            with self.assertRaisesRegex(ValueError, "Data Descriptor"):
                verify_zip_binary_structure(
                    bad_zip,
                    require_stored=False,
                    forbid_data_descriptor=True,
                    forbid_zip64=True,
                )

    def test_rejects_streaming_stored_with_data_descriptor(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            apk = tmp / "STAR-Launcher-v3.0.1.apk"
            make_minimal_apk(apk)
            bad_zip = tmp / "bad-dd-stored.zip"
            write_streaming_dd_zip(apk, bad_zip, method=0)

            with self.assertRaisesRegex(ValueError, "Data Descriptor"):
                verify_zip_binary_structure(
                    bad_zip,
                    require_stored=True,
                    forbid_data_descriptor=True,
                    forbid_zip64=True,
                )

    def test_rejects_zip64_extra_field(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            apk = tmp / "STAR-Launcher-v3.0.1.apk"
            make_minimal_apk(apk)
            zip64_path = tmp / "with-zip64.zip"
            with zipfile.ZipFile(zip64_path, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as zf:
                info = zipfile.ZipInfo(apk.name)
                info.extra = struct.pack("<HHQ", 0x0001, 8, apk.stat().st_size)
                zf.writestr(info, apk.read_bytes())

            with self.assertRaisesRegex(ValueError, "ZIP64"):
                verify_zip_binary_structure(
                    zip64_path,
                    require_stored=True,
                    forbid_data_descriptor=True,
                    forbid_zip64=True,
                )

    def test_rejects_compressed_resources_arsc_in_apk(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            apk = tmp / "bad-resources.apk"
            with zipfile.ZipFile(apk, "w", allowZip64=False) as zf:
                zf.writestr("AndroidManifest.xml", b"manifest", compress_type=zipfile.ZIP_DEFLATED)
                zf.writestr("classes.dex", b"dex", compress_type=zipfile.ZIP_DEFLATED)
                zf.writestr("resources.arsc", b"ARSC", compress_type=zipfile.ZIP_DEFLATED)
                for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
                    zf.writestr(f"lib/{abi}/libpojavexec.so", b"so")

            with self.assertRaisesRegex(ValueError, "resources.arsc"):
                verify_apk_contents(apk)

    def test_rejects_sha256_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            apk = tmp / "STAR-Launcher-v3.0.1.apk"
            make_minimal_apk(apk)
            out_zip = tmp / "STAR-Launcher-v3.0.1.zip"
            package_release_zip(apk, out_zip, [], compression="stored")
            self.assertEqual(len(compute_sha256(out_zip)), 64)

            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                verify_zip_and_extracted_apk(
                    zip_path=out_zip,
                    require_stored=True,
                    expected_apk_name=apk.name,
                    expected_apk_sha256="0" * 64,
                )


if __name__ == "__main__":
    unittest.main(verbosity=2)
