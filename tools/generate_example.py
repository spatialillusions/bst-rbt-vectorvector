"""Generate a tiny contour archive using only committed repository data (JDK 21 required)."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java', help='JDK 21 java executable')
    parser.add_argument('--decoder', choices=('java', 'native'), default='java',
                        help='WebP decoder; native requires a supported platform')
    parser.add_argument('--out', type=Path, default=ROOT / 'out/example-contours')
    a = parser.parse_args()
    output = a.out.resolve()
    if output.exists():
        parser.error('Output already exists; choose a fresh --out directory')
    jar = ROOT / 'target/vectorvector-cli.jar'
    if not jar.is_file():
        parser.error('Build first: mvn verify (Windows: ./build-jdk21.ps1 -MavenArgs verify)')
    subprocess.run([a.java, '-Xmx512m', '-Dvectorvector.webpDecoder='+a.decoder,
        '-jar', str(jar), '--profile', 'rbt',
        '--dataFormat', 'mapterhorn', '--pmtiles', str(ROOT / 'src/test/resources/pmtiles/fixture.pmtiles'),
        '--bbox=-16.70,63.98,-16.65,64.02', '-minZ', '9', '-maxZ', '10',
        '--parallelism', '1', '-f', 'mbtiles', '-o', str(output)], check=True, cwd=ROOT)
    print(output / 'contours.mbtiles')

if __name__ == '__main__':
    main()
