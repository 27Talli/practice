#!/usr/bin/env python3
"""Install Google's SDK tools from official metadata for a Java 17 CI build."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

sdk = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "offer-point-android-sdk"
sdk.mkdir(parents=True, exist_ok=True)
metadata_url = "https://dl.google.com/android/repository/repository2-1.xml"
with urllib.request.urlopen(metadata_url, timeout=60) as response:
    metadata = ET.fromstring(response.read())

def tag(element):
    return element.tag.split("}")[-1]

def child(element, name):
    return next(item for item in element if tag(item) == name)

# This stable tool release supports Java 17 and the requested Android 35 SDK.
package = next(item for item in metadata if item.get("path") == "cmdline-tools;16.0")
archives = child(package, "archives")
archive = next(item for item in archives if any(tag(c) == "host-os" and c.text == "linux" for c in item))
complete = child(archive, "complete")
relative_url = child(complete, "url").text.strip()
checksum = child(complete, "checksum")
archive_path = sdk / "command-line-tools.zip"
url = "https://dl.google.com/android/repository/" + relative_url
with urllib.request.urlopen(url, timeout=90) as response, archive_path.open("wb") as output:
    shutil.copyfileobj(response, output)
digest = hashlib.new(checksum.get("type", "sha1"), archive_path.read_bytes()).hexdigest()
if digest.lower() != checksum.text.strip().lower():
    raise RuntimeError("Android command-line tools checksum mismatch")
extract = sdk / "unpack"
with zipfile.ZipFile(archive_path) as source:
    source.extractall(extract)
tools = sdk / "cmdline-tools" / "latest"
tools.parent.mkdir(exist_ok=True)
if tools.exists():
    shutil.rmtree(tools)
shutil.move(str(extract / "cmdline-tools"), tools)
for executable in (tools / "bin").iterdir():
    executable.chmod(0o755)
environment = os.environ.copy()
environment["ANDROID_HOME"] = str(sdk)
environment["ANDROID_SDK_ROOT"] = str(sdk)
manager = str(tools / "bin" / "sdkmanager")
subprocess.run([manager, "--sdk_root=" + str(sdk), "--licenses"],
               input="y\n" * 100, text=True, env=environment, check=True, timeout=240)
subprocess.run([manager, "--sdk_root=" + str(sdk), "platforms;android-35", "build-tools;35.0.0", "platform-tools"],
               env=environment, check=True, timeout=600)
if os.environ.get("GITHUB_ENV"):
    with open(os.environ["GITHUB_ENV"], "a") as output:
        output.write(f"ANDROID_HOME={sdk}\nANDROID_SDK_ROOT={sdk}\n")
if os.environ.get("GITHUB_PATH"):
    with open(os.environ["GITHUB_PATH"], "a") as output:
        output.write(f"{tools / 'bin'}\n{sdk / 'platform-tools'}\n")
print("Android SDK 35 and Build Tools 35.0.0 are installed.")
