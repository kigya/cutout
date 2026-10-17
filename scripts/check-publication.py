#!/usr/bin/env python3
"""Reject local/private and generated files from a prospective publication."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAX_FILE_SIZE = 100 * 1024 * 1024

BLOCKED_DIRS = {
    ".agents", ".cursor", ".codex", ".serena", ".p-build-app", ".local-private",
    ".kotlin", ".gradle", ".idea", ".cxx", ".expo", ".turbo", ".metro-cache",
    ".pytest_cache", "__pycache__", ".bundle", ".swiftpm", ".cache", ".npm", ".yarn",
    "node_modules", "build", "coverage", "Pods", "Carthage", "DerivedData", "xcuserdata", "target",
}
MODEL_SUFFIXES = {
    ".onnx", ".tflite", ".pt", ".pth", ".pb", ".weights", ".safetensors", ".gguf",
}
SIGNING_SUFFIXES = {
    ".keystore", ".jks", ".p12", ".pfx", ".mobileprovision", ".provisionprofile",
    ".key", ".p8", ".pem",
}
PERSONAL_WORKFLOW_MARKERS = (
    "personal workflow skills:",
    ".agents/skills/p-build-app",
    ".agents/skills/p-find-ideas",
    ".agents/skills/p-prepare-google-play",
    ".agents/skills/p-capture-lesson",
    ".agents/skills/p-create-readme",
)


def git_bytes(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], cwd=ROOT)


def candidate_paths(staged_only: bool) -> list[str]:
    if staged_only:
        raw = git_bytes("diff", "--cached", "--name-only", "--diff-filter=ACMRT", "-z")
        return sorted({part.decode("utf-8", "surrogateescape") for part in raw.split(b"\0") if part})

    # Include all tracked files and non-ignored untracked files. Ignored credentials
    # are still checked when forcibly staged because they are tracked at that point.
    tracked = git_bytes("ls-files", "-z")
    untracked = git_bytes("ls-files", "--others", "--exclude-standard", "-z")
    return sorted({part.decode("utf-8", "surrogateescape") for part in (tracked + b"\0" + untracked).split(b"\0") if part})


def staged_size(path: str) -> int | None:
    try:
        value = subprocess.check_output(["git", "cat-file", "-s", f":{path}"], cwd=ROOT, stderr=subprocess.DEVNULL)
        return int(value.strip())
    except (subprocess.CalledProcessError, ValueError):
        return None


def candidate_content(path: str, staged_only: bool) -> bytes:
    if staged_only:
        try:
            return subprocess.check_output(["git", "show", f":{path}"], cwd=ROOT, stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            return b""
    file_path = ROOT / path
    if not file_path.is_file():
        return b""
    try:
        return file_path.read_bytes()
    except OSError:
        return b""


def block_reason(path: str, staged_only: bool) -> str | None:
    normalized = path.replace("\\", "/")
    parts = [part for part in normalized.split("/") if part]
    lower_parts = [part.lower() for part in parts]
    name = parts[-1] if parts else normalized
    lower_name = name.lower()

    if any(part in BLOCKED_DIRS for part in lower_parts):
        return "private or generated directory"
    if any(lower_parts[index:index + 2] in (["ios", "vendor"], ["vendor", "bundle"]) for index in range(len(lower_parts) - 1)):
        return "generated iOS or Bundler dependency directory"
    if lower_name == "agents.md":
        return "local agent instructions"
    if lower_name == ".gitignore":
        content = candidate_content(path, staged_only).decode("utf-8", "ignore").lower().replace("\\", "/")
        if any(marker in content for marker in PERSONAL_WORKFLOW_MARKERS):
            return "Personal workflow skill rules in a local ignore file"
    if lower_parts and (lower_parts[0] in {"docs", "prd"}):
        return "local-only documentation or product research"
    if lower_name in {"google-services.json", "googleservice-info.plist", "firebase.json", ".firebaserc"}:
        return "Firebase project configuration"
    if lower_name == ".xcode.env.local":
        return "local Xcode environment override"
    if lower_name == ".ds_store":
        return "generated Finder metadata"
    if lower_name == ".env" or (lower_name.startswith(".env.") and lower_name != ".env.example"):
        return "environment file"
    if lower_name.endswith((".log", ".log.1", ".log.gz")):
        return "private or generated log"
    if Path(lower_name).suffix in SIGNING_SUFFIXES:
        return "signing credential or key material"
    if lower_name in {"id_rsa", "id_ed25519", "private_key", "privatekey"}:
        return "signing credential or key material"
    # This one licensed, first-party weight file is required for Cutout's offline
    # inference. Other model weights are not publishable through this gate.
    if Path(lower_name).suffix in MODEL_SUFFIXES and path != "app/src/main/assets/isnet.onnx":
        return "model weights are not publishable source"

    file_path = ROOT / path
    index_size = staged_size(path)
    working_size: int | None = None
    if file_path.is_file():
        try:
            working_size = file_path.stat().st_size
        except OSError:
            pass
    if staged_only:
        size = index_size
    else:
        sizes = [item for item in (index_size, working_size) if item is not None]
        size = max(sizes) if sizes else None
    if size is not None and size > MAX_FILE_SIZE:
        return f"file exceeds 100 MiB ({size / (1024 * 1024):.1f} MiB)"
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true", help="inspect only staged additions and modifications")
    parser.add_argument("--dry-run", action="store_true", help="explicitly state that this check does not modify files")
    args = parser.parse_args()

    try:
        paths = candidate_paths(args.staged)
    except (OSError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: cannot enumerate Git publication candidates: {exc}", file=sys.stderr)
        return 2

    blocked = [(path, block_reason(path, args.staged)) for path in paths]
    blocked = [(path, reason) for path, reason in blocked if reason]
    mode = "staged candidates" if args.staged else "tracked and non-ignored candidates"
    print(f"Publication check ({mode}; read-only{' dry-run' if args.dry_run else ''}): {len(paths)} file(s) inspected")
    if blocked:
        print(f"BLOCKED: {len(blocked)} file(s) need review before publication:")
        for path, reason in blocked:
            print(f"  {path}: {reason}")
        return 1
    print("PASS: no blocked paths, credentials, unapproved model weights, or oversized files found")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
