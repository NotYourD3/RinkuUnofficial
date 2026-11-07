package de.keksuccino.rinku;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;

import sun.misc.Unsafe;

public final class MemoryUtilBridge {

    private static final Unsafe UNSAFE;
    private static final long ADDRESS_OFFSET;
    private static final Method CLEANER_CLEAN;

    static {
        Unsafe unsafe = null;
        long addressOffset = -1L;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Throwable t) {
            unsafe = null;
        }
        // Resolve the real java.nio.Buffer.address field offset instead of guessing an object layout
        // from arrayBaseOffset(). A wrong offset silently yields a bogus native address and corrupts
        // memory, which is especially dangerous now that frame rows are copied with copyMemory().
        if (unsafe != null) {
            try {
                Field addressField = Buffer.class.getDeclaredField("address");
                addressOffset = unsafe.objectFieldOffset(addressField);
            } catch (Throwable t) {
                addressOffset = -1L;
            }
        }
        Method cleanerClean = null;
        try {
            Class<?> cleanerClass = Class.forName("sun.misc.Cleaner");
            cleanerClean = cleanerClass.getMethod("clean");
        } catch (Throwable t) {
            cleanerClean = null;
        }
        UNSAFE = unsafe;
        ADDRESS_OFFSET = addressOffset;
        CLEANER_CLEAN = cleanerClean;
    }

    private MemoryUtilBridge() {}

    public static ByteBuffer memAlloc(int size) {
        return ByteBuffer.allocateDirect(size);
    }

    public static long memAddress(Buffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return 0L;
        }
        if (UNSAFE != null && ADDRESS_OFFSET >= 0L) {
            try {
                return UNSAFE.getLong(buffer, ADDRESS_OFFSET);
            } catch (Throwable ignored) {}
        }
        try {
            Field addrField = Buffer.class.getDeclaredField("address");
            addrField.setAccessible(true);
            return addrField.getLong(buffer);
        } catch (Throwable ignored) {}
        return 0L;
    }

    public static void memCopy(long srcAddress, long dstAddress, long bytes) {
        if (UNSAFE != null && srcAddress != 0L && dstAddress != 0L && bytes > 0L) {
            UNSAFE.copyMemory(srcAddress, dstAddress, bytes);
        }
    }

    public static void memFree(Buffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return;
        }
        try {
            if (CLEANER_CLEAN != null) {
                Class<?> dbbClass = Class.forName("java.nio.DirectByteBuffer");
                Field cleanerField = dbbClass.getDeclaredField("cleaner");
                cleanerField.setAccessible(true);
                Object cleaner = cleanerField.get(buffer);
                if (cleaner != null) {
                    CLEANER_CLEAN.invoke(cleaner);
                }
            }
        } catch (Throwable ignored) {}
    }

}
