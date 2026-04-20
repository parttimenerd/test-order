#!/usr/bin/env python3
"""Debug script to check tree-sitter AST for annotation and other edge cases."""
import tree_sitter as ts
import tree_sitter_java as tsjava

JAVA = ts.Language(tsjava.language())
PARSER = ts.Parser(JAVA)

def dump_children(source, path, target_type=None, max_depth=5):
    src = open(path, 'rb').read()
    tree = PARSER.parse(src)
    
    def walk(node, depth=0):
        if depth > max_depth:
            return
        prefix = "  " * depth
        name = ''
        for c in node.children:
            if c.type == 'identifier':
                name = src[c.start_byte:c.end_byte].decode()
                break
        line = f"{prefix}{node.type}"
        if name:
            line += f" [{name}]"
        if target_type and node.type == target_type:
            line += " <--- TARGET"
        print(line)
        for child in node.children:
            walk(child, depth + 1)
    
    walk(tree.root_node)

# 1. Check annotation element types
print("=== Java8_RepeatingAnnotations.java ===")
dump_children(None, 'java_files/Java8_RepeatingAnnotations.java', 'annotation_type_element_declaration')

print("\n=== riskyMethod in Java8_TypeAnnotations.java ===")
# Check the throws clause parsing
src = open('java_files/Java8_TypeAnnotations.java', 'rb').read()
tree = PARSER.parse(src)
def find_method(node, name):
    if node.type == 'method_declaration':
        for c in node.children:
            if c.type == 'identifier' and src[c.start_byte:c.end_byte].decode() == name:
                return node
    for c in node.children:
        r = find_method(c, name)
        if r: return r
    return None

m = find_method(tree.root_node, 'riskyMethod')
if m:
    print(f"Found riskyMethod at {m.start_point}-{m.end_point}")
    for c in m.children:
        print(f"  {c.type}: {src[c.start_byte:c.end_byte].decode()[:80]}")

print("\n=== DeepEnum in LocalEnumEdgeCases_Java16.java ===")
src2 = open('java_files/edge_cases/LocalEnumEdgeCases_Java16.java', 'rb').read()
tree2 = PARSER.parse(src2)
def find_type(node, name, depth=0):
    if node.type in ('enum_declaration', 'class_declaration', 'record_declaration'):
        for c in node.children:
            if c.type == 'identifier' and src2[c.start_byte:c.end_byte].decode() == name:
                print(f"Found {name} at depth={depth}, type={node.type}, parent={node.parent.type if node.parent else 'none'}")
                p = node.parent
                chain = []
                while p:
                    chain.append(p.type)
                    p = p.parent
                print(f"  Parent chain: {' -> '.join(chain[:10])}")
                return
    for c in node.children:
        find_type(c, name, depth+1)
find_type(tree2.root_node, 'DeepEnum')

print("\n=== Tiny_StaticImport_Java5.java AST ===")
dump_children(None, 'java_files/edge_cases/tiny/Tiny_StaticImport_Java5.java', 'method_invocation', max_depth=8)
