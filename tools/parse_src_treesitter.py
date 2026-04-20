#!/usr/bin/env python3
"""Parse Java files under any 'src' directory using tree-sitter and write
structure JSON files into a mirrored 'src_parsed' directory.

Usage: /tmp/ts-venv/bin/python3 tools/parse_src_treesitter.py
"""

import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import tree_sitter as ts
import tree_sitter_java as tsjava

JAVA = ts.Language(tsjava.language())
PARSER = ts.Parser(JAVA)

ROOT = Path(__file__).resolve().parent.parent

TYPE_DECL_KINDS = {
    "class_declaration",
    "interface_declaration",
    "enum_declaration",
    "record_declaration",
    "annotation_type_declaration",
}

METHOD_KINDS = {
    "method_declaration",
    "constructor_declaration",
    "annotation_type_element_declaration",
}


def node_text(node, source: bytes) -> str:
    return source[node.start_byte:node.end_byte].decode("utf-8", errors="replace")


def extract_package(root_node, source: bytes) -> str:
    for child in root_node.children:
        if child.type == "package_declaration":
            for sub in child.children:
                if sub.type in ("scoped_identifier", "identifier"):
                    return node_text(sub, source)
    return ""


def kind_from_node_type(node_type: str) -> str:
    return {
        "class_declaration": "class",
        "interface_declaration": "interface",
        "enum_declaration": "enum",
        "record_declaration": "record",
        "annotation_type_declaration": "annotation",
    }.get(node_type, "unknown")


def find_name(node, source: bytes) -> str:
    for child in node.children:
        if child.type == "identifier":
            return node_text(child, source)
    return ""


def find_body_node(node):
    for child in node.children:
        if child.type in ("class_body", "interface_body", "enum_body", "annotation_type_body"):
            return child
        if child.type == "body":
            return child
    return None


def extract_method_signature(node, source: bytes) -> str:
    """Return the method signature (everything up to and including the opening brace or semicolon)."""
    body_node = None
    for child in node.children:
        if child.type == "block":
            body_node = child
            break
    if body_node:
        sig = source[node.start_byte:body_node.start_byte].decode("utf-8", errors="replace").rstrip()
        return sig
    # abstract / interface method — up to semicolon
    return node_text(node, source).rstrip(";").rstrip()


def extract_method_body(node, source: bytes) -> str | None:
    """Return the method body text (inside the braces), or None for abstract methods."""
    for child in node.children:
        if child.type == "block":
            full = node_text(child, source)
            # Strip outer { }
            if full.startswith("{") and full.endswith("}"):
                return full[1:-1]
            return full
    return None


def extract_initializers(body_node, source: bytes) -> list[dict]:
    """Extract static and instance initializer blocks from a type body node."""
    initializers = []
    if body_node is None:
        return initializers
    nodes_to_scan = list(body_node.children)
    # For enums, initializers can live inside enum_body_declarations
    for child in body_node.children:
        if child.type == "enum_body_declarations":
            nodes_to_scan.extend(child.children)
    for child in nodes_to_scan:
        if child.type == "static_initializer":
            # Find the block inside static_initializer
            for sub in child.children:
                if sub.type == "block":
                    body = node_text(sub, source)
                    initializers.append({"isStatic": True, "body": body})
                    break
        elif child.type == "block":
            # Instance initializer — a bare block at the type body level
            body = node_text(child, source)
            initializers.append({"isStatic": False, "body": body})
    return initializers


def extract_fields(body_node, source: bytes) -> list[dict]:
    """Extract field declarations from a type body node."""
    fields = []
    if body_node is None:
        return fields
    nodes_to_scan = list(body_node.children)
    # For enums, fields live inside enum_body_declarations
    for child in body_node.children:
        if child.type == "enum_body_declarations":
            nodes_to_scan.extend(child.children)
    for child in nodes_to_scan:
        if child.type == "field_declaration":
            # A field_declaration contains a type and one or more variable_declarators
            for sub in child.children:
                if sub.type == "variable_declarator":
                    name = find_name(sub, source)
                    if name:
                        fields.append({"name": name})
        elif child.type == "constant_declaration":
            # Constants in interfaces/annotations
            for sub in child.children:
                if sub.type == "variable_declarator":
                    name = find_name(sub, source)
                    if name:
                        fields.append({"name": name})
    return fields


def extract_methods(body_node, source: bytes) -> list[dict]:
    methods = []
    if body_node is None:
        return methods
    nodes_to_scan = list(body_node.children)
    # For enums, methods live inside enum_body_declarations
    for child in body_node.children:
        if child.type == "enum_body_declarations":
            nodes_to_scan.extend(child.children)
    for child in nodes_to_scan:
        if child.type in METHOD_KINDS:
            name = find_name(child, source)
            if child.type == "constructor_declaration":
                name = find_name(child, source) or "<init>"
            methods.append({
                "name": name,
                "signature": extract_method_signature(child, source),
                "body": extract_method_body(child, source),
            })
    return methods


def extract_types(node, source: bytes, package: str, parent_fq: str = "") -> list[dict]:
    types = []
    children_to_scan = []

    if node.type in ("program",):
        children_to_scan = node.children
    elif node.type in ("class_body", "interface_body", "enum_body", "annotation_type_body"):
        children_to_scan = list(node.children)
        # For enums, inner types can live inside enum_body_declarations
        for child in node.children:
            if child.type == "enum_body_declarations":
                children_to_scan.extend(child.children)
    elif node.type == "block":
        # Local types inside method bodies (e.g. local records)
        children_to_scan = node.children
    else:
        return types

    for child in children_to_scan:
        if child.type not in TYPE_DECL_KINDS:
            continue

        kind = kind_from_node_type(child.type)
        name = find_name(child, source)
        if not name:
            continue

        if parent_fq:
            fqname = f"{parent_fq}${name}"
        elif package:
            fqname = f"{package}.{name}"
        else:
            fqname = name

        body_node = find_body_node(child)
        body_text = node_text(body_node, source) if body_node else ""
        # Strip outer { }
        if body_text.startswith("{") and body_text.endswith("}"):
            body_text = body_text[1:-1]

        methods = extract_methods(body_node, source)
        fields = extract_fields(body_node, source)
        initializers = extract_initializers(body_node, source)
        inner_types = extract_types(body_node, source, package, fqname) if body_node else []

        # Also find local types inside method bodies (e.g. local records/enums)
        if body_node is not None:
            for method_node in body_node.children:
                if method_node.type in METHOD_KINDS:
                    for mc in method_node.children:
                        if mc.type == "block":
                            inner_types.extend(find_local_types(mc, source, package, fqname))

        types.append({
            "kind": kind,
            "name": name,
            "fqname": fqname,
            "body": body_text,
            "methods": methods,
            "fields": fields,
            "initializers": initializers,
            "inner_types": inner_types,
        })

    return types


def find_local_types(block_node, source: bytes, package: str, parent_fq: str) -> list[dict]:
    """Recursively walk all blocks inside a method body to find local type declarations."""
    types = []
    for child in block_node.children:
        if child.type in TYPE_DECL_KINDS:
            kind = kind_from_node_type(child.type)
            name = find_name(child, source)
            if name:
                fqname = f"{parent_fq}${name}" if parent_fq else name
                body_node = find_body_node(child)
                body_text = node_text(body_node, source) if body_node else ""
                if body_text.startswith("{") and body_text.endswith("}"):
                    body_text = body_text[1:-1]
                methods = extract_methods(body_node, source)
                fields = extract_fields(body_node, source)
                initializers = extract_initializers(body_node, source)
                inner_types = extract_types(body_node, source, package, fqname) if body_node else []
                types.append({
                    "kind": kind,
                    "name": name,
                    "fqname": fqname,
                    "body": body_text,
                    "methods": methods,
                    "fields": fields,
                    "initializers": initializers,
                    "inner_types": inner_types,
                })
        else:
            # Recurse into nested scopes (blocks, anonymous class bodies, etc.)
            _collect_local_types_deep(child, types, source, package, parent_fq)
    return types


# Node types that can contain nested type declarations
_SCOPE_KINDS = {"block", "class_body", "interface_body", "enum_body"}


def _collect_local_types_deep(node, types, source: bytes, package: str, parent_fq: str):
    """Walk a node's descendants looking for blocks/class bodies that may
    contain local type declarations."""
    for sub in node.children:
        if sub.type in TYPE_DECL_KINDS:
            kind = kind_from_node_type(sub.type)
            name = find_name(sub, source)
            if name:
                fqname = f"{parent_fq}${name}" if parent_fq else name
                body_node = find_body_node(sub)
                body_text = node_text(body_node, source) if body_node else ""
                if body_text.startswith("{") and body_text.endswith("}"):
                    body_text = body_text[1:-1]
                methods = extract_methods(body_node, source)
                fields = extract_fields(body_node, source)
                initializers = extract_initializers(body_node, source)
                inner_types = extract_types(body_node, source, package, fqname) if body_node else []
                types.append({
                    "kind": kind, "name": name, "fqname": fqname,
                    "body": body_text, "methods": methods, "fields": fields,
                    "initializers": initializers, "inner_types": inner_types,
                })
        elif sub.type in _SCOPE_KINDS:
            types.extend(find_local_types(sub, source, package, parent_fq))
        else:
            _collect_local_types_deep(sub, types, source, package, parent_fq)


def parse_java_file(path: Path) -> dict:
    source = path.read_bytes()
    tree = PARSER.parse(source)
    root = tree.root_node

    package = extract_package(root, source)
    types = extract_types(root, source, package)

    result = {
        "file": str(path),
        "package": package,
        "types": types,
    }

    # Check for parse errors
    if _has_error(root):
        result["has_parse_errors"] = True

    return result


def _has_error(node) -> bool:
    """Recursively check if the tree contains any ERROR nodes."""
    if node.type == "ERROR":
        return True
    for child in node.children:
        if _has_error(child):
            return True
    return False


def _process_one(args: tuple[str, str]) -> str:
    """Worker: parse one Java file and write its JSON. Returns output path."""
    java_path_str, out_file_str = args
    p = Path(java_path_str)
    out_file = Path(out_file_str)
    out_file.parent.mkdir(parents=True, exist_ok=True)
    try:
        parsed = parse_java_file(p)
    except Exception as e:
        parsed = {"file": java_path_str, "error": str(e)}
    out_file.write_text(json.dumps(parsed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return out_file_str


def main():
    skip_dirs = {"target", ".git", "__pycache__", "src_parsed"}
    tasks: list[tuple[str, str]] = []
    for dirpath, dirnames, filenames in os.walk(ROOT, followlinks=True):
        dirnames[:] = [d for d in dirnames if d not in skip_dirs]
        for fname in filenames:
            if not fname.endswith(".java"):
                continue
            p = Path(dirpath) / fname
            rel = p.relative_to(ROOT)
            out_file = ROOT / "src_parsed" / rel.parent / (p.stem + ".json")
            tasks.append((str(p), str(out_file)))

    workers = min(os.cpu_count() or 4, len(tasks) or 1)
    with ProcessPoolExecutor(max_workers=workers) as pool:
        outputs = list(pool.map(_process_one, tasks, chunksize=64))

    print(f"Wrote {len(outputs)} parsed files using {workers} workers")


if __name__ == "__main__":
    main()
