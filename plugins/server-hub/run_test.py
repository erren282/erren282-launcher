import os, subprocess, glob, shutil, sys

root = r'E:\game\gamea\mcl\HMCL\docs\plugin\server-hub'
hmcl = r'E:\game\gamea\mcl\HMCL-test\HMCL-3.16.4.jar'
fx_dir = r'E:\game\gamea\mcl\HMCL-test\.hmcl\dependencies\windows-x86_64\openjfx'
fx = glob.glob(os.path.join(fx_dir, '*.jar'))
out = os.path.join(root, 'out-test')
if os.path.isdir(out):
    shutil.rmtree(out)
os.makedirs(out, exist_ok=True)

cp = ';'.join([hmcl] + fx + [os.path.join(root, 'out')])
javac = r'E:\ZULU25\bin\javac.exe'
java = r'E:\ZULU25\bin\java.exe'

cmd = [javac, '-encoding', 'UTF-8', '-source', '17', '-target', '17',
       '-cp', cp, '-d', out, os.path.join(root, 'tools', 'BackRoutingTest.java')]
print('=== COMPILE ===')
r = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
print('exit', r.returncode)
print(r.stdout)
print(r.stderr)

if r.returncode == 0:
    run_cp = cp + ';' + out
    print('=== RUN ===')
    r2 = subprocess.run([java, '-Dfile.encoding=UTF-8', '-cp', run_cp,
                         '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', 'org.dsh.serverhub.BackRoutingTest'],
                        capture_output=True, text=True, encoding='utf-8', errors='replace')
    print('exit', r2.returncode)
    print(r2.stdout)
    print(r2.stderr)
