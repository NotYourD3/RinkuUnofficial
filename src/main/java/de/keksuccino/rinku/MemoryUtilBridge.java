package de.keksuccino.rinku;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import sun.misc.Unsafe;

public final class MemoryUtilBridge {

    private static final Unsafe UNSAFE;
    private static final Method CLEANER_CLEAN;
    private static final Object ATTACHMENT_NOT_REQUIRED = new Object();

    static {
        Unsafe unsafe = null;
        Method cleanerClean = null;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Throwable t) {
            unsafe = null;
        }
        try {
            Class<?> cleanerClass = Class.forName("sun.misc.Cleaner");
            cleanerClean = cleanerClass.getMethod("clean");
        } catch (Throwable t) {
            cleanerClean = null;
        }
        UNSAFE = unsafe;
        CLEANER_CLEAN = cleanerClean;
    }

    private MemoryUtilBridge() {
    }

    public static ByteBuffer memAlloc(int size) {
        return ByteBuffer.allocateDirect(size);
    }

    public static long memAddress(Buffer buffer) {
        if (UNSAFE != null && buffer != null) {
            try {
                return UNSAFE.getLong(buffer, UNSAFE.arrayBaseOffset(byte[].class) + 12L);
            } catch (Throwable ignored) {
            }
        }
        if (buffer != null && buffer.isDirect()) {
            try {
                Field addrField = Buffer.class.getDeclaredField("address");
                addrField.setAccessible(true);
                return addrField.getLong(buffer);
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    public static void memCopy(long srcAddress, long dstAddress, long bytes) {
        if (UNSAFE != null && srcAddress != 0L && dstAddress != 0L) {
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
        } catch (Throwable ignored) {
        }
    }

}
