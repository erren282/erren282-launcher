"""页内返回语义 + 布局/转场的回归测试。

页内已经不再画任何返回按钮（返回只由窗口左上角的原生箭头承担），所以这里同时验证两件事：

* `BackSemanticsTest` —— 返回箭头在各层视图上的分流（子视图 → 上一层，顶层 → 汇聚页）；
* `ServerListPageTest` / `PageLayoutTest` / `TransitionTest` —— 页面上确实没有返回按钮，
  并且进入/退出在线目录仍然有转场动画。

用法：`python run_test.py`（在 server-list 插件目录下执行）。
"""

import os, subprocess, glob, shutil

root = r'E:\game\gamea\mcl\HMCL\docs\plugin\server-list'
hmcl = r'E:\game\gamea\mcl\HMCL-test\HMCL-3.16.4.jar'
fx_dir = r'E:\game\gamea\mcl\HMCL-test\.hmcl\dependencies\windows-x86_64\openjfx'
fx = glob.glob(os.path.join(fx_dir, '*.jar'))
out = os.path.join(root, 'out-test')
if os.path.isdir(out):
    shutil.rmtree(out)
os.makedirs(out, exist_ok=True)

TESTS = ['BackSemanticsTest', 'ServerListPageTest', 'PageLayoutTest', 'TransitionTest']

cp = ';'.join([hmcl] + fx + [os.path.join(root, 'out')])
javac = r'E:\ZULU25\bin\javac.exe'
java = r'E:\ZULU25\bin\java.exe'

print('=== COMPILE ===')
sources = [os.path.join(root, 'tools', name + '.java') for name in TESTS]
r = subprocess.run([javac, '-encoding', 'UTF-8', '-source', '17', '-target', '17',
                    '-cp', cp, '-d', out] + sources,
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
print('exit', r.returncode)
if r.returncode != 0:
    print(r.stdout)
    print(r.stderr)
    raise SystemExit(1)

failed = []
for name in TESTS:
    print()
    print('=== RUN', name, '===')
    r2 = subprocess.run([java, '-Dfile.encoding=UTF-8',
                         '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                         '-cp', cp + ';' + out,
                         'org.dsh.serverlist.' + name],
                        capture_output=True, text=True, encoding='utf-8', errors='replace')
    print('exit', r2.returncode)
    for line in (r2.stdout or '').splitlines():
        if 'PASS' in line or 'FAIL' in line or 'RESULT' in line or '===' in line or 'FATAL' in line:
            print(line)
    err = (r2.stderr or '')
    if 'Exception' in err or 'Error' in err:
        print(err[-3000:])
    if r2.returncode != 0:
        failed.append(name)

print()
print('SUMMARY:', 'ALL PASSED' if not failed else 'FAILED: ' + ', '.join(failed))
raise SystemExit(1 if failed else 0)
