from __future__ import annotations

import re
import subprocess
import tempfile
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
SOURCE_SVG = ROOT / "shared" / "branding" / "ic_fluent_clipboard_arrow_right_24_filled.svg"
ANDROID_DRAWABLE = ROOT / "android-app" / "app" / "src" / "main" / "res" / "drawable"
ANDROID_RES = ROOT / "android-app" / "app" / "src" / "main" / "res"
WINDOWS_ASSETS = ROOT / "windows-app" / "src" / "ClipboardSync.App" / "Assets"

BACKGROUND_COLOR = "#113A5C"
FOREGROUND_COLOR = "#FFFFFF"
ICON_SIZE = 1024
ANDROID_MIPMAP_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
BROWSER_CANDIDATES = [
    Path(r"C:\Program Files (x86)\Microsoft\EdgeCore\147.0.3912.60\msedge.exe"),
    Path(r"C:\Program Files (x86)\Microsoft\EdgeWebView\Application\147.0.3912.60\msedge.exe"),
    Path(r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"),
]


def extract_path_data(svg_text: str) -> str:
    match = re.search(r'<path[^>]*d="([^"]+)"', svg_text)
    if not match:
        raise RuntimeError("Unable to find SVG path data in source icon.")
    return match.group(1)


def build_monochrome_svg(path_data: str, fill: str) -> str:
    return f"""<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<path d="{path_data}" fill="{fill}"/>
</svg>
"""


def write_android_vector(path: Path, path_data: str, fill: str, size_dp: int) -> None:
    path.write_text(
        f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size_dp}dp"
    android:height="{size_dp}dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:translateX="22"
        android:translateY="22"
        android:scaleX="2.6666667"
        android:scaleY="2.6666667">
        <path
            android:fillColor="{fill}"
            android:pathData="{path_data}" />
    </group>
</vector>
""",
        encoding="utf-8",
    )


def write_background(path: Path, color: str) -> None:
    path.write_text(
        f"""<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="{color}" />
</shape>
""",
        encoding="utf-8",
    )


def locate_browser() -> Path:
    for candidate in BROWSER_CANDIDATES:
        if candidate.exists():
            return candidate
    raise RuntimeError("No supported headless browser was found to render the Windows app icon.")


def render_windows_icon(path_data: str) -> Image.Image:
    WINDOWS_ASSETS.mkdir(parents=True, exist_ok=True)
    icon_png = WINDOWS_ASSETS / "AppIcon.png"
    icon_ico = WINDOWS_ASSETS / "AppIcon.ico"
    preview_png = ROOT / "shared" / "branding" / "app-icon-preview.png"
    browser = locate_browser()

    html = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <style>
    html, body {{
      margin: 0;
      width: 1024px;
      height: 1024px;
      overflow: hidden;
      background: transparent;
    }}
    body {{
      display: grid;
      place-items: center;
      background: transparent;
      font-family: sans-serif;
    }}
    .tile {{
      width: 920px;
      height: 920px;
      border-radius: 220px;
      background: {BACKGROUND_COLOR};
      display: grid;
      place-items: center;
    }}
    .glyph {{
      width: 560px;
      height: 560px;
      display: block;
    }}
  </style>
</head>
<body>
  <div class="tile">
    <svg class="glyph" viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
      <path d="{path_data}" fill="{FOREGROUND_COLOR}" />
    </svg>
  </div>
</body>
</html>
"""

    with tempfile.TemporaryDirectory() as temp_dir:
        temp_path = Path(temp_dir)
        html_path = temp_path / "icon.html"
        screenshot_path = temp_path / "app-icon.png"
        html_path.write_text(html, encoding="utf-8")
        subprocess.run(
            [
                str(browser),
                "--headless",
                "--disable-gpu",
                f"--screenshot={screenshot_path}",
                f"--window-size={ICON_SIZE},{ICON_SIZE}",
                html_path.as_uri(),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        image = Image.open(screenshot_path).convert("RGBA")
        image.save(icon_png)
        image.save(preview_png)
        image.save(icon_ico, sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
        return image


def write_android_raster_icons(image: Image.Image) -> None:
    for folder_name, size in ANDROID_MIPMAP_SIZES.items():
        target_dir = ANDROID_RES / folder_name
        target_dir.mkdir(parents=True, exist_ok=True)
        resized = image.resize((size, size), Image.LANCZOS)
        resized.save(target_dir / "ic_launcher.png")
        resized.save(target_dir / "ic_launcher_round.png")


def main() -> None:
    svg_text = SOURCE_SVG.read_text(encoding="utf-8")
    path_data = extract_path_data(svg_text)

    ANDROID_DRAWABLE.mkdir(parents=True, exist_ok=True)
    write_android_vector(ANDROID_DRAWABLE / "ic_launcher_foreground.xml", path_data, FOREGROUND_COLOR, 108)
    write_android_vector(ANDROID_DRAWABLE / "ic_notification.xml", path_data, "#FFFFFF", 24)
    write_background(ANDROID_DRAWABLE / "ic_launcher_background.xml", BACKGROUND_COLOR)
    rendered_icon = render_windows_icon(path_data)
    write_android_raster_icons(rendered_icon)


if __name__ == "__main__":
    main()
