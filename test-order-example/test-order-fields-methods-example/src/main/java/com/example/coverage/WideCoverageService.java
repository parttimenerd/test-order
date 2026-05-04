package com.example.coverage;

import java.util.*;

/**
 * Service with many methods to test method coverage in tests. Different tests
 * exercise different subsets of methods.
 */
public class WideCoverageService {

	private List<String> items = new ArrayList<>();
	private int accessCount = 0;
	private Map<String, String> metadata = new HashMap<>();

	// Methods are grouped by what they manipulate

	// Item list methods (5 methods)
	public void addItem(String item) {
		// MODIFIED: Added comment
		items.add(item);
		accessCount++;
	}

	public void removeItem(String item) {
		items.remove(item);
		accessCount++;
	}

	public int getItemCount() {
		accessCount++;
		return items.size();
	}

	public boolean containsItem(String item) {
		accessCount++;
		return items.contains(item);
	}

	public List<String> getAllItems() {
		accessCount++;
		return new ArrayList<>(items);
	}

	// Metadata methods (4 methods)
	public void setMetadata(String key, String value) {
		metadata.put(key, value);
		accessCount++;
	}

	public String getMetadata(String key) {
		accessCount++;
		return metadata.get(key);
	}

	public void removeMetadata(String key) {
		metadata.remove(key);
		accessCount++;
	}

	public Map<String, String> getAllMetadata() {
		accessCount++;
		return new HashMap<>(metadata);
	}

	// Validation methods (3 methods)
	public boolean validate() {
		accessCount++;
		return !items.isEmpty();
	}

	public void clear() {
		items.clear();
		metadata.clear();
		accessCount++;
	}

	public int getAccessCount() {
		accessCount++;
		return accessCount;
	}
}
