package me.bechberger.testorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.lz4.LZ4SafeDecompressor;
import net.jpountz.xxhash.XXHashFactory;

/**
 * Centralised LZ4 factory access. Uses the pure-Java safe implementation to
 * avoid {@code sun.misc.Unsafe} deprecation warnings on Java 21+.
 */
public final class LZ4Support {

	/**
	 * Compression level for LZ4 frame output streams.
	 * <ul>
	 * <li>{@link #FAST} — fastest writes, ~10-20% larger files. Ideal for
	 * learn-mode where the index is rewritten frequently.</li>
	 * <li>{@link #MEDIUM} — LZ4 HC level 4, ~2-3x slower than FAST but ~10-15%
	 * smaller. Good default for learn-mode on large indices.</li>
	 * <li>{@link #HC} — high compression (level 9), smallest files, much slower
	 * writes. Ideal for archival or CI where read speed matters more.</li>
	 * </ul>
	 */
	public enum Compression {
		/** LZ4 fast compressor — optimized for write speed. */
		FAST,
		/** LZ4 HC level 4 — balanced speed/size. */
		MEDIUM,
		/** LZ4 HC level 9 — optimized for compression ratio. */
		HC;

		/** Parse from user-facing string (case-insensitive). */
		public static Compression fromString(String s) {
			if (s == null || s.isBlank())
				return MEDIUM;
			return switch (s.strip().toLowerCase(java.util.Locale.ROOT)) {
				case "hc", "high" -> HC;
				case "fast", "low" -> FAST;
				default -> MEDIUM;
			};
		}
	}

	private static final LZ4Factory FACTORY = LZ4Factory.safeInstance();
	private static final XXHashFactory HASH_FACTORY = XXHashFactory.safeInstance();

	private LZ4Support() {
	}

	public static LZ4Factory factory() {
		return FACTORY;
	}

	public static LZ4Compressor highCompressor(int compressionLevel) {
		return FACTORY.highCompressor(compressionLevel);
	}

	public static LZ4Compressor fastCompressor() {
		return FACTORY.fastCompressor();
	}

	public static LZ4SafeDecompressor safeDecompressor() {
		return FACTORY.safeDecompressor();
	}

	/**
	 * Creates an LZ4 frame output stream using the safe (no-Unsafe) implementation.
	 * <p>
	 * Uses FAST compression intentionally for performance — this method is called
	 * on write-heavy paths where speed matters more than file size. Use
	 * {@link #frameOutputStream(OutputStream, Compression)} with
	 * {@link Compression#MEDIUM} or {@link Compression#HC} for smaller output.
	 */
	public static LZ4FrameOutputStream frameOutputStream(OutputStream out) throws IOException {
		return new LZ4FrameOutputStream(out, LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB, -1L, FACTORY.fastCompressor(),
				HASH_FACTORY.hash32(), LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE);
	}

	/**
	 * Creates an LZ4 frame output stream with high compression for archival writes
	 * (e.g. dependency index) where write speed is less important than file size.
	 */
	public static LZ4FrameOutputStream frameOutputStreamHC(OutputStream out) throws IOException {
		return new LZ4FrameOutputStream(out, LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB, -1L, FACTORY.highCompressor(9),
				HASH_FACTORY.hash32(), LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE);
	}

	/**
	 * Creates an LZ4 frame output stream with fast compression for write-heavy
	 * paths (e.g. learn-mode index writes where speed matters more than size).
	 * Typically 10-50x faster than HC with only ~5-15% larger output.
	 */
	public static LZ4FrameOutputStream frameOutputStreamFast(OutputStream out) throws IOException {
		return new LZ4FrameOutputStream(out, LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB, -1L, FACTORY.fastCompressor(),
				HASH_FACTORY.hash32(), LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE);
	}

	/**
	 * Creates an LZ4 frame output stream with medium compression (HC level 4) —
	 * ~10-15% smaller than FAST with only ~2-3x write overhead. Default for
	 * learn-mode index writes.
	 */
	public static LZ4FrameOutputStream frameOutputStreamMedium(OutputStream out) throws IOException {
		return new LZ4FrameOutputStream(out, LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB, -1L, FACTORY.highCompressor(4),
				HASH_FACTORY.hash32(), LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE);
	}

	/**
	 * Creates an LZ4 frame output stream with the given compression level.
	 */
	public static LZ4FrameOutputStream frameOutputStream(OutputStream out, Compression compression) throws IOException {
		return switch (compression) {
			case HC -> frameOutputStreamHC(out);
			case MEDIUM -> frameOutputStreamMedium(out);
			default -> frameOutputStreamFast(out);
		};
	}

	/**
	 * Creates an LZ4 frame input stream using the safe (no-Unsafe) implementation.
	 */
	public static LZ4FrameInputStream frameInputStream(InputStream in) throws IOException {
		return new LZ4FrameInputStream(in, FACTORY.safeDecompressor(), HASH_FACTORY.hash32(), false);
	}

	/**
	 * Creates an LZ4 block output stream using the safe (no-Unsafe) compressor and
	 * checksum.
	 */
	public static LZ4BlockOutputStream blockOutputStream(OutputStream out, int blockSize, LZ4Compressor compressor) {
		return new LZ4BlockOutputStream(out, blockSize, compressor,
				HASH_FACTORY.newStreamingHash32(0x9747b28c).asChecksum(), true);
	}

	/**
	 * Creates an LZ4 block input stream using the safe (no-Unsafe) decompressor and
	 * checksum.
	 */
	public static LZ4BlockInputStream blockInputStream(InputStream in) {
		return new LZ4BlockInputStream(in, FACTORY.fastDecompressor(),
				HASH_FACTORY.newStreamingHash32(0x9747b28c).asChecksum());
	}
}
