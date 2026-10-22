"""Build Android's java.base image with the official JDK, without a Gradle child process.
Only standard Python modules are required. Inputs are pinned to Android SDK 37.0/JDK21.
"""
import argparse, hashlib, pathlib, shutil, subprocess, zipfile

p = argparse.ArgumentParser()
p.add_argument('--jdk', required=True)
p.add_argument('--sdk', required=True)
args = p.parse_args()
jdk, sdk = pathlib.Path(args.jdk), pathlib.Path(args.sdk)
jar_candidates = list((sdk/'platforms').glob('android-37*/core-for-system-modules.jar'))
if not jar_candidates: raise SystemExit('Android SDK Platform 37.0 is required')
source = jar_candidates[0]
root = pathlib.Path(__file__).resolve().parent.parent/'local-toolchains'
container = root/'android-jdk-image'
stamp = hashlib.sha256(source.read_bytes()).hexdigest()
if (container/'sdk.sha256').exists() and (container/'sdk.sha256').read_text() == stamp:
    print('Existing Android JDK image matches the SDK')
    raise SystemExit(0)
if container.exists(): raise SystemExit('SDK changed; move the previous local-toolchains/android-jdk-image aside before rebuilding')
scratch = root/'jdk-image-build'
scratch.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(source) as z:
    packages = sorted({n.rpartition('/')[0].replace('/','.') for n in z.namelist() if n.endswith('.class') and '/' in n and not n.startswith('META-INF/')})
descriptor = scratch/'module-info.java'
descriptor.write_text('module java.base {\n' + ''.join(f'    exports {name};\n' for name in packages) + '}\n')
exe = '.exe' if __import__('os').name == 'nt' else ''
def run(tool, *params): subprocess.run([str(jdk/'bin'/(tool+exe)), *map(str, params)], check=True)
run('javac', '--system=none', f'--patch-module=java.base={source}', '-d', scratch, descriptor)
module = scratch/'module.jar'
with zipfile.ZipFile(source) as src, zipfile.ZipFile(module, 'w') as dst:
    for item in src.infolist():
        if item.filename != 'module-info.class': dst.writestr(item, src.read(item))
    dst.write(scratch/'module-info.class', 'module-info.class')
version = subprocess.check_output([str(jdk/'bin'/('jlink'+exe)), '--version'], text=True).strip()
jmods = scratch/('jmods-'+stamp[:12])
jmods.mkdir(exist_ok=True)
if not (jmods/'java.base.jmod').exists():
    run('jmod', 'create', '--module-version', version, '--target-platform', 'LINUX-OTHER', '--class-path', module, jmods/'java.base.jmod')
container.mkdir(parents=True)
run('jlink', '--module-path', jmods, '--add-modules', 'java.base', '--output', container/'jdkImage', '--disable-plugin', 'system-modules')
shutil.copyfile(jdk/'lib/jrt-fs.jar', container/'jdkImage/lib/jrt-fs.jar')
shutil.copyfile(jdk/'lib/jrt-fs.jar', container/'jrt-fs.jar')
(container/'sdk.sha256').write_text(stamp)
print('Android JDK image ready')
