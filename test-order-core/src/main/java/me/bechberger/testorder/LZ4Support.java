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
	 */
	public static LZ4FrameOutputStream frameOutputStream(OutputStream out) throws IOException {
		return new LZ4FrameOutputStream(out, LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB, -1L, FACTORY.fastCompressor(),
				HASH_FACTORY.hash32(), LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE);
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
