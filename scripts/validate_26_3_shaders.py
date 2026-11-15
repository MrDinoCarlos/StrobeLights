"""Compile/link the 26.3 shaders against the official client includes.

Usage: python scripts/validate_26_3_shaders.py --vanilla-dir <client-assets-root>
       --glslang <glslang executable> [--output build/shader-validation]
The vanilla directory contains assets/minecraft/shaders extracted from 26.3.
"""
import argparse
import hashlib
import itertools
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vanilla-dir", type=Path, required=True)
    parser.add_argument("--glslang", type=Path, required=True)
    parser.add_argument("--download-vanilla", action="store_true",
                        help="Download the official 26.3 client, verify its Mojang SHA-1 and extract shader includes")
    parser.add_argument("--output", type=Path, default=Path("build/shader-validation"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    pack = root / "compatibility/26.3/resource-pack"
    args.output.mkdir(parents=True, exist_ok=True)
    if args.download_vanilla:
        digest = "e877b6a07acd633fb3bb475002175cec036e7b87"
        args.vanilla_dir.mkdir(parents=True, exist_ok=True)
        client = args.vanilla_dir / "client.jar"
        if not client.is_file():
            urllib.request.urlretrieve(f"https://piston-data.mojang.com/v1/objects/{digest}/client.jar", client)
        if hashlib.sha1(client.read_bytes()).hexdigest() != digest:
            raise SystemExit("Official client checksum mismatch")
        with zipfile.ZipFile(client) as archive:
            for name in archive.namelist():
                if name.startswith("assets/minecraft/shaders/") and not name.endswith("/"):
                    output = args.vanilla_dir / name
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.write_bytes(archive.read(name))

    def expand(path, stack=()):
        if path in stack:
            raise ValueError(f"Recursive shader include: {path}")
        source = path.read_text(encoding="utf-8")

        def include(match):
            namespace, name = match[1].split(":", 1)
            relative = Path("assets") / namespace / "shaders/include" / name
            candidate = pack / relative
            if not candidate.is_file():
                candidate = args.vanilla_dir / relative
            return expand(candidate, (*stack, path))

        return re.sub(r"#include\s+<([^>]+)>", include, source)

    combinations = [
        {}, {"ALPHA_CUTOUT": "0.1"}, {"GLINT": "1"},
        {"GLINT": "1", "GLINT_SPECIAL": "1"},
        {"OIT": "1", "OIT_ALPHA_ONLY": "1", "OIT_DEPTH_BOUNDS": "1"},
        {"OIT": "1", "OIT_ALPHA_ONLY": "1", "OIT_TRANSMITTANCE": "1"},
        {"OIT": "1", "OIT_ACCUMULATE": "1"},
        {"OIT": "1", "OIT_ALPHA_ONLY": "1", "OIT_TRANSMITTANCE": "1", "OIT_ADDITIVE": "1"},
        {"OIT": "1", "OIT_ACCUMULATE": "1", "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE": "1"},
    ]
    programs = ["assets/minecraft/shaders/core/item", "assets/strobelights/shaders/post/frame_lights",
                "assets/strobelights/shaders/post/frame_compose"]
    count = 0
    for program in programs:
        variants = combinations if program.endswith("core/item") else [{}]
        for backend, (index, defines) in itertools.product(["opengl", "vulkan"], enumerate(variants)):
            macros = {"OIT_COEFF_COUNT": "8", "OIT_COEFF_ATTACHMENT_COUNT": "2", "OIT_WAVELET_RANK": "2", **defines}
            if backend == "vulkan":
                macros["RENDERPEARL_DEPTH_IS_ZERO_TO_ONE"] = "1"
            stem = program.rsplit("/", 1)[1] + f"-{backend}-{index}"
            files = []
            for extension, stage in [("vsh", "vert"), ("fsh", "frag")]:
                source = expand(pack / (program + "." + extension))
                # Enable explicit binding support for glslang's standalone
                # SPIR-V validation of the game's GLSL 330 source.
                source = source.replace("#version 330", "#version 330\n#extension GL_ARB_shading_language_420pack : require", 1)
                output = args.output / (stem + "." + stage)
                output.write_text(source, encoding="utf-8")
                files.append(str(output))
            result = subprocess.run([str(args.glslang), "-V", "--auto-map-bindings", "--auto-map-locations", "-l",
                                     *[f"-D{k}={v}" for k, v in macros.items()], "-o", str(args.output / (stem + ".spv")), *files],
                                    capture_output=True, text=True)
            if result.returncode:
                raise SystemExit(f"{stem}\n{result.stdout}\n{result.stderr}")
            count += 1
    print(f"Compiled and linked {count} shader program variants (OpenGL/Vulkan, OIT and glint).")


if __name__ == "__main__":
    main()
