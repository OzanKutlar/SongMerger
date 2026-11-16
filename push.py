"""Build the debug APK with modern JDK (Corretto 21) and install it on a connected device.

Usage: python push.py

With one usable device connected it is used directly; with several, a numbered list is
shown to pick from. Setting ANDROID_SERIAL to a serial from 'adb devices' skips the
question. The device is chosen before the build, so the install runs unattended.
Gradle and adb write straight to this console.
"""

import os
import shutil
import subprocess
import sys
from collections import namedtuple
from pathlib import Path

JDK_CANDIDATES = [
    Path(r"C:\Users\Ozan\.jdks\corretto-21.0.6"),
    Path(r"C:\Users\Ozan\.jdks\openjdk-19"),
    Path(r"C:\Program Files\Android\Android Studio\jbr"),
]
SDK_PLATFORM_TOOLS = Path(r"C:\Users\Ozan\AppData\Local\Android\Sdk\platform-tools")

REPO_ROOT = Path(__file__).resolve().parent
GRADLEW = REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
APK = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
VERSION_CHECK_TIMEOUT_S = 30
ADB_LIST_TIMEOUT_S = 30
MAX_PROMPT_ATTEMPTS = 5
EXIT_NOT_FOUND = 127
EXIT_INTERRUPTED = 130

Device = namedtuple("Device", ["serial", "state", "model"])

STATE_HINTS = {
    "unauthorized": "unlock the phone and accept the USB debugging prompt",
    "offline": "reconnect it, or restart adb with 'adb kill-server'",
}


def log(message):
    print(f"push: {message}", flush=True)


def error(message):
    print(f"push: {message}", file=sys.stderr, flush=True)


# ---------------------------------------------------------------- JDK Setup

def java_executable(java_home):
    name = "java.exe" if os.name == "nt" else "java"
    return Path(java_home) / "bin" / name


def is_compatible_jdk(java_home):
    """True when java_home contains a JDK with version >= 17 (required by modern AGP)."""
    if not java_home:
        return False
    java = java_executable(java_home)
    if not java.is_file():
        return False
    try:
        result = subprocess.run([str(java), "-version"], capture_output=True, text=True,
                                timeout=VERSION_CHECK_TIMEOUT_S)
    except (OSError, subprocess.SubprocessError) as problem:
        error(f"cannot run {java}: {problem}")
        return False
    output = result.stderr + result.stdout
    # Java 8 reports "1.8", versions 9+ report e.g. "11.0", "17.0", "19.0", "21.0"
    if '"1.8' in output:
        return False
    return True


def build_environment():
    """Copy of the current environment with JDK 17+ active, or None when none is available."""
    env = os.environ.copy()
    current = env.get("JAVA_HOME")
    if current and is_compatible_jdk(current):
        log(f"using JAVA_HOME {current}")
        java_home = Path(current)
    else:
        java_home = None
        for candidate in JDK_CANDIDATES:
            if is_compatible_jdk(candidate):
                java_home = candidate
                break
        if java_home is None:
            return None
        log(f"setting JAVA_HOME to {java_home}")

    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")

    # Also ensure Android SDK platform-tools is on PATH if adb is not found
    if shutil.which("adb", path=env.get("PATH")) is None and SDK_PLATFORM_TOOLS.is_dir():
        env["PATH"] = str(SDK_PLATFORM_TOOLS) + os.pathsep + env.get("PATH", "")

    return env


# ---------------------------------------------------------------- Devices

def list_devices(adb, env):
    """Devices reported by 'adb devices -l', or None when adb could not be asked."""
    try:
        result = subprocess.run([adb, "devices", "-l"], capture_output=True, text=True,
                                env=env, timeout=ADB_LIST_TIMEOUT_S)
    except (OSError, subprocess.SubprocessError) as problem:
        error(f"cannot list devices: {problem}")
        return None
    if result.returncode != 0:
        error(f"'adb devices' failed: {result.stderr.strip()}")
        return None
    return parse_devices(result.stdout)


def parse_devices(text):
    """Parses 'adb devices -l' output; the header and daemon notices are skipped."""
    devices = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 2 or line.startswith("*") or line.startswith("List of devices"):
            continue
        model = next((p[len("model:"):] for p in parts[2:] if p.startswith("model:")), "")
        devices.append(Device(parts[0], parts[1], model.replace("_", " ")))
    return devices


def connection_of(serial):
    if serial.startswith("emulator-"):
        return "emulator"
    if ":" in serial or serial.startswith("adb-"):
        return "Wi-Fi"
    return "USB"


def describe(device):
    model = device.model or "unknown model"
    return f"{model} via {connection_of(device.serial)} [{device.serial}]"


def report_unusable(devices):
    for device in devices:
        if device.state == "device":
            continue
        hint = STATE_HINTS.get(device.state, "not ready")
        log(f"ignoring {describe(device)}: {device.state} ({hint})")


def from_android_serial(usable, env):
    """The device named by ANDROID_SERIAL, when it is set and connected."""
    wanted = env.get("ANDROID_SERIAL")
    if not wanted:
        return None
    match = next((d for d in usable if d.serial == wanted), None)
    if match is None:
        error(f"ANDROID_SERIAL={wanted} is not connected, choose another device")
    return match


def prompt_for_device(usable):
    """Numbered menu; returns the chosen device, or None after too many invalid answers."""
    log("connected devices:")
    for number, device in enumerate(usable, start=1):
        print(f"  {number}) {describe(device)}", flush=True)
    count = len(usable)
    for _ in range(MAX_PROMPT_ATTEMPTS):
        try:
            answer = input(f"Install on which device? [1-{count}]: ").strip()
        except EOFError:
            error("no answer given")
            return None
        if answer.isdigit() and 1 <= int(answer) <= count:
            return usable[int(answer) - 1]
        print(f"Please enter a number from 1 to {count}.", flush=True)
    error("too many invalid answers")
    return None


def choose_device(adb, env):
    """Serial of the device to install on, or None when there is none to use."""
    devices = list_devices(adb, env)
    if devices is None:
        return None
    report_unusable(devices)
    usable = [d for d in devices if d.state == "device"]
    if not usable:
        error("no usable device connected (check the cable and USB debugging)")
        return None
    device = from_android_serial(usable, env)
    if device is None and len(usable) == 1:
        device = usable[0]
    if device is None:
        device = prompt_for_device(usable)
    if device is not None:
        log(f"installing on {describe(device)}")
    return device.serial if device is not None else None


# ---------------------------------------------------------------- Commands

def run(command, env):
    """Runs a command in the repo root with its output going straight to this console."""
    log("> " + " ".join(command))
    try:
        return subprocess.run(command, cwd=REPO_ROOT, env=env).returncode
    except FileNotFoundError:
        error(f"'{command[0]}' was not found")
        return EXIT_NOT_FOUND
    except OSError as problem:
        error(f"cannot start '{command[0]}': {problem}")
        return EXIT_NOT_FOUND


def build(env):
    if not GRADLEW.is_file():
        error(f"{GRADLEW} does not exist")
        return EXIT_NOT_FOUND
    code = run([str(GRADLEW), ":app:assembleDebug"], env)
    if code != 0:
        error(f"build failed (exit code {code}), not installing")
    return code


def install(adb, serial, env):
    if not APK.is_file():
        error(f"build finished but {APK} is missing")
        return 1
    code = run([adb, "-s", serial, "install", str(APK)], env)
    if code != 0:
        error(f"install failed (exit code {code})")
    return code


def main():
    env = build_environment()
    if env is None:
        error("no compatible JDK 17+ found in JAVA_HOME or C:\\Users\\Ozan\\.jdks")
        return 1
    adb = shutil.which("adb", path=env.get("PATH"))
    if adb is None:
        error("adb was not found on PATH; install the Android platform-tools or add them to PATH")
        return EXIT_NOT_FOUND
    serial = choose_device(adb, env)
    if serial is None:
        return 1
    code = build(env)
    if code != 0:
        return code
    code = install(adb, serial, env)
    if code == 0:
        log("done")
    return code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        error("interrupted")
        sys.exit(EXIT_INTERRUPTED)
