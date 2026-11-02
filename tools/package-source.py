#!/usr/bin/env python3
"""Package reviewable source without local credentials, personal fixtures or build output."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

EXCLUDED_DIRS = {'.git', '.gradle', '.kotlin', '.idea', 'build', 'dist', 'out', '__pycache__', '.cxx', '.externalNativeBuild', 'captures', 'private-ocr-fixtures'}
EXCLUDED_SUFFIXES = {'.apk', '.aab', '.jks', '.keystore', '.p12', '.pfx', '.log', '.hprof', '.iml'}

def allowed(path):
    return not (any(p in EXCLUDED_DIRS for p in path.parts)
        or path.as_posix().startswith('docs/samples/reference/')
        or path.as_posix().startswith('app/src/androidTest/assets/private/')
        or path.name in {'local.properties', '.DS_Store', 'Thumbs.db'}
        or path.name.startswith('.env') or path.suffix.lower() in EXCLUDED_SUFFIXES)

def package(root, output, prefix):
    try:
        files = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root)
        paths = {Path(p.decode('utf-8')) for p in files.split(b'\0') if p}
    except (subprocess.CalledProcessError, FileNotFoundError):
        paths = set()
        for base, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in EXCLUDED_DIRS]
            paths.update((Path(base)/f).relative_to(root) for f in files)
    output.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for relative in sorted(paths):
            file = root/relative
            if not allowed(relative) or not file.is_file() or file.is_symlink() or file.resolve() == output.resolve():
                continue
            name = prefix.rstrip('/')+'/'+relative.as_posix()
            if relative.as_posix() == 'gradle.properties':
                lines = file.read_text(encoding='utf-8').splitlines()
                archive.writestr(name, '\n'.join(l for l in lines if not ('systemProp.' in l and any(k in l.lower() for k in ('proxy', 'password', 'user'))))+'\n')
            else:
                archive.write(file, name)
            count += 1
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('Source ZIP verification failed')
    print(f'Packaged {count} source files: {output}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--prefix', default='nbu-assistant-lite')
    args = parser.parse_args()
    package(args.root.resolve(), args.output.resolve(), args.prefix)
