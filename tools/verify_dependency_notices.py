"""Verify the shaded JAR's reviewed runtime notice inventory (standard library only)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = 'META-INF/licenses/dependencies.json'


def runtime_coordinates(text):
    result = set()
    for line in re.sub(r'\x1b\[[0-9;]*m', '', text).splitlines():
        value = line.strip().split(' -- module')[0]
        if not re.match(r'^[\w.-]+:[\w.-]+:[\w.-]+:', value):
            continue
        fields = value.split(':')
        if len(fields) not in (5, 6) or fields[-1] not in ('compile', 'runtime'):
            raise ValueError('Unexpected runtime dependency record: '+value)
        result.add(':'.join([fields[0], fields[1], fields[-2]]))
    if not result:
        raise ValueError('No resolved runtime coordinates; build with clean verify first')
    return result


def verify(jar, expected, project_license):
    with zipfile.ZipFile(jar) as archive:
        manifest = json.loads(archive.read(MANIFEST))
        dependencies = manifest['dependencies']
        coordinates = [item['coordinate'] for item in dependencies]
        if len(coordinates) != len(set(coordinates)) or set(coordinates) != set(expected):
            raise ValueError('Runtime dependencies differ from the reviewed notice inventory')
        checked = set()
        for item in dependencies:
            if not item.get('license') or not item.get('files'):
                raise ValueError('Missing license evidence: '+item['coordinate'])
            for resource in item['files']:
                data = archive.read(resource['path'])
                if not data or not resource.get('source') or hashlib.sha256(data).hexdigest() != resource['sha256']:
                    raise ValueError('Missing/changed notice: '+resource['path'])
                checked.add(resource['path'])
        if archive.read('META-INF/licenses/vectorvector/LICENSE') != project_license:
            raise ValueError('Upstream project license changed or missing from the JAR')
        if not archive.read('META-INF/licenses/vectorvector/THIRD-PARTY-NOTICES.md'):
            raise ValueError('Third-party notice index missing')
    return {'checks': 'passed', 'runtime_artifacts': len(coordinates),
            'unique_notice_resources_verified': len(checked)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT/'target/vectorvector-cli.jar')
    parser.add_argument('--dependencies', type=Path, default=ROOT/'target/runtime-dependencies.txt')
    args = parser.parse_args()
    result = verify(args.jar, runtime_coordinates(args.dependencies.read_text()),
                    (ROOT/'LICENSE').read_bytes())
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
