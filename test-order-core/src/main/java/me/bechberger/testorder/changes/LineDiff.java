package me.bechberger.testorder.changes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple line-level diff utility using LCS (longest common subsequence).
 * Computes the number of changed lines and the actual diff text between two texts.
 */
public class LineDiff {

    private LineDiff() {}

    /**
     * Computes the number of changed lines between two text blocks.
     * Uses LCS (longest common subsequence) to determine the edit distance in lines.
     *
     * @return number of inserted + deleted lines (0 if identical)
     */
    public static int changedLineCount(String oldText, String newText) {
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";
        if (oldText.equals(newText)) return 0;

        String[] oldLines = oldText.isEmpty() ? new String[0] : oldText.split("\n", -1);
        String[] newLines = newText.isEmpty() ? new String[0] : newText.split("\n", -1);

        int lcs = lcsLength(oldLines, newLines);
        // changed = (old lines not in LCS) + (new lines not in LCS)
        return (oldLines.length - lcs) + (newLines.length - lcs);
    }

    /**
     * Produces the diff text (insertions and deletions) between two text blocks.
     * Uses LCS with O(m×n) traceback to identify changed lines precisely.
     * Each line is prefixed with "+" (insertion) or "-" (deletion).
     *
     * @return the diff text, or empty string if identical
     */
    public static String diffText(String oldText, String newText) {
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";
        if (oldText.equals(newText)) return "";

        String[] oldLines = oldText.isEmpty() ? new String[0] : oldText.split("\n", -1);
        String[] newLines = newText.isEmpty() ? new String[0] : newText.split("\n", -1);

        int m = oldLines.length, n = newLines.length;

        // Build full LCS table for traceback
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // Traceback to collect diff lines (reverse order, then reversed)
        List<String> changes = new ArrayList<>();
        int i = m, j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                i--; j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                changes.add("+" + newLines[j - 1]);
                j--;
            } else {
                changes.add("-" + oldLines[i - 1]);
                i--;
            }
        }

        Collections.reverse(changes);
        return String.join("\n", changes);
    }

    /**
     * Computes LCS length using O(min(m,n)) space DP.
     * For bodies under a few hundred lines this is efficient enough.
     */
    static int lcsLength(String[] a, String[] b) {
        // Ensure a is the shorter array to minimize space
        if (a.length > b.length) {
            String[] tmp = a; a = b; b = tmp;
        }
        int m = a.length, n = b.length;
        if (m == 0) return 0;

        // Standard DP with two rows
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            // swap
            int[] tmp = prev; prev = curr; curr = tmp;
            java.util.Arrays.fill(curr, 0);
        }
        return prev[n];
    }
}
