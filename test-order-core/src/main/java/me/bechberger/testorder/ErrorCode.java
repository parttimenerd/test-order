package me.bechberger.testorder;

/**
 * Standardized error codes for test-order operations.
 * Enables programmatic error handling in build systems and CI/CD.
 *
 * <p>Codes are organized in ranges:
 * <ul>
 *   <li>1000-1099: File/Index errors</li>
 *   <li>1100-1199: State/Persistence errors</li>
 *   <li>1200-1299: Configuration errors</li>
 *   <li>1300-1399: Change detection errors</li>
 *   <li>2000-2099: Informational (not errors)</li>
 *   <li>0: Success</li>
 * </ul>
 */
public enum ErrorCode {
    // Success
    SUCCESS(0, "Operation completed successfully"),

    // File/Index errors (1000-1099)
    INDEX_NOT_FOUND(1001, "No dependency index found"),
    INDEX_CORRUPTED(1002, "Index file is corrupted"),
    INDEX_EMPTY(1003, "Dependency index contains no tests"),
    INDEX_READ_ERROR(1004, "Failed to read dependency index"),
    INDEX_WRITE_ERROR(1005, "Failed to write dependency index"),

    // State/Persistence errors (1100-1199)
    STATE_NOT_FOUND(1101, "No state file found"),
    STATE_CORRUPTED(1102, "State file is corrupted"),
    STATE_READ_ERROR(1103, "Failed to read state file"),
    STATE_WRITE_ERROR(1104, "Failed to write state file"),
    HASH_FILE_LOCKED(1105, "Hash file is locked by another process"),
    HASH_FILE_WRITE_ERROR(1106, "Failed to write hash file"),
    FILE_LOCK_TIMEOUT(1107, "Timeout acquiring file lock"),
    PERMISSION_DENIED(1108, "Permission denied accessing test-order directory"),

    // Configuration errors (1200-1299)
    CHANGE_MODE_INVALID(1201, "Invalid change detection mode"),
    INSTRUMENTATION_MODE_INVALID(1202, "Invalid instrumentation mode"),
    WEIGHT_INVALID(1203, "Invalid scoring weight value"),
    THRESHOLD_INVALID(1204, "Invalid threshold value"),
    CONFIG_MISSING_REQUIRED(1205, "Required configuration is missing"),

    // Change detection errors (1300-1399)
    CHANGE_DETECTION_FAILED(1301, "Change detection failed"),
    GIT_NOT_AVAILABLE(1302, "Git is not available (required for change detection)"),
    SOURCE_ROOT_NOT_FOUND(1303, "Source root directory not found"),
    TEST_ROOT_NOT_FOUND(1304, "Test source root directory not found"),

    // Agent/Instrumentation errors (1400-1499)
    AGENT_NOT_FOUND(1401, "Instrumentation agent not found"),
    AGENT_LOAD_FAILED(1402, "Failed to load instrumentation agent"),

    // Informational/Warnings (2000-2099)
    AUTO_SWITCH_THRESHOLD_REACHED(2001, "Auto-switched to learn mode (threshold reached)"),
    AUTO_SWITCH_NEW_TESTS(2002, "Auto-switched to learn mode (new test classes detected)"),
    INDEX_NEEDS_REBUILD(2003, "Index contains stale entries (recommend testOrderCompact)"),
    HASH_FILE_STALE(2004, "Hash file is stale (snapshots may be outdated)"),
    DEPS_NOT_FOUND(2005, "No .deps files found (recommend running in learn mode first)");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * @return numeric error code
     */
    public int getCode() {
        return code;
    }

    /**
     * @return human-readable error message
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return true if this represents a successful operation
     */
    public boolean isSuccess() {
        return code == 0;
    }

    /**
     * @return true if this is an error (not informational)
     */
    public boolean isError() {
        return code >= 1000 && code < 2000;
    }

    /**
     * @return true if this is an informational code (not an error)
     */
    public boolean isInformational() {
        return code >= 2000;
    }

    /**
     * Resolve error code from numeric value.
     *
     * @return matching ErrorCode or null if not found
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec.code == code) {
                return ec;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("[%d] %s", code, message);
    }
}
