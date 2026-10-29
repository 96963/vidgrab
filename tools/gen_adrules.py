#!/usr/bin/env python3
"""把 EasyList 系列的 ABP 规则压缩成 VidGrab 用的域名规则表。

用法:
    python tools/gen_adrules.py <规则目录> <输出文件>

输出格式（每行）:
    B domain          始终拦截
    T domain          仅第三方请求拦截
    A domain          例外（放行）
"""
import os
import re
import sys

HOST_RE = re.compile(r'^\|\|([a-z0-9][a-z0-9.-]*[a-z0-9])\^?', re.IGNORECASE)
SAFE_OPTIONS = {
    'third-party', 'script', 'image', 'stylesheet', 'xmlhttprequest', 'subdocument',
    'object', 'media', 'font', 'document', 'popup', 'all', 'important', 'ping',
    'websocket', 'other',
}


def parse_options(rule: str):
    """返回 (options, is_valid)。看不懂或带否定/域限定的规则直接丢弃，避免误伤。"""
    if '$' not in rule:
        return set(), True
    opts_raw = rule.split('$', 1)[1]
    opts = set()
    for opt in opts_raw.split(','):
        opt = opt.strip().lower()
        if not opt:
            continue
        if opt.startswith('~') or '=' in opt:
            return set(), False
        if opt not in SAFE_OPTIONS:
            return set(), False
        opts.add(opt)
    return opts, True


def main():
    rule_dir, out_path = sys.argv[1], sys.argv[2]
    block, third_party, allow = set(), set(), set()

    for name in ('easylist.txt', 'easyprivacy.txt', 'easylistchina.txt'):
        path = os.path.join(rule_dir, name)
        if not os.path.exists(path):
            continue
        with open(path, encoding='utf-8', errors='ignore') as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith('!'):
                    continue
                is_exception = line.startswith('@@')
                rule = line[2:] if is_exception else line
                m = HOST_RE.match(rule)
                if not m:
                    continue
                host = m.group(1).lower().strip('.')
                if '.' not in host or len(host) > 100:
                    continue
                opts, valid = parse_options(rule)
                if not valid:
                    continue
                if is_exception:
                    allow.add(host)
                elif 'third-party' in opts:
                    third_party.add(host)
                else:
                    block.add(host)

    block -= allow
    third_party -= allow

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('# 由 tools/gen_adrules.py 生成，来源 EasyList / EasyPrivacy / EasyList China\n')
        for host in sorted(block):
            fh.write('B ' + host + '\n')
        for host in sorted(third_party):
            fh.write('T ' + host + '\n')
        for host in sorted(allow):
            fh.write('A ' + host + '\n')

    print(f'block={len(block)} third_party={len(third_party)} allow={len(allow)}')
    print(f'output={out_path} ({os.path.getsize(out_path)} bytes)')


if __name__ == '__main__':
    main()
