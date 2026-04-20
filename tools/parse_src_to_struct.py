#!/usr/bin/env python3
"""Parse Java files under any 'src' directory and write structure JSON files
into a mirrored 'src_parsed' directory.

Usage: python3 tools/parse_src_to_struct.py
"""
import os
import re
import json
from pathlib import Path


def remove_comments_and_strings(src: str) -> str:
    # remove block comments
    src = re.sub(r'/\*.*?\*/', lambda m: ' ' * (m.end()-m.start()), src, flags=re.S)
    # remove line comments
    src = re.sub(r'//.*', lambda m: ' ' * (m.end()-m.start()), src)
    # remove string literals
    src = re.sub(r'"(?:\\.|[^"\\])*"', lambda m: ' ' * (m.end()-m.start()), src)
    src = re.sub(r"'(?:\\.|[^'\\])+'", lambda m: ' ' * (m.end()-m.start()), src)
    return src


def find_matching_brace(src: str, start_idx: int) -> int:
    depth = 0
    for i in range(start_idx, len(src)):
        c = src[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i
    return -1


def extract_types(src: str, package: str, parent_fq: str = None):
    types = []
    # pattern for class/interface/enum declaration
    pattern = re.compile(r"\b(class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)")
    for m in pattern.finditer(src):
        decl_idx = m.start()
        type_kind = m.group(1)
        type_name = m.group(2)
        # find first '{' after declaration
        brace_pos = src.find('{', m.end())
        if brace_pos == -1:
            continue
        end_pos = find_matching_brace(src, brace_pos)
        if end_pos == -1:
            continue
        body = src[brace_pos+1:end_pos]
        fqname = type_name if not parent_fq else parent_fq + '$' + type_name
        if package:
            fqname = package + '.' + fqname

        type_obj = {
            'kind': type_kind,
            'name': type_name,
            'fqname': fqname,
            'start': decl_idx,
            'end': end_pos,
            'body': body,
            'methods': [],
            'inner_types': []
        }

        # parse methods in body
        for mm in find_methods_in_body(body):
            type_obj['methods'].append(mm)

        # recursively find inner types
        inner = extract_types(body, package, parent_fq=(parent_fq + '$' + type_name) if parent_fq else type_name)
        type_obj['inner_types'] = inner
        types.append(type_obj)

    return types


def find_methods_in_body(body: str):
    methods = []
    # crude method signature matcher: modifiers + return/ctor + name + (params) + { or ;
    method_pattern = re.compile(r'''(
        ([A-Za-z_][A-Za-z0-9_<>\[\]\s,@]*?)\s+  # return type or modifiers
        ([A-Za-z_][A-Za-z0-9_]*)\s*                 # method name
        \([^\)]*\)\s*                            # params
        (\{ | ;)                                    # start of body or abstract
    )''', re.X)

    for m in method_pattern.finditer(body):
        sig_start = m.start()
        sig = m.group(0)
        name = m.group(3)
        rest = body[m.end()-1:]
        if rest.startswith('{'):
            # find matching brace for method body relative to body start
            method_body_start = m.end() - 1
            abs_start = method_body_start
            abs_end = find_matching_brace(body, method_body_start)
            if abs_end == -1:
                method_body = body[method_body_start+1:]
                end_pos = len(body)
            else:
                method_body = body[method_body_start+1:abs_end]
                end_pos = abs_end
            methods.append({'name': name, 'signature': sig.strip(), 'body': method_body, 'start': sig_start, 'end': end_pos})
        else:
            # abstract/interface method ending with ;
            semi = body.find(';', m.end())
            if semi != -1:
                methods.append({'name': name, 'signature': sig.strip(), 'body': None, 'start': sig_start, 'end': semi})

    return methods


def parse_java_file(path: Path):
    text = path.read_text(encoding='utf-8')
    cleaned = remove_comments_and_strings(text)
    # find package
    pkg_m = re.search(r'^\s*package\s+([A-Za-z0-9_.]+)\s*;', cleaned, flags=re.M)
    package = pkg_m.group(1) if pkg_m else ''

    types = extract_types(cleaned, package)

    return {
        'file': str(path),
        'package': package,
        'types': types
    }


def main(root: str = '.'):
    root = Path(root)
    outputs = []
    for p in root.rglob('*.java'):
        # only process files in a path segment 'src'
        parts = p.parts
        if 'src' not in parts:
            continue
        rel = Path(*parts[parts.index('src')+1:])
        out_dir = root / 'src_parsed' / rel.parent
        out_dir.mkdir(parents=True, exist_ok=True)
        try:
            parsed = parse_java_file(p)
        except Exception as e:
            parsed = {'file': str(p), 'error': str(e)}
        out_file = out_dir / (p.stem + '.json')
        out_file.write_text(json.dumps(parsed, indent=2), encoding='utf-8')
        outputs.append(str(out_file))

    print('Wrote', len(outputs), 'parsed files')


if __name__ == '__main__':
    main('.')
