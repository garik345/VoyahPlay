#!/usr/bin/env python3
"""Prepare a scoped three-way update; never execute fetched upstream code or overwrite conflicts."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def git(*args, env=None, data=None, check=True):
    return subprocess.run(['git', *args], input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, env=env, check=check)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--target', default='refs/remotes/diplay/main')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    root = Path(git('rev-parse', '--show-toplevel').stdout.decode().strip())
    os.chdir(root)
    if git('status', '--porcelain').stdout.strip():
        raise SystemExit('Use a clean checkout; commit or stash your changes first.')
    config_path = Path('.github/diplay-upstream.json')
    config = json.loads(config_path.read_text())
    base = config['baseline']
    if not re.fullmatch(r'[0-9a-f]{40}', base):
        raise SystemExit('baseline must be a full commit SHA')
    target = git('rev-parse', '--verify', args.target + '^{commit}').stdout.decode().strip()
    if git('merge-base', '--is-ancestor', base, target, check=False).returncode:
        raise SystemExit('Upstream is not a descendant of the reviewed baseline. Manual review required.')
    prefixes = config['paths']
    if not prefixes or any(not p.startswith('shared/src/') or not p.endswith('/') or '..' in p for p in prefixes):
        raise SystemExit('Invalid scoped path configuration')
    changed = [p.decode() for p in git('diff', '--name-only', '--no-renames', '-z', base, target).stdout.split(b'\0') if p]
    selected = [p for p in changed if any(p.startswith(prefix) for prefix in prefixes)]
    excluded = [p for p in changed if p not in selected]
    blocked = [p for p in selected if Path(p).suffix.lower() in {'.pk8','.p7b','.pem','.key','.p12','.pfx','.jks','.keystore','.apk','.aab'}]
    if blocked:
        raise SystemExit('Forbidden file types in upstream scope: ' + ', '.join(blocked))
    # A reviewed manual resolution is reusable only for the exact baseline,
    # incoming blob and committed local blob. No path is excluded unconditionally.
    resolved = []
    for path, resolution in config.get('reviewed_resolutions', {}).items():
        if path not in selected or resolution.get('baseline') != base:
            continue
        incoming = git('rev-parse', '--verify', f'{target}:{path}', check=False)
        local = git('rev-parse', '--verify', f'HEAD:{path}', check=False)
        if incoming.returncode or local.returncode:
            continue
        if (incoming.stdout.decode().strip() == resolution.get('upstream_blob') and
                local.stdout.decode().strip() == resolution.get('merged_blob')):
            resolved.append(path)
    pathspecs = [*prefixes, *[':(exclude,literal)' + path for path in resolved]]
    patch = git('diff', '--binary', '--full-index', '--no-renames', base, target, '--', *pathspecs).stdout
    conflicts = []
    with tempfile.TemporaryDirectory(prefix='voyah-upstream-') as tmp:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(tmp)/'index'))
        git('read-tree', 'HEAD', env=env)
        if patch:
            result = git('apply', '--3way', '--cached', env=env, data=patch, check=False)
            if result.returncode:
                conflicts = [p.decode() for p in git('diff', '--name-only', '--diff-filter=U', '-z', env=env).stdout.split(b'\0') if p]
                if not conflicts:
                    conflicts = ['Patch could not be applied; inspect upstream.patch manually.']
        candidate = b'' if conflicts else git('diff', '--cached', '--binary', '--full-index', env=env).stdout
    status = 'conflict' if conflicts else ('ready' if selected else 'no-scoped-changes')
    lines = ['# DiPlay → VoyahPlay: review required', '', f'Baseline: `{base}`', f'Target: `{target}`',
             f'Status: **{status}**', '',
             'This is a scoped update, not a complete upstream merge. Never auto-merge it.',
             'Excluded files may contain required dependencies, Gradle changes or UI integration.', '',
             '## Selected paths', '', *[f'- `{p}`' for p in selected], '',
             '## Verified manual resolutions (exact blob match; kept unchanged)', '',
             *[f'- `{p}`' for p in resolved], '', '## Conflicts', '',
             *[f'- `{p}`' for p in conflicts], '', '## Changes outside the automatic scope', '',
             *[f'- `{p}`' for p in excluded], '', '## Before merging', '',
             '- [ ] Resolve conflicts and inspect changes outside scope for dependencies.',
             '- [ ] Preserve Voyah integrations, applicationId, branding and local authentication.',
             '- [ ] Run Android checks on this exact branch/commit.',
             '- [ ] Test USB/Wi-Fi reconnect, Siri, calls, music and cluster navigation on the vehicle.',
             '- [ ] For a conflict report: apply the patch manually and advance baseline only after completing the scoped update.', '']
    report = '\n'.join(lines)
    # Reports are generated only after all merge work has happened in a temporary index.
    if not args.dry_run:
        if candidate:
            git('apply', '--index', data=candidate)
        Path('docs/DIPLAY-UPSTREAM-REVIEW.md').write_text(report)
        if not conflicts:
            config['baseline'] = target
            config_path.write_text(json.dumps(config, indent=2) + '\n')
        # Artifact outside the committed tree; no upstream code gets executed here.
        out = Path(os.environ.get('RUNNER_TEMP', tempfile.gettempdir()))/'voyah-upstream-review'
        out.mkdir(exist_ok=True)
        (out/'upstream.patch').write_bytes(patch)
        (out/'review.md').write_text(report)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'status={status}\ntarget={target}\n')
    print(report)


if __name__ == '__main__':
    main()
