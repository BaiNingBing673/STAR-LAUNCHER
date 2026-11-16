#!/usr/bin/env python3
"""Verify STAR Launcher ZIP archives and APK packages for Android compatibility.

Performs binary-level inspection of ZIP Local File Headers, Central Directory
records, End of Central Directory, Data Descriptor absence, ZIP64 absence,
compression methods, Java ZipFile/ZipInputStream extraction, native unzip -t,
internal APK integrity, ABI coverage, and optional apksigner verification.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


EOCD_SIG = b"PK\x05\x06"
ZIP64_EOCD_LOCATOR_SIG = b"PK\x06\x07"
ZIP64_EOCD_SIG = b"PK\x06\x06"
CD_SIG = 0x02014B50
LFH_SIG = 0x04034B50
DD_SIG = b"PK\x07\x08"

FLAG_ENCRYPTED = 0x0001
FLAG_DATA_DESCRIPTOR = 0x0008
FLAG_UTF8 = 0x0800

REQUIRED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
DEBUG_CERT_SHA256 = "d0d0886a0d7e3486e6627f9b8011027fe3c6b0fb09424530b6d7be14f8c2cc33"

JAVA_CHECK_SOURCE = r"""
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public class ZipCompatVerifier {
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte value : b) {
            sb.append(String.format("%02x", value & 0xff));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String zipPath = args[0];
        Map<String, String> zipFileHashes = new LinkedHashMap<>();

        try (ZipFile zf = new ZipFile(zipPath)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long total = 0;
                byte[] buf = new byte[65536];
                try (InputStream is = zf.getInputStream(entry)) {
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        md.update(buf, 0, r);
                        total += r;
                    }
                }
                if (entry.getSize() >= 0 && total != entry.getSize()) {
                    throw new IOException("ZipFile size mismatch for " + entry.getName());
                }
                zipFileHashes.put(entry.getName(), hex(md.digest()));
            }
        }

        Map<String, String> streamHashes = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipPath), 65536))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entry.getSize() < 0 || entry.getCompressedSize() < 0 || entry.getCrc() < 0) {
                    throw new IOException("ZipInputStream missing Local File Header metadata for " + entry.getName()
                        + " (size=" + entry.getSize() + ", csize=" + entry.getCompressedSize() + ", crc=" + entry.getCrc() + ")");
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long total = 0;
                byte[] buf = new byte[65536];
                int r;
                while ((r = zis.read(buf)) != -1) {
                    md.update(buf, 0, r);
                    total += r;
                }
                if (total != entry.getSize()) {
                    throw new IOException("ZipInputStream read mismatch for " + entry.getName());
                }
                streamHashes.put(entry.getName(), hex(md.digest()));
            }
        }

        if (!zipFileHashes.equals(streamHashes)) {
            throw new IOException("ZipFile vs ZipInputStream hash mismatch: " + zipFileHashes + " vs " + streamHashes);
        }
        System.out.println("JAVA_COMPAT_OK entries=" + zipFileHashes.size());
    }
}
"""


def compute_sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def compute_sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def verify_sha256_sidecar(file_path: Path, sha_path: Path) -> str:
    if not sha_path.is_file():
        raise FileNotFoundError(f"Missing SHA-256 sidecar: {sha_path}")
    first_line = sha_path.read_text(encoding="utf-8").strip().splitlines()[0]
    expected = first_line.split()[0].lower()
    actual = compute_sha256_file(file_path)
    if actual != expected:
        raise ValueError(
            f"SHA-256 mismatch for {file_path.name}: expected {expected}, got {actual}"
        )
    return actual


def find_eocd_offset(data: bytes) -> int:
    search_start = max(0, len(data) - (65535 + 22))
    idx = data.rfind(EOCD_SIG, search_start)
    if idx < 0:
        raise ValueError("Invalid ZIP: End of Central Directory (PK\\x05\\x06) not found")
    return idx


def verify_zip_binary_structure(
    zip_path: Path,
    require_stored: bool = True,
    forbid_data_descriptor: bool = True,
    forbid_zip64: bool = True,
) -> list[dict]:
    """Inspect binary ZIP structures (EOCD, CD, LFH) for conservative compatibility."""
    file_size = zip_path.stat().st_size
    if file_size < 22:
        raise ValueError(f"ZIP file too small ({file_size} bytes): {zip_path}")

    with zip_path.open("rb") as fh:
        # Read tail for EOCD, then read headers sequentially without buffering huge payloads twice.
        tail_len = min(file_size, 65535 + 64)
        fh.seek(file_size - tail_len)
        tail = fh.read(tail_len)
        eocd_rel = tail.rfind(EOCD_SIG)
        if eocd_rel < 0:
            raise ValueError("End of Central Directory (PK\\x05\\x06) not found")
        eocd_offset = (file_size - tail_len) + eocd_rel

        if forbid_zip64 and eocd_rel >= 20:
            locator_slice = tail[eocd_rel - 20 : eocd_rel]
            if locator_slice[:4] == ZIP64_EOCD_LOCATOR_SIG:
                raise ValueError("ZIP64 EOCD locator found, but ZIP64 is disabled for compatibility")

        eocd_raw = tail[eocd_rel : eocd_rel + 22]
        (
            _sig,
            disk_no,
            cd_disk,
            entries_disk,
            entries_total,
            cd_size,
            cd_offset,
            comment_len,
        ) = struct.unpack("<IHHHHIIH", eocd_raw)

        if disk_no != 0 or cd_disk != 0 or entries_disk != entries_total:
            raise ValueError("Multi-disk ZIP archives are not supported")
        if eocd_offset + 22 + comment_len != file_size:
            raise ValueError("Trailing bytes after End of Central Directory")
        if forbid_zip64 and (
            entries_total == 0xFFFF or cd_size == 0xFFFFFFFF or cd_offset == 0xFFFFFFFF
        ):
            raise ValueError("ZIP64 sentinel detected in EOCD")
        if cd_offset + cd_size != eocd_offset:
            raise ValueError(
                f"Central directory ends at {cd_offset + cd_size}, expected EOCD at {eocd_offset}"
            )

        fh.seek(cd_offset)
        cd_bytes = fh.read(cd_size)
        if len(cd_bytes) != cd_size:
            raise ValueError("Truncated Central Directory")

        pos = 0
        expected_lfh_offset = 0
        parsed_entries: list[dict] = []

        for idx in range(entries_total):
            if pos + 46 > cd_size:
                raise ValueError(f"Truncated Central Directory entry #{idx}")
            (
                cd_sig,
                ver_made,
                ver_need,
                cd_flags,
                cd_method,
                cd_mtime,
                cd_mdate,
                cd_crc,
                cd_csize,
                cd_usize,
                fname_len,
                extra_len,
                comment_len_entry,
                disk_start,
                int_attr,
                ext_attr,
                lfh_offset,
            ) = struct.unpack("<IHHHHHHIIIHHHHHII", cd_bytes[pos : pos + 46])

            if cd_sig != CD_SIG:
                raise ValueError(f"Bad Central Directory signature at entry #{idx}")

            name_start = pos + 46
            name_end = name_start + fname_len
            extra_end = name_end + extra_len
            entry_end = extra_end + comment_len_entry
            if entry_end > cd_size:
                raise ValueError(f"Truncated Central Directory metadata at entry #{idx}")

            fname_bytes = cd_bytes[name_start:name_end]
            cd_extra = cd_bytes[name_end:extra_end]
            fname = fname_bytes.decode("utf-8", errors="strict")

            if fname.startswith("/") or fname.startswith("\\") or ".." in Path(fname).parts:
                raise ValueError(f"Unsafe archive path: {fname!r}")
            if disk_start != 0:
                raise ValueError(f"Multi-disk entry unsupported: {fname}")
            if cd_flags & FLAG_ENCRYPTED:
                raise ValueError(f"Encrypted entry unsupported: {fname}")
            if forbid_data_descriptor and (cd_flags & FLAG_DATA_DESCRIPTOR):
                raise ValueError(
                    f"Entry {fname} sets Data Descriptor bit (GPBF 0x0008), forbidden for Android compatibility"
                )
            if require_stored and cd_method != 0:
                raise ValueError(
                    f"Entry {fname} uses compression method {cd_method}, expected ZIP_STORED (0)"
                )
            if cd_method == 0 and cd_csize != cd_usize:
                raise ValueError(
                    f"ZIP_STORED entry {fname} has compressed={cd_csize} != uncompressed={cd_usize}"
                )
            if forbid_zip64 and (
                cd_csize == 0xFFFFFFFF
                or cd_usize == 0xFFFFFFFF
                or lfh_offset == 0xFFFFFFFF
                or has_zip64_extra_field(cd_extra)
            ):
                raise ValueError(f"ZIP64 metadata detected in Central Directory for {fname}")

            if lfh_offset != expected_lfh_offset:
                raise ValueError(
                    f"Non-contiguous Local File Header for {fname}: offset={lfh_offset}, expected={expected_lfh_offset}"
                )

            fh.seek(lfh_offset)
            lfh_fixed = fh.read(30)
            if len(lfh_fixed) != 30:
                raise ValueError(f"Truncated Local File Header for {fname}")
            (
                lfh_sig,
                lfh_ver_need,
                lfh_flags,
                lfh_method,
                lfh_mtime,
                lfh_mdate,
                lfh_crc,
                lfh_csize,
                lfh_usize,
                lfh_fname_len,
                lfh_extra_len,
            ) = struct.unpack("<IHHHHHIIIHH", lfh_fixed)

            if lfh_sig != LFH_SIG:
                raise ValueError(f"Invalid Local File Header signature for {fname}")
            lfh_fname = fh.read(lfh_fname_len)
            lfh_extra = fh.read(lfh_extra_len)

            if lfh_fname != fname_bytes:
                raise ValueError(f"LFH filename mismatch for {fname}")
            if forbid_data_descriptor and (lfh_flags & FLAG_DATA_DESCRIPTOR):
                raise ValueError(
                    f"LFH for {fname} has Data Descriptor flag (0x{lfh_flags:04x})"
                )
            if forbid_zip64 and has_zip64_extra_field(lfh_extra):
                raise ValueError(f"ZIP64 extra field detected in LFH for {fname}")
            if (
                lfh_flags != cd_flags
                or lfh_method != cd_method
                or lfh_crc != cd_crc
                or lfh_csize != cd_csize
                or lfh_usize != cd_usize
            ):
                raise ValueError(
                    f"LFH vs CD mismatch for {fname}: "
                    f"LFH(flags={lfh_flags:#x}, method={lfh_method}, crc={lfh_crc:#x}, csize={lfh_csize}, usize={lfh_usize}) vs "
                    f"CD(flags={cd_flags:#x}, method={cd_method}, crc={cd_crc:#x}, csize={cd_csize}, usize={cd_usize})"
                )
            if cd_method == 0 and lfh_ver_need > 20:
                raise ValueError(f"Unexpected high extract_version {lfh_ver_need} for STORED entry {fname}")

            expected_lfh_offset = lfh_offset + 30 + lfh_fname_len + lfh_extra_len + lfh_csize
            pos = entry_end
            parsed_entries.append(
                {
                    "name": fname,
                    "version_needed": lfh_ver_need,
                    "flags": lfh_flags,
                    "method": lfh_method,
                    "crc32": lfh_crc,
                    "compressed_size": lfh_csize,
                    "uncompressed_size": lfh_usize,
                }
            )

        if pos != cd_size:
            raise ValueError(f"Central Directory size mismatch: consumed {pos}, total {cd_size}")
        if expected_lfh_offset != cd_offset:
            raise ValueError(
                f"Payload end ({expected_lfh_offset}) does not meet Central Directory ({cd_offset}); "
                "unexpected trailing Data Descriptor or gap bytes"
            )

        return parsed_entries


def has_zip64_extra_field(extra: bytes) -> bool:
    offset = 0
    while offset + 4 <= len(extra):
        header_id, data_len = struct.unpack("<HH", extra[offset : offset + 4])
        if header_id == 0x0001:
            return True
        offset += 4 + data_len
    return False


def verify_java_zip_compat(zip_path: Path) -> None:
    """Verify archive with both java.util.zip.ZipFile and ZipInputStream if JDK is available."""
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            cand_javac = Path(java_home) / "bin" / ("javac.exe" if os.name == "nt" else "javac")
            cand_java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
            if cand_javac.is_file() and cand_java.is_file():
                javac = str(cand_javac)
                java = str(cand_java)
    if not javac or not java:
        print("WARN: javac/java not found; skipping Java ZipInputStream compatibility check")
        return

    with tempfile.TemporaryDirectory(prefix="star_zip_java_") as tmp_dir:
        tmp = Path(tmp_dir)
        src = tmp / "ZipCompatVerifier.java"
        src.write_text(JAVA_CHECK_SOURCE, encoding="utf-8")
        subprocess.run([javac, str(src)], check=True, cwd=tmp)
        proc = subprocess.run(
            [java, "-cp", str(tmp), "ZipCompatVerifier", str(zip_path)],
            check=True,
            capture_output=True,
            text=True,
        )
        print(proc.stdout.strip())


def verify_apk_contents(
    apk_path: Path,
    expected_package: str | None = None,
    apksigner: str | None = None,
    aapt2: str | None = None,
    expected_cert_sha256: str | None = None,
    forbid_debug_cert: bool = False,
) -> None:
    """Validate internal APK ZIP structure, ABIs, resource alignment, and optional apksigner/aapt2."""
    with zipfile.ZipFile(apk_path, "r") as zf:
        bad = zf.testzip()
        if bad is not None:
            raise zipfile.BadZipFile(f"APK failed internal ZIP CRC check at {bad}")
        infos = {info.filename: info for info in zf.infolist()}

    for required in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
        if required not in infos:
            raise ValueError(f"APK missing required entry: {required}")

    res_info = infos["resources.arsc"]
    if res_info.compress_type != zipfile.ZIP_STORED:
        raise ValueError(
            f"APK resources.arsc must be uncompressed (STORED) for Android 11+, got method {res_info.compress_type}"
        )

    for abi in REQUIRED_ABIS:
        so_path = f"lib/{abi}/libpojavexec.so"
        if so_path not in infos:
            raise ValueError(f"APK missing required native library: {so_path}")

    if apksigner:
        proc = subprocess.run(
            [apksigner, "verify", "--print-certs", "--verbose", str(apk_path)],
            check=True,
            capture_output=True,
            text=True,
        )
        output = proc.stdout + "\n" + proc.stderr
        actual_sha = None
        for line in output.splitlines():
            stripped = line.strip()
            if "certificate SHA-256 digest:" in stripped and (
                stripped.startswith("Signer #1 ")
                or (stripped.startswith("V") and " Signer: certificate SHA-256 digest:" in stripped)
            ):
                actual_sha = stripped.split()[-1].strip().lower()
                break
        if not actual_sha:
            raise ValueError("Could not parse certificate SHA-256 from apksigner output")
        if forbid_debug_cert and actual_sha == DEBUG_CERT_SHA256:
            raise ValueError("Release APK is signed with the shared debug.keystore certificate")
        if expected_cert_sha256:
            norm_expected = expected_cert_sha256.lower().replace(":", "").strip()
            if actual_sha != norm_expected:
                raise ValueError(
                    f"APK cert SHA-256 mismatch: expected {norm_expected}, got {actual_sha}"
                )
        print(f"apksigner OK: cert_sha256={actual_sha}")

    if aapt2 and expected_package:
        proc = subprocess.run(
            [aapt2, "dump", "badging", str(apk_path)],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if f"package: name='{expected_package}'" not in proc.stdout:
            raise ValueError(f"Expected package '{expected_package}' not found in aapt2 badging")
        print(f"aapt2 OK: package={expected_package}")


def verify_zip_and_extracted_apk(
    zip_path: Path,
    require_stored: bool = True,
    expected_apk_name: str | None = None,
    expected_apk_sha256: str | None = None,
    expected_package: str | None = None,
    apksigner: str | None = None,
    aapt2: str | None = None,
    expected_cert_sha256: str | None = None,
    forbid_debug_cert: bool = False,
) -> None:
    """Run full verification suite on a packaged ZIP and the APK inside it."""
    entries = verify_zip_binary_structure(
        zip_path,
        require_stored=require_stored,
        forbid_data_descriptor=True,
        forbid_zip64=True,
    )
    for entry in entries:
        print(
            f"ZIP_HEADER_OK name={entry['name']} method={entry['method']} "
            f"flags=0x{entry['flags']:04x} ver={entry['version_needed']} "
            f"size={entry['uncompressed_size']:,} crc=0x{entry['crc32']:08x}"
        )

    with zipfile.ZipFile(zip_path, "r") as zf:
        bad = zf.testzip()
        if bad is not None:
            raise zipfile.BadZipFile(f"Python testzip() failed on {bad}")

    verify_java_zip_compat(zip_path)

    unzip_bin = shutil.which("unzip")
    if unzip_bin:
        subprocess.run([unzip_bin, "-t", str(zip_path)], check=True)
        print("unzip -t OK")

    zip_sha_file = Path(f"{zip_path}.sha256")
    if zip_sha_file.is_file():
        actual_zip_sha = verify_sha256_sidecar(zip_path, zip_sha_file)
        print(f"ZIP SHA-256 OK: {actual_zip_sha}")

    with tempfile.TemporaryDirectory(prefix="star_extract_verify_") as tmp_dir:
        tmp = Path(tmp_dir)
        with zipfile.ZipFile(zip_path, "r") as zf:
            zf.extractall(tmp)

        apk_candidates = sorted(tmp.glob("*.apk"))
        if len(apk_candidates) != 1:
            raise ValueError(
                f"Expected exactly 1 APK inside {zip_path.name}, found {[p.name for p in apk_candidates]}"
            )
        extracted_apk = apk_candidates[0]
        if expected_apk_name and extracted_apk.name != expected_apk_name:
            raise ValueError(
                f"Extracted APK name {extracted_apk.name!r} != expected {expected_apk_name!r}"
            )
        actual_apk_sha = compute_sha256_file(extracted_apk)
        if expected_apk_sha256 and actual_apk_sha != expected_apk_sha256.lower():
            raise ValueError(
                f"Extracted APK SHA-256 mismatch: expected {expected_apk_sha256}, got {actual_apk_sha}"
            )
        sidecar_in_zip = tmp / f"{extracted_apk.name}.sha256"
        if sidecar_in_zip.is_file():
            verify_sha256_sidecar(extracted_apk, sidecar_in_zip)

        verify_apk_contents(
            extracted_apk,
            expected_package=expected_package,
            apksigner=apksigner,
            aapt2=aapt2,
            expected_cert_sha256=expected_cert_sha256,
            forbid_debug_cert=forbid_debug_cert,
        )
        print(f"EXTRACTED_APK_OK name={extracted_apk.name} sha256={actual_apk_sha}")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify STAR Launcher ZIP archive compatibility and APK integrity."
    )
    parser.add_argument("--zip", required=True, type=Path, help="Path to ZIP archive to verify")
    parser.add_argument(
        "--allow-deflate",
        action="store_true",
        help="Allow Deflate (method 8) instead of requiring ZIP_STORED (method 0)",
    )
    parser.add_argument("--expected-apk-name", default=None, help="Expected APK filename inside ZIP")
    parser.add_argument("--expected-apk-sha256", default=None, help="Expected SHA-256 of extracted APK")
    parser.add_argument("--expected-package", default=None, help="Expected Android package name")
    parser.add_argument("--apksigner", default=None, help="Path to apksigner executable")
    parser.add_argument("--aapt2", default=None, help="Path to aapt2 executable")
    parser.add_argument("--expected-cert-sha256", default=None, help="Expected signing cert SHA-256")
    parser.add_argument(
        "--forbid-debug-cert",
        action="store_true",
        help="Fail if APK is signed with shared debug.keystore certificate",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    verify_zip_and_extracted_apk(
        zip_path=args.zip,
        require_stored=not args.allow_deflate,
        expected_apk_name=args.expected_apk_name,
        expected_apk_sha256=args.expected_apk_sha256,
        expected_package=args.expected_package,
        apksigner=args.apksigner,
        aapt2=args.aapt2,
        expected_cert_sha256=args.expected_cert_sha256,
        forbid_debug_cert=args.forbid_debug_cert,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
