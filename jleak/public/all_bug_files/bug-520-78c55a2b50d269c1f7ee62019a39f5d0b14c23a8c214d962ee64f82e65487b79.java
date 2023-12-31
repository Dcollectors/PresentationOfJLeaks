package freenet.support.io;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.spaceroots.mantissa.random.MersenneTwister;

import freenet.crypt.SHA256;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * Helper functions for working with Buckets.
 */
public class BucketTools {

	static final int BLOCK_SIZE = 4096;
	
	/**
	 * Copy from the input stream of <code>src</code> to the output stream of
	 * <code>dest</code>.
	 * 
	 * @param src
	 * @param dst
	 * @throws IOException
	 */
	public final static void copy(Bucket src, Bucket dst) throws IOException {
		OutputStream out = dst.getOutputStream();
		InputStream in = src.getInputStream();
		ReadableByteChannel readChannel = Channels.newChannel(in);
		WritableByteChannel writeChannel = Channels.newChannel(out);

		ByteBuffer buffer = ByteBuffer.allocateDirect(BLOCK_SIZE);
		while (readChannel.read(buffer) != -1) {
			buffer.flip();
			while(buffer.hasRemaining())
				writeChannel.write(buffer);
			buffer.clear();
		}

		writeChannel.close();
		readChannel.close();
	}

	public final static void zeroPad(Bucket b, long size) throws IOException {
		OutputStream out = b.getOutputStream();

		// Initialized to zero by default.
		byte[] buffer = new byte[16384];

		long count = 0;
		while (count < size) {
			long nRequired = buffer.length;
			if (nRequired > size - count) {
				nRequired = size - count;
			}
			out.write(buffer, 0, (int) nRequired);
			count += nRequired;
		}

		out.close();
	}

	public final static void paddedCopy(Bucket from, Bucket to, long nBytes,
			int blockSize) throws IOException {

		if (nBytes > blockSize) {
			throw new IllegalArgumentException("nBytes > blockSize");
		}

		OutputStream out = null;
		InputStream in = null;

		try {

			out = to.getOutputStream();
			byte[] buffer = new byte[16384];
			in = from.getInputStream();

			long count = 0;
			while (count != nBytes) {
				long nRequired = nBytes - count;
				if (nRequired > buffer.length) {
					nRequired = buffer.length;
				}
				long nRead = in.read(buffer, 0, (int) nRequired);
				if (nRead == -1) {
					throw new IOException("Not enough data in source bucket.");
				}
				out.write(buffer, 0, (int) nRead);
				count += nRead;
			}

			if (count < blockSize) {
				// hmmm... better to just allocate a new buffer
				// instead of explicitly zeroing the old one?
				// Zero pad to blockSize
				long padLength = buffer.length;
				if (padLength > blockSize - nBytes) {
					padLength = blockSize - nBytes;
				}
				for (int i = 0; i < padLength; i++) {
					buffer[i] = 0;
				}

				while (count != blockSize) {
					long nRequired = blockSize - count;
					if (blockSize - count > buffer.length) {
						nRequired = buffer.length;
					}
					out.write(buffer, 0, (int) nRequired);
					count += nRequired;
				}
			}
		} finally {
			if (in != null)
				in.close();
			if (out != null)
				out.close();
		}
	}

	public static Bucket[] makeBuckets(BucketFactory bf, int count, int size)
		throws IOException {
		Bucket[] ret = new Bucket[count];
		for (int i = 0; i < count; i++) {
			ret[i] = bf.makeBucket(size);
		}
		return ret;
	}

	public final static int[] nullIndices(Bucket[] array) {
		List list = new ArrayList();
		for (int i = 0; i < array.length; i++) {
			if (array[i] == null) {
				list.add(new Integer(i));
			}
		}

		int[] ret = new int[list.size()];
		for (int i = 0; i < list.size(); i++) {
			ret[i] = ((Integer) list.get(i)).intValue();
		}
		return ret;
	}

	public final static int[] nonNullIndices(Bucket[] array) {
		List list = new ArrayList();
		for (int i = 0; i < array.length; i++) {
			if (array[i] != null) {
				list.add(new Integer(i));
			}
		}

		int[] ret = new int[list.size()];
		for (int i = 0; i < list.size(); i++) {
			ret[i] = ((Integer) list.get(i)).intValue();
		}
		return ret;
	}

	public final static Bucket[] nonNullBuckets(Bucket[] array) {
		List list = new ArrayList(array.length);
		for (int i = 0; i < array.length; i++) {
			if (array[i] != null) {
				list.add(array[i]);
			}
		}

		Bucket[] ret = new Bucket[list.size()];
		return (Bucket[]) list.toArray(ret);
	}

	/**
	 * Read the entire bucket in as a byte array.
	 * Not a good idea unless it is very small!
	 * Don't call if concurrent writes may be happening.
	 * @throws IOException If there was an error reading from the bucket.
	 * @throws OutOfMemoryError If it was not possible to allocate enough 
	 * memory to contain the entire bucket.
	 */
	public final static byte[] toByteArray(Bucket bucket) throws IOException {
		long size = bucket.size();
		if(size > Integer.MAX_VALUE) throw new OutOfMemoryError();
		byte[] data = new byte[(int)size];
		InputStream is = bucket.getInputStream();
		DataInputStream dis = null;
		try {
			dis = new DataInputStream(is);
			dis.readFully(data);
		} finally {
			is.close();
			if(dis != null)
				dis.close();
		}
		return data;
	}

	public static int toByteArray(Bucket bucket, byte[] output) throws IOException {
		long size = bucket.size();
		if(size > output.length)
			throw new IllegalArgumentException("Data does not fit in provided buffer");
		InputStream is = bucket.getInputStream();
		int moved = 0;
		while(true) {
			if(moved == size) return moved;
			int x = is.read(output, moved, (int)(size - moved));
			if(x == -1) return moved;
			moved += x;
		}
	}
	
	public static Bucket makeImmutableBucket(BucketFactory bucketFactory, byte[] data) throws IOException {
		return makeImmutableBucket(bucketFactory, data, data.length);
	}
	
	public static Bucket makeImmutableBucket(BucketFactory bucketFactory, byte[] data, int length) throws IOException {
		Bucket bucket = bucketFactory.makeBucket(length);
		OutputStream os = bucket.getOutputStream();
		os.write(data, 0, length);
		os.close();
		bucket.setReadOnly();
		return bucket;
	}

	public static byte[] hash(Bucket data) throws IOException {
		InputStream is = null;
		try {
			MessageDigest md = SHA256.getMessageDigest();
			is = data.getInputStream();
			long bucketLength = data.size();
			long bytesRead = 0;
			byte[] buf = new byte[4096];
			while((bytesRead < bucketLength) || (bucketLength == -1)) {
				int readBytes = is.read(buf);
				if(readBytes < 0) break;
				bytesRead += readBytes;
				if(readBytes > 0)
					md.update(buf, 0, readBytes);
			}
			if((bytesRead < bucketLength) && (bucketLength > 0))
				throw new EOFException();
			if((bytesRead != bucketLength) && (bucketLength > 0))
				throw new IOException("Read "+bytesRead+" but bucket length "+bucketLength+ '!');
			byte[] retval = md.digest();
			SHA256.returnMessageDigest(md);
			return retval;
		} finally {
			if(is != null) is.close();
		}
	}

	/** Copy the given quantity of data from the given bucket to the given OutputStream. 
	 * @throws IOException If there was an error reading from the bucket or writing to the stream. */
	public static void copyTo(Bucket decodedData, OutputStream os, long truncateLength) throws IOException {
		if(truncateLength == 0) return;
		if(truncateLength < 0) truncateLength = Long.MAX_VALUE;
		InputStream is = decodedData.getInputStream();
		try {
			byte[] buf = new byte[4096];
			long moved = 0;
			while(moved < truncateLength) {
				// DO NOT move the (int) inside the Math.min()! big numbers truncate to negative numbers.
				int bytes = (int) Math.min(buf.length, truncateLength - moved);
				if(bytes <= 0)
					throw new IllegalStateException("bytes="+bytes+", truncateLength="+truncateLength+", moved="+moved);
				bytes = is.read(buf, 0, bytes);
				if(bytes <= 0) {
					if(truncateLength == Long.MAX_VALUE)
						break;
					throw new IOException("Could not move required quantity of data: "+bytes+" (moved "+moved+" of "+truncateLength+ ')');
				}
				os.write(buf, 0, bytes);
				moved += bytes;
			}
		} finally {
			is.close();
		}
	}

	/** Copy data from an InputStream into a Bucket. */
	public static void copyFrom(Bucket bucket, InputStream is, long truncateLength) throws IOException {
		OutputStream os = bucket.getOutputStream();
		byte[] buf = new byte[4096];
		if(truncateLength < 0) truncateLength = Long.MAX_VALUE;
		try {
			long moved = 0;
			while(moved < truncateLength) {
				// DO NOT move the (int) inside the Math.min()! big numbers truncate to negative numbers.
				int bytes = (int) Math.min(buf.length, truncateLength - moved);
				if(bytes <= 0)
					throw new IllegalStateException("bytes="+bytes+", truncateLength="+truncateLength+", moved="+moved);
				bytes = is.read(buf, 0, bytes);
				if(bytes <= 0) {
					if(truncateLength == Long.MAX_VALUE)
						break;
					throw new IOException("Could not move required quantity of data: "+bytes+" (moved "+moved+" of "+truncateLength+ ')');
				}
				os.write(buf, 0, bytes);
				moved += bytes;
			}
		} finally {
			os.close();
		}
	}

	/**
	 * Split the data into a series of read-only Bucket's.
	 * @param origData The original data Bucket.
	 * @param splitSize The number of bytes to put into each bucket.
	 * 
	 * FIXME This could be made many orders of magnitude more efficient on
	 * time and space if the underlying Bucket happens to be a passed-in
	 * plaintext file!
	 * 
	 * Note that this method will allocate a buffer of size splitSize.
	 * @throws IOException If there is an error creating buckets, reading from
	 * the provided bucket, or writing to created buckets.
	 */
	public static Bucket[] split(Bucket origData, int splitSize, BucketFactory bf) throws IOException {
		if(origData instanceof FileBucket) {
			return ((FileBucket)origData).split(splitSize);
		}
		long length = origData.size();
		if(length > ((long)Integer.MAX_VALUE) * splitSize)
			throw new IllegalArgumentException("Way too big!: "+length+" for "+splitSize);
		int bucketCount = (int) (length / splitSize);
		if(length % splitSize > 0) bucketCount++;
		Bucket[] buckets = new Bucket[bucketCount];
		InputStream is = origData.getInputStream();
		DataInputStream dis = null;
		try {
			dis = new DataInputStream(is);
			long remainingLength = length;
			byte[] buf = new byte[splitSize];
			for(int i=0;i<bucketCount;i++) {
				int len = (int) Math.min(splitSize, remainingLength);
				Bucket bucket = bf.makeBucket(len);
				buckets[i] = bucket;
				dis.readFully(buf, 0, len);
				remainingLength -= len;
				OutputStream os = bucket.getOutputStream();
				try {
					os.write(buf, 0, len);
				} finally {
					os.close();
				}
			}
		} finally {
			is.close();
			if(dis != null)
				dis.close();
		}
		return buckets;
	}
	
	/**
	 * Pad a bucket with random data
	 * 
	 * @param oldBucket
	 * @param blockLength
	 * @param BucketFactory
	 * @param length
	 * 
	 * @return the paded bucket
	 */
	public static Bucket pad(Bucket oldBucket, int blockLength, BucketFactory bf, int length) throws IOException {
		byte[] hash = BucketTools.hash(oldBucket);
		Bucket b = bf.makeBucket(blockLength);
		MersenneTwister mt = new MersenneTwister(hash);
		OutputStream os = b.getOutputStream();
		BucketTools.copyTo(oldBucket, os, length);
		byte[] buf = new byte[4096];
		for(int x=length;x<blockLength;) {
			int remaining = blockLength - x;
			int thisCycle = Math.min(remaining, buf.length);
			mt.nextBytes(buf); // FIXME??
			os.write(buf, 0, thisCycle);
			x += thisCycle;
		}
		os.close();
		if(b.size() != blockLength)
			throw new IllegalStateException();
		return b;
	}
}
