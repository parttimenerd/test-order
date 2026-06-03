package me.bechberger.testorder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Radix trie mapping Java class FQCNs to sequential integer IDs.
 * <p>
 * Exploits package-prefix redundancy for compact serialization. IDs are
 * assigned via DFS traversal so classes in the same package get contiguous IDs
 * (good for RoaringBitmap run containers).
 */
class ClassNameTrie {

	static final class Node {
		String label;
		boolean terminal;
		int classId = -1;
		final TreeMap<Character, Node> children = new TreeMap<>();

		Node(String label) {
			this.label = label;
		}
	}

	private final Node root = new Node("");
	private String[] idToName;
	private Map<String, Integer> nameToId;

	void insert(String name) {
		insertInto(root, name);
	}

	private void insertInto(Node current, String remaining) {
		if (remaining.isEmpty()) {
			current.terminal = true;
			return;
		}

		char first = remaining.charAt(0);
		Node child = current.children.get(first);

		if (child == null) {
			Node n = new Node(remaining);
			n.terminal = true;
			current.children.put(first, n);
			return;
		}

		int commonLen = commonPrefixLen(remaining, child.label);

		if (commonLen == child.label.length()) {
			// child label is full prefix of remaining
			insertInto(child, remaining.substring(commonLen));
			return;
		}

		// split child at commonLen
		Node split = new Node(child.label.substring(0, commonLen));
		current.children.put(first, split);

		child.label = child.label.substring(commonLen);
		split.children.put(child.label.charAt(0), child);

		String tail = remaining.substring(commonLen);
		if (tail.isEmpty()) {
			split.terminal = true;
		} else {
			Node n = new Node(tail);
			n.terminal = true;
			split.children.put(tail.charAt(0), n);
		}
	}

	private static int commonPrefixLen(String a, String b) {
		int len = Math.min(a.length(), b.length());
		for (int i = 0; i < len; i++) {
			if (a.charAt(i) != b.charAt(i))
				return i;
		}
		return len;
	}

	/** Assigns sequential IDs via DFS. Must be called after all insertions. */
	void assignIds() {
		List<String> names = new ArrayList<>();
		Map<String, Integer> map = new HashMap<>();
		assignIdsDfs(root, new StringBuilder(), names, map);
		idToName = names.toArray(new String[0]);
		nameToId = map;
	}

	private void assignIdsDfs(Node node, StringBuilder prefix, List<String> names, Map<String, Integer> map) {
		int prevLen = prefix.length();
		prefix.append(node.label);
		if (node.terminal) {
			node.classId = names.size();
			String fullName = prefix.toString();
			map.put(fullName, node.classId);
			names.add(fullName);
		}
		for (Node child : node.children.values()) {
			assignIdsDfs(child, prefix, names, map);
		}
		prefix.setLength(prevLen);
	}

	int size() {
		return idToName == null ? 0 : idToName.length;
	}

	int getId(String name) {
		Integer id = nameToId.get(name);
		if (id == null)
			throw new NoSuchElementException("Unknown class name: " + name);
		return id;
	}

	String getName(int id) {
		return idToName[id];
	}

	// ---- serialization ----

	void writeTo(DataOutputStream out) throws IOException {
		// Use a reusable byte buffer to reduce GC pressure from getBytes() calls
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(512);
		java.nio.charset.CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
		writeNode(out, root, buffer, encoder);
	}

	private void writeNode(DataOutputStream out, Node node, java.nio.ByteBuffer buffer,
			java.nio.charset.CharsetEncoder encoder) throws IOException {
		// Encode label into reusable buffer; fall back to direct getBytes() for labels
		// longer than the buffer capacity (avoids silent truncation on overflow).
		byte[] labelBytes;
		int labelLen;
		int maxBytes = (int) (node.label.length() * encoder.maxBytesPerChar());
		if (maxBytes <= buffer.capacity()) {
			buffer.clear();
			encoder.reset();
			java.nio.CharBuffer charBuf = java.nio.CharBuffer.wrap(node.label);
			encoder.encode(charBuf, buffer, true);
			encoder.flush(buffer);
			labelLen = buffer.position();
			buffer.flip();
			labelBytes = null; // use buffer
		} else {
			labelBytes = node.label.getBytes(StandardCharsets.UTF_8);
			labelLen = labelBytes.length;
		}

		writeVarInt(out, labelLen);
		if (labelBytes != null) {
			out.write(labelBytes, 0, labelLen);
		} else {
			out.write(buffer.array(), 0, labelLen);
		}

		// encode terminal: 0 = not terminal, otherwise classId + 1
		writeVarInt(out, node.terminal ? node.classId + 1 : 0);
		writeVarInt(out, node.children.size());
		for (Node child : node.children.values()) {
			writeNode(out, child, buffer, encoder);
		}
	}

	static ClassNameTrie readFrom(DataInputStream in) throws IOException {
		ClassNameTrie trie = new ClassNameTrie();
		trie.root.label = "";
		trie.root.terminal = false;
		trie.root.children.clear();
		readNode(in, trie.root);
		// rebuild lookup tables
		List<String> names = new ArrayList<>();
		Map<String, Integer> map = new HashMap<>();
		rebuildLookup(trie.root, new StringBuilder(), names, map);
		trie.idToName = names.toArray(new String[0]);
		trie.nameToId = map;
		return trie;
	}

	private static void readNode(DataInputStream in, Node node) throws IOException {
		int labelLen = readVarInt(in);
		byte[] labelBytes = new byte[labelLen];
		in.readFully(labelBytes);
		node.label = new String(labelBytes, StandardCharsets.UTF_8);
		int terminalMarker = readVarInt(in);
		if (terminalMarker > 0) {
			node.terminal = true;
			node.classId = terminalMarker - 1;
		}
		int childCount = readVarInt(in);
		for (int i = 0; i < childCount; i++) {
			Node child = new Node("");
			readNode(in, child);
			if (!child.label.isEmpty()) {
				node.children.put(child.label.charAt(0), child);
			}
		}
	}

	private static void rebuildLookup(Node node, StringBuilder prefix, List<String> names, Map<String, Integer> map) {
		int prevLen = prefix.length();
		prefix.append(node.label);
		if (node.terminal) {
			String name = prefix.toString();
			// ensure list is large enough (IDs may not arrive in order from trie)
			while (names.size() <= node.classId)
				names.add(null);
			names.set(node.classId, name);
			map.put(name, node.classId);
		}
		for (Node child : node.children.values()) {
			rebuildLookup(child, prefix, names, map);
		}
		prefix.setLength(prevLen);
	}

	// ---- varint encoding (unsigned, 7-bit chunks, MSB continuation) ----

	static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	static int readVarInt(DataInputStream in) throws IOException {
		int value = 0;
		int shift = 0;
		int b;
		do {
			if (shift >= 35) {
				throw new IOException("VarInt overflow: too many continuation bytes");
			}
			b = in.readByte() & 0xFF;
			value |= (b & 0x7F) << shift;
			shift += 7;
		} while ((b & 0x80) != 0);
		return value;
	}
}
