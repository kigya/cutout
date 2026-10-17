#!/usr/bin/env python3
"""Semantic Android smoke helpers for Cutout; never installs or resets the app."""

from __future__ import annotations

import argparse
import datetime as dt
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
EVIDENCE_ROOT = ROOT / "docs" / "quality" / "evidence"
STALE_PACKAGE = "app.erase.background"
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class SmokeError(RuntimeError):
    pass


class Device:
    def __init__(self, serial: str, package: str) -> None:
        if not serial:
            raise SmokeError("Pass --serial explicitly; this harness never selects a device for you.")
        if not package:
            raise SmokeError("Pass --package explicitly.")
        if package == STALE_PACKAGE:
            raise SmokeError(f"Refusing stale package {STALE_PACKAGE}; use the Cutout package dev.kigya.cutout.")
        if not re.fullmatch(r"[A-Za-z0-9_.]+", package):
            raise SmokeError(f"Invalid Android package name: {package!r}")
        self.serial = serial
        self.package = package
        self.adb = shutil.which("adb")
        if not self.adb:
            raise SmokeError("adb was not found on PATH. Install Android platform-tools first.")
        self._verify_target()

    def run(self, *args: str, timeout: int = 25, binary: bool = False) -> str | bytes:
        proc = subprocess.run(
            [self.adb, "-s", self.serial, *args],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
        if proc.returncode:
            error = proc.stderr.decode("utf-8", "replace").strip()
            raise SmokeError(f"adb {' '.join(args)} failed ({proc.returncode}): {error or 'no diagnostic'}")
        return proc.stdout if binary else proc.stdout.decode("utf-8", "replace")

    def _verify_target(self) -> None:
        listing = subprocess.run([self.adb, "devices"], capture_output=True, text=True, timeout=10)
        rows = {line.split()[0]: line.split()[1] for line in listing.stdout.splitlines()[1:] if len(line.split()) >= 2}
        if rows.get(self.serial) != "device":
            present = ", ".join(f"{key}={value}" for key, value in rows.items()) or "none"
            raise SmokeError(f"Target {self.serial!r} is not an online adb device (connected: {present}).")
        qemu = str(self.run("shell", "getprop", "ro.kernel.qemu")).strip()
        if qemu != "1" or not self.serial.startswith("emulator-"):
            raise SmokeError(f"Refusing non-emulator target {self.serial!r} (ro.kernel.qemu={qemu!r}).")
        installed = str(self.run("shell", "pm", "path", self.package)).strip()
        if not installed.startswith("package:"):
            raise SmokeError(f"Package {self.package!r} is not installed on {self.serial}.")

    def hierarchy(self) -> ET.Element:
        remote = f"/data/local/tmp/cutout-smoke-{os.getpid()}.xml"
        self.run("shell", "uiautomator", "dump", remote, timeout=40)
        raw = str(self.run("shell", "cat", remote))
        self.run("shell", "rm", "-f", remote)
        start = raw.find("<?xml")
        if start < 0:
            start = raw.find("<hierarchy")
        if start < 0:
            raise SmokeError(f"uiautomator did not return an XML hierarchy: {raw[:300]!r}")
        try:
            return ET.fromstring(raw[start:])
        except ET.ParseError as exc:
            raise SmokeError(f"Could not parse uiautomator XML: {exc}") from exc

    def matches(self, selector: argparse.Namespace, root: ET.Element | None = None) -> list[ET.Element]:
        root = root if root is not None else self.hierarchy()
        attr = selector.attr
        value = selector.value
        if not value:
            raise SmokeError("Provide a semantic locator, such as --text, --description, or --resource-id.")
        return [
            node for node in root.iter("node")
            if (value in node.attrib.get(attr, "") if selector.contains else node.attrib.get(attr, "") == value)
        ]

    @staticmethod
    def describe(nodes: Iterable[ET.Element]) -> str:
        lines = []
        for node in nodes:
            a = node.attrib
            label = a.get("text") or a.get("content-desc") or a.get("resource-id") or "<unlabeled>"
            lines.append(f"{label!r} clickable={a.get('clickable')} enabled={a.get('enabled')} bounds={a.get('bounds')}")
        return "\n".join(lines)

    def capture_hierarchy(self, destination: Path) -> None:
        destination.write_bytes(ET.tostring(self.hierarchy(), encoding="utf-8", xml_declaration=True))

    def screenshot(self, destination: Path) -> None:
        destination.write_bytes(self.run("exec-out", "screencap", "-p", binary=True))


def evidence_dir(serial: str, label: str) -> Path:
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    safe_label = re.sub(r"[^A-Za-z0-9_-]+", "-", label).strip("-") or "capture"
    path = EVIDENCE_ROOT / f"{stamp}-{serial}-{safe_label}"
    path.mkdir(parents=True, exist_ok=False)
    return path


def add_locator(parser: argparse.ArgumentParser) -> None:
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--text")
    group.add_argument("--description")
    group.add_argument("--resource-id")
    parser.add_argument("--contains", action="store_true", help="Match a substring instead of the full accessible value.")


def locator_namespace(args: argparse.Namespace) -> argparse.Namespace:
    options = (("text", "text"), ("description", "content-desc"), ("resource_id", "resource-id"))
    selected = [(attr, getattr(args, field, None)) for field, attr in options if getattr(args, field, None) is not None]
    if len(selected) != 1:
        raise SmokeError("Select exactly one semantic locator: --text, --description, or --resource-id.")
    args.attr, args.value = selected[0]
    if not args.value:
        raise SmokeError("Semantic locator value cannot be empty.")
    return args


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Exact adb emulator serial (for example emulator-5554).")
    parser.add_argument("--package", required=True, help="Installed Cutout package (for example dev.kigya.cutout).")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("launch", help="Launch the app's main activity; does not stop, install, or reset it.")

    inspect = sub.add_parser("inspect", help="Print visible semantic labels and optionally capture private evidence.")
    inspect.add_argument("--capture", action="store_true", help="Save screenshot and hierarchy under ignored docs/quality/evidence/.")
    inspect.add_argument("--label", default="screen", help="Short evidence folder label.")

    assertion = sub.add_parser("assert", help="Assert a semantic node is visible, or absent with --absent.")
    add_locator(assertion)
    assertion.add_argument("--absent", action="store_true")
    assertion.add_argument("--timeout", type=float, default=0.0, help="Seconds to wait for the assertion (max 60).")

    tap = sub.add_parser("tap", help="Tap a semantic UI node; prefers a clickable ancestor if needed.")
    add_locator(tap)
    tap.add_argument("--index", type=int, default=0, help="Which matching node to tap when labels repeat.")

    swipe = sub.add_parser("swipe", help="Swipe within a semantic target, using fractions of its accessible bounds.")
    add_locator(swipe)
    swipe.add_argument("--index", type=int, default=0)
    swipe.add_argument("--from", dest="start", nargs=2, type=float, required=True, metavar=("X", "Y"))
    swipe.add_argument("--to", dest="end", nargs=2, type=float, required=True, metavar=("X", "Y"))
    swipe.add_argument("--duration-ms", type=int, default=450)

    sub.add_parser("back", help="Send Android Back to return from system settings or a picker.")
    sub.add_parser("overlay-state", help="Print the target app's SYSTEM_ALERT_WINDOW app-op (no mutation).")
    sub.add_parser("open-settings", help="Open Android system Settings as the foreground app for bubble coverage.")
    return parser


def do_assert(device: Device, args: argparse.Namespace) -> None:
    deadline = time.monotonic() + max(0.0, min(args.timeout, 60.0))
    last: list[ET.Element] = []
    while True:
        root = device.hierarchy()
        last = device.matches(locator_namespace(args), root)
        found = bool(last)
        if found != args.absent:
            print("PASS: " + ("found" if found else "absent") + f" {args.value!r}")
            if last:
                print(device.describe(last))
            return
        if time.monotonic() >= deadline:
            expectation = "absent" if args.absent else "visible"
            raise SmokeError(f"Expected semantic node {args.value!r} to be {expectation}; last hierarchy has no match.")
        time.sleep(0.5)


def do_tap(device: Device, args: argparse.Namespace) -> None:
    root = device.hierarchy()
    matches = device.matches(locator_namespace(args), root)
    if not matches:
        raise SmokeError(f"No semantic node matched {args.value!r}. Run inspect to see current labels.")
    if args.index < 0 or args.index >= len(matches):
        raise SmokeError(f"Match index {args.index} is out of range; found {len(matches)} matching nodes.")
    node = matches[args.index]
    # A label may be on a TextView nested inside the Pressable. Select the nearest clickable ancestor.
    parents: dict[ET.Element, ET.Element] = {child: parent for parent in root.iter("node") for child in parent}
    while node.attrib.get("clickable") != "true" and node in parents:
        node = parents[node]
    bounds = BOUNDS_RE.fullmatch(node.attrib.get("bounds", ""))
    if not bounds:
        raise SmokeError(f"Matched node has no usable screen bounds: {device.describe([node])}")
    left, top, right, bottom = map(int, bounds.groups())
    x, y = (left + right) // 2, (top + bottom) // 2
    device.run("shell", "input", "tap", str(x), str(y))
    print(f"Tapped semantic match {args.value!r} at ({x}, {y}).")


def do_swipe(device: Device, args: argparse.Namespace) -> None:
    matches = device.matches(locator_namespace(args))
    if not matches:
        raise SmokeError(f"No semantic swipe target matched {args.value!r}. Run inspect first.")
    if args.index < 0 or args.index >= len(matches):
        raise SmokeError(f"Match index {args.index} is out of range; found {len(matches)} matching nodes.")
    bounds = BOUNDS_RE.fullmatch(matches[args.index].attrib.get("bounds", ""))
    if not bounds:
        raise SmokeError("Swipe target has no usable screen bounds.")
    left, top, right, bottom = map(int, bounds.groups())
    def point(fractions: list[float]) -> tuple[int, int]:
        if any(part < 0.0 or part > 1.0 for part in fractions):
            raise SmokeError("Swipe fractions must be between 0 and 1.")
        return left + round((right-left)*fractions[0]), top + round((bottom-top)*fractions[1])
    start = point(args.start)
    end = point(args.end)
    if not 50 <= args.duration_ms <= 10000:
        raise SmokeError("Swipe duration must be between 50 and 10000 ms.")
    device.run("shell", "input", "swipe", str(start[0]), str(start[1]), str(end[0]), str(end[1]), str(args.duration_ms))
    print(f"Swiped semantic target {args.value!r} from {start} to {end}.")


def main() -> int:
    args = make_parser().parse_args()
    try:
        device = Device(args.serial, args.package)
        if args.command == "launch":
            device.run("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", device.package)
            print(f"Launched {device.package} on {device.serial}; no install, force-stop, or data reset was performed.")
        elif args.command == "inspect":
            root = device.hierarchy()
            print(device.describe(root.iter("node")) or "No accessible nodes returned.")
            if args.capture:
                output = evidence_dir(device.serial, args.label)
                device.capture_hierarchy(output / "hierarchy.xml")
                device.screenshot(output / "screen.png")
                print(f"Saved private local evidence under {output.relative_to(ROOT)}")
        elif args.command == "assert":
            do_assert(device, args)
        elif args.command == "tap":
            do_tap(device, args)
        elif args.command == "swipe":
            do_swipe(device, args)
        elif args.command == "back":
            device.run("shell", "input", "keyevent", "KEYCODE_BACK")
            print("Sent Android Back.")
        elif args.command == "overlay-state":
            print(str(device.run("shell", "appops", "get", device.package, "SYSTEM_ALERT_WINDOW")).strip())
        elif args.command == "open-settings":
            device.run("shell", "am", "start", "-a", "android.settings.SETTINGS")
            print("Opened Android Settings; verify the Cutout bubble remains visible over this foreground app.")
        return 0
    except (SmokeError, subprocess.TimeoutExpired) as exc:
        print(f"SMOKE ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
