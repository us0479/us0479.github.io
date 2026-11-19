#!/usr/bin/env python3
r"""Package the current private BankWatch project using Python 3.9+ stdlib.

Windows cmd, from the repository root:
  python scripts\package-source.py --dry-run --json
  python scripts\package-source.py --output-dir output\source-20261116

The default output is output/source. The output directory must not exist.
Creates bankwatch-source.zip, bankwatch-source.sha256 and package-report.json.
The ZIP has one BankWatch/ root and a generated PACKAGE-INFO.json inventory.
Six UI/CSV resource copies are made inside the ZIP from dist/ and samples/;
source files, existing resource copies and historical evidence stay untouched.
Only the listed current files and Java sources are selected. Old v2 artifacts,
unused initialization copies, private files, .env and build outputs are omitted.
Archive integrity checks do not run Maven, Docker, business tests or a DB.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import runpy
import stat
import sys
import zipfile


MANIFEST = "docs/verification/BankWatch_verification_manifest.json"
VERIFIER = "scripts/verify-manifest.py"
PACKAGE_INFO = "PACKAGE-INFO.json"
ARCHIVE_NAME = "bankwatch-source.zip"
SHA_NAME = "bankwatch-source.sha256"
REPORT_NAME = "package-report.json"
ARCHIVE_ROOT = "BankWatch"
FIXED_TIMESTAMP = (2000, 1, 1, 0, 0, 0)
REQUIRED = (
    ".dockerignore", ".env.example", ".gitignore", ".github/workflows/postgres-integration.yml",
    ".mvn/wrapper/maven-wrapper.properties", "mvnw", "mvnw.cmd", "pom.xml", "README.md",
    "Dockerfile", "compose.yaml", "db/schema.sql", "db/seed.sql", "db/grant_runtime.sql",
    "dist/app.js", "dist/app.css", "dist/index.html",
    "samples/events-r02.csv", "samples/events-invalid-customer.csv", "samples/events-boundaries.csv",
    "docs/BankWatch_user-scenario.md", "docs/BankWatch_erd.pdf", "docs/BankWatch_spec.xlsx",
    "docs/BankWatch_data_dictionary.md", MANIFEST,
    "scripts/init-db.sh", "scripts/sync-assets.py", "scripts/package-source.py", VERIFIER,
    "src/main/resources/application.properties",
    "src/main/java/kr/ac/bankwatch/BankWatchApplication.java",
    "src/test/java/kr/ac/bankwatch/PostgresIntegrationTest.java",
)
RESOURCE_COPIES = (
    ("dist/app.js", "src/main/resources/static/app.js"),
    ("dist/app.css", "src/main/resources/static/app.css"),
    ("dist/index.html", "src/main/resources/templates/index.html"),
    ("samples/events-r02.csv", "src/main/resources/static/samples/events-r02.csv"),
    ("samples/events-invalid-customer.csv", "src/main/resources/static/samples/events-invalid-customer.csv"),
    ("samples/events-boundaries.csv", "src/main/resources/static/samples/events-boundaries.csv"),
)
EXECUTABLES = {"mvnw", "scripts/init-db.sh"}
PASSWORD_FIELDS = {"OWNER_DB_PASSWORD", "APP_DB_PASSWORD", "BANKWATCH_ADMIN_PASSWORD",
                   "BANKWATCH_ANALYST_A_PASSWORD", "BANKWATCH_ANALYST_B_PASSWORD"}
IGNORED_JAVA_DIRS = {".git", ".local", ".env", "private", "secrets", "credentials", "target",
                     "build", "output", "node_modules", "__pycache__"}


class PackageError(Exception):
    """A preflight or archive integrity failure without sensitive file contents."""


def require(condition, message):
    if not condition:
        raise PackageError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")


def safe_name(name):
    require(isinstance(name, str) and name and not any(ord(c) < 32 or ord(c) == 127 for c in name),
            "Invalid archive-relative path")
    path, windows = PurePosixPath(name), PureWindowsPath(name)
    require(not path.is_absolute() and not windows.drive and not windows.root,
            "Absolute or drive-qualified archive path")
    require(not any(c in name for c in '\\:<>"|?*') and ".." not in path.parts and
            path.as_posix() == name and name != ".", "Noncanonical archive path")
    require(all(not p.endswith((".", " ")) and not PureWindowsPath(p).is_reserved() for p in path.parts),
            "Nonportable archive path")
    return name


def source_file(root, name):
    path = root
    for part in PurePosixPath(safe_name(name)).parts:
        path = path / part
        require(not path.is_symlink(), "Symlink in selected source: " + name)
    require(path.is_file() and path.resolve().is_relative_to(root), "Missing selected source: " + name)
    return path


def reject_walk_error(_error):
    raise PackageError("Unreadable Java source directory")


def java_sources(root, folder):
    base = root / folder
    for parent in [base, *base.parents]:
        require(not parent.is_symlink(), "Symlink in Java source directory")
        if parent == root:
            break
    require(base.is_dir(), "Missing Java source directory")
    names = set()
    for current, directories, filenames in os.walk(base, followlinks=False, onerror=reject_walk_error):
        directories[:] = sorted(d for d in directories if d.casefold() not in IGNORED_JAVA_DIRS)
        require(not any((Path(current) / d).is_symlink() for d in directories), "Symlink in Java source tree")
        for filename in filenames:
            if Path(filename).suffix == ".java":
                names.add((Path(current) / filename).relative_to(root).as_posix())
    return names


def selected_sources(root):
    names = set(REQUIRED)
    for folder in ["src/main/java", "src/test/java"]:
        names.update(java_sources(root, folder))
    require(len({name.casefold() for name in names}) == len(names), "Case-insensitive path collision")
    sources = {name: source_file(root, name).read_bytes() for name in sorted(names)}
    require(sources["scripts/package-source.py"] == Path(__file__).read_bytes(),
            "Selected project must contain this version of package-source.py")
    return sources


def check_env_example(data):
    fields = {}
    for line in data.decode("utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        require("=" in line, "Invalid environment template")
        key, value = (p.strip() for p in line.split("=", 1))
        require(key in PASSWORD_FIELDS | {"BANKWATCH_PORT"} and key not in fields,
                "Unexpected or duplicate environment template field")
        fields[key] = value
    require(set(fields) == PASSWORD_FIELDS | {"BANKWATCH_PORT"}, "Incomplete environment template")
    require(all(fields[key] in {"", "''", '\"\"'} for key in PASSWORD_FIELDS),
            "Environment template contains a populated password field")
    port = fields["BANKWATCH_PORT"]
    require(not port or port.isascii() and port.isdigit() and 1 <= int(port) <= 65535,
            "Invalid example web port")


def check_wrapper(data):
    properties = {}
    for line in data.decode("utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith(("#", "!")):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    require(properties.get("distributionType") == "only-script", "Unsupported Maven Wrapper type")
    require(properties.get("distributionUrl", "").startswith("https://"), "Wrapper must use an HTTPS distribution")
    checksum = properties.get("distributionSha256Sum", "")
    require(len(checksum) == 64 and all(c in "0123456789abcdef" for c in checksum), "Missing Wrapper checksum")


def check_evidence(root, sources):
    try:
        validator = runpy.run_path(str(source_file(root, VERIFIER)))
        manifest = validator["parse_json"](sources[MANIFEST].decode("utf-8"))
        files = validator["validate_archive"](manifest)
    except Exception:
        raise PackageError("Public inventory validation failed; run verify-manifest.py --json separately") from None
    require(all(path in sources for path, row in files.items() if row["role"] == "canonical_java"),
            "A Java source referenced by the evidence inventory is missing")
    return manifest, files


def archive_entries(sources):
    entries = {name: {"data": data, "source": name, "generated_copy": False} for name, data in sources.items()}
    for source, destination in RESOURCE_COPIES:
        require(destination not in entries, "Duplicate resource mapping")
        entries[destination] = {"data": sources[source], "source": source, "generated_copy": True}
    require(len({name.casefold() for name in entries}) == len(entries), "Archive path collision")
    for name in entries:
        safe_name(name)
    return dict(sorted(entries.items()))


def snapshot_comparison(sources, entries, files):
    differences, archive_differences = [], []
    for path, row in files.items():
        if path in sources and digest(sources[path]) != row["sha256"]:
            differences.append(path)
        if path in entries and digest(entries[path]["data"]) != row["sha256"]:
            archive_differences.append(path)
    return {"status": "CURRENT_SELECTED_FILES_MATCH_WINDOWS_REVIEW" if not differences and not archive_differences else "SELECTED_FILES_DIFFER_FROM_MANIFEST",
            "selected_source_differences": sorted(differences),
            "archive_entry_differences": sorted(archive_differences),
            "archive_paths_not_in_snapshot": sorted(set(entries) - set(files)),
            "omitted_historical_inventory_paths": sorted(set(files) - set(entries)),
            "full_project_comparison_performed": False,
            "scope": "Selected files and runtime inputs compared with current manifest; original Windows and historical raw execution bytes are preserved."}


def package_info(entries, sources, manifest, files):
    inventory = [{"path": path, "sha256": digest(item["data"]), "bytes": len(item["data"]),
                  "source_path": item["source"], "generated_resource_copy": item["generated_copy"],
                  "unix_mode": "0755" if path in EXECUTABLES else "0644"} for path, item in entries.items()]
    return {"schema_version": "1.0.0", "kind": "bankwatch.source_bundle", "archive_root": ARCHIVE_ROOT,
            "private_project": "us0479/bankwatch-db", "final_submission": False,
            "files": inventory, "inventory_excludes": [PACKAGE_INFO],
            "selected_source_files": len(sources), "generated_resource_copies": len(RESOURCE_COPIES),
            "evidence": {"manifest_path": MANIFEST, "manifest_sha256": digest(sources[MANIFEST]),
                         "archive_validation": "PASS_PUBLIC_SOURCE_FINGERPRINTS_ONLY", "raw_execution_evidence_included": False, "embedded_blobs": len(manifest["embedded_blobs"]),
                         "execution_groups": len(manifest["evidence_runs"]),
                         "snapshot_created_at_kst": manifest["snapshot"]["created_at_kst"]},
            "snapshot_comparison": snapshot_comparison(sources, entries, files),
            "packaging_policy": {"selection": "Explicit current files plus Java source trees; no broad docs/scripts/resources copy.",
                                 "generated_resources": "Six ZIP entries from canonical dist/ and samples/ only; project resources unchanged.",
                                 "sql_source": "db/ only; unused src/main/resources/init/ copies omitted.",
                                 "credentials": "No .env/private paths selected; example password fields required empty.",
                                 "zip_metadata": "Sorted paths, fixed timestamp, regular files and explicit 0644/0755 modes.",
                                 "archive_sha256": "Stored outside ZIP to avoid recursive self-hashing."},
            "execution_status": {"maven_build": "NOT_RUN", "postgresql_tests": "NOT_RUN",
                                 "application_http_browser_tests": "NOT_RUN", "windows_application_execution": "NOT_RUN",
                                 "git_commit": None, "git_push": "NOT_RUN"},
            "recorded_windows_evidence": {"JUnit_tests": manifest["summary"].get("windows_JUnit_tests"), "HTTP_requests": manifest["summary"].get("windows_HTTP_requests"), "checks": manifest["summary"].get("windows_checks"), "document_version": manifest["snapshot"].get("official_submission_version"), "submission_date": manifest["snapshot"].get("official_submission_date"), "business_tests_rerun_during_packaging": False}}


def write_archive(path, entries):
    with zipfile.ZipFile(path, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for name, item in sorted(entries.items()):
            member = zipfile.ZipInfo(ARCHIVE_ROOT + "/" + safe_name(name), date_time=FIXED_TIMESTAMP)
            member.create_system = 3
            mode = 0o755 if name in EXECUTABLES else 0o644
            member.external_attr = (stat.S_IFREG | mode) << 16
            bundle.writestr(member, item["data"], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def verify_archive(path, entries):
    with zipfile.ZipFile(path) as bundle:
        expected = [ARCHIVE_ROOT + "/" + name for name in sorted(entries)]
        require(bundle.namelist() == expected, "ZIP entry list mismatch")
        require(bundle.testzip() is None, "ZIP CRC check failed")
        for name, item in entries.items():
            member = bundle.getinfo(ARCHIVE_ROOT + "/" + name)
            mode = stat.S_IFREG | (0o755 if name in EXECUTABLES else 0o644)
            require(member.external_attr >> 16 == mode and member.date_time == FIXED_TIMESTAMP,
                    "ZIP permission/timestamp mismatch")
            require(bundle.read(member) == item["data"], "ZIP entry bytes mismatch")


def output_directory(root, requested):
    directory = (requested if requested is not None else root / "output/source").absolute()
    require(".." not in directory.parts, "Parent traversal in output directory")
    require(not any(p.is_symlink() for p in [directory, *directory.parents]), "Symlink in output directory path")
    require(not directory.exists(), "Output directory already exists; choose a new directory")
    if directory.is_relative_to(root):
        require(directory.is_relative_to(root / "output"), "Project-local output must be inside ignored output/")
    require(not root.is_relative_to(directory), "Output directory cannot contain the project root")
    return directory


def check_sources_unchanged(root, sources):
    for path, data in sources.items():
        require(source_file(root, path).read_bytes() == data, "Selected source changed during packaging: " + path)


def create_output(directory, entries, info, root, sources):
    # All input/template/evidence checks complete before creating output paths.
    directory.parent.mkdir(parents=True, exist_ok=True)
    directory.mkdir(exist_ok=False)
    created = []
    try:
        archive = directory / ARCHIVE_NAME
        created.append(archive)
        write_archive(archive, entries)
        verify_archive(archive, entries)
        check_sources_unchanged(root, sources)
        archive_digest = digest(archive.read_bytes())
        sidecar = directory / SHA_NAME
        created.append(sidecar)
        with sidecar.open("xb") as stream:
            stream.write((archive_digest + "  " + ARCHIVE_NAME + "\n").encode("ascii"))
        report = directory / REPORT_NAME
        created.append(report)
        with report.open("xb") as stream:
            stream.write(json_bytes({"archive": {"name": ARCHIVE_NAME, "sha256": archive_digest,
                "bytes": archive.stat().st_size, "entries": len(entries), "crc_and_entry_bytes": "PASS"},
                "package_info": info}))
        return archive_digest, archive.stat().st_size
    except Exception:
        for path in reversed(created):
            if path.is_file():
                path.unlink()
        directory.rmdir()
        raise


def arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1], help="Project root containing this packager version")
    parser.add_argument("--output-dir", type=Path, help="New output directory; default <root>/output/source")
    parser.add_argument("--dry-run", action="store_true", help="Validate inputs and show counts without creating outputs")
    parser.add_argument("--json", action="store_true", help="Emit a machine-readable result without source file contents")
    return parser.parse_args(argv)


def execute(args):
    root = args.root.resolve(strict=True)
    require(root.is_dir(), "Invalid project root")
    sources = selected_sources(root)
    check_env_example(sources[".env.example"])
    check_wrapper(sources[".mvn/wrapper/maven-wrapper.properties"])
    manifest, files = check_evidence(root, sources)
    entries = archive_entries(sources)
    info = package_info(entries, sources, manifest, files)
    entries[PACKAGE_INFO] = {"data": json_bytes(info), "source": None, "generated_copy": False}
    directory = output_directory(root, args.output_dir)
    result = {"status": "PASS", "mode": "dry-run" if args.dry_run else "package",
              "output_directory": str(directory), "archive_root": ARCHIVE_ROOT,
              "selected_source_files": len(sources), "generated_resource_copies": len(RESOURCE_COPIES),
              "archive_entries": len(entries), "embedded_evidence_blobs": len(manifest["embedded_blobs"]),
              "evidence_archive_validation": "PASS", "source_files_modified": False,
              "business_tests_executed": False, "final_submission": False,
              "snapshot_comparison": info["snapshot_comparison"]}
    if not args.dry_run:
        archive_digest, size = create_output(directory, entries, info, root, sources)
        result.update(archive_sha256=archive_digest, archive_bytes=size)
    return result


def main(argv=None):
    args = arguments(argv)
    try:
        result = execute(args)
    except (PackageError, OSError, ValueError, TypeError, KeyError, OverflowError, RuntimeError,
            zipfile.BadZipFile, zipfile.LargeZipFile) as error:
        message = str(error) if isinstance(error, PackageError) else "Input/output or package validation failed"
        if args.json:
            print(json.dumps({"status": "FAIL", "error": message, "business_tests_executed": False}))
        else:
            print("FAIL: " + message, file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(result, ensure_ascii=True))
    else:
        print("PASS: {mode}; sources={selected_source_files}; ZIP entries={archive_entries}; "
              "generated resources={generated_resource_copies}; business tests executed=false".format(**result))
        print("Output: " + result["output_directory"])
        if "archive_sha256" in result:
            print("ZIP SHA-256: " + result["archive_sha256"])
        print("Public edition excludes private raw logs; packaging does not execute Java/PostgreSQL or submit coursework.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
