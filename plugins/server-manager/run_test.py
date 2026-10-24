"""服务端管理插件的 UI 回归测试。

重点覆盖「页内不再有任何返回按钮」这件事：
`ManagerUiTest` 会在真实 JavaFX 场景里构建主视图、玩家管理页、插件管理页，
并断言这三个界面上都不存在带「返回」字样的按钮（返回只由窗口左上角的原生箭头承担）。
`PluginLoadTest` 校验插件包的清单与入口类。

用法：`python run_test.py`（在 server-manager 插件目录下执行）。
"""

import os, subprocess, glob, shutil

root = r'E:\game\gamea\mcl\HMCL\docs\plugin\server-manager'
hmcl = r'E:\game\gamea\mcl\HMCL-test\HMCL-3.16.4.jar'
fx_dir = r'E:\game\gamea\mcl\HMCL-test\.hmcl\dependencies\windows-x86_64\openjfx'
fx = glob.glob(os.path.join(fx_dir, '*.jar'))
out = os.path.join(root, 'out-test')
if os.path.isdir(out):
    shutil.rmtree(out)
os.makedirs(out, exist_ok=True)

TESTS = ['ManagerUiTest', 'PluginLoadTest']

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
    # PluginLoadTest 需要传入要校验的 jar：取 dist/ 下版本号最高的那个
    extra = []
    if name == 'PluginLoadTest':
        jars = glob.glob(os.path.join(root, 'dist', '*.jar'))
        if not jars:
            print('dist/ 下没有 jar，先执行 build.ps1')
            failed.append(name)
            continue
        extra = [max(jars, key=os.path.getmtime)]
    r2 = subprocess.run([java, '-Dfile.encoding=UTF-8',
                         '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                         '-cp', cp + ';' + out, name] + extra,
                        capture_output=True, text=True, encoding='utf-8', errors='replace')
    print('exit', r2.returncode)
    for line in (r2.stdout or '').splitlines():
        stripped = line.strip()
        if (stripped.startswith(('PASS', 'FAIL', '[PASS]', '[FAIL]', '通过', '==='))
                or '返回' in stripped
                or '插件包' in stripped
                or stripped.startswith(('id ', 'name ', 'version ', 'entry', '实例化', '继承'))):
            print(line)
    err = (r2.stderr or '')
    if 'Exception' in err or 'Error' in err:
        print(err[-3000:])
    if r2.returncode != 0:
        failed.append(name)

print()
print('SUMMARY:', 'ALL PASSED' if not failed else 'FAILED: ' + ', '.join(failed))
raise SystemExit(1 if failed else 0)
