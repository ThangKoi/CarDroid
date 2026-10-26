package com.okcar.tool.encrypter;

import java.nio.ByteBuffer;

/**
 * JNI bridge into OkcarOS's libnative-lib.so + libap-lib.so.
 *
 * The native libraries dispatch by fully-qualified class+method name, so
 * this file MUST stay in package `com.okcar.tool.encrypter` and match the
 * method signatures the native code expects. Do not refactor names.
 */
public abstract class SR {
    static {
        // Load in dependency order. The Android linker resolves NEEDED tags
        // but explicit ordering avoids surprises.
        System.loadLibrary("libusb");
        System.loadLibrary("jdns_sd");
        System.loadLibrary("resampler");
        System.loadLibrary("ap-lib");
        System.loadLibrary("uikit-lib");
        System.loadLibrary("easymock");
        System.loadLibrary("native-lib");
    }

    public static native long r(String str, int i);
    public static native int r2(String str);
    public static native int g(byte[] arr1, byte[] arr2, int i1, int i2, int i3, int i4, long handle);
    public static native void z3();
    public static native long u(String str, int i1, int i2, int i3, int i4, ByteBuffer buf, Object callback);
    public static native long u1(String str, int i1, long handle, int i2);
    public static native int u2(long handle);
    public static native int u3(long handle);
    public static native byte[] u4(long handle);
    public static native void u6(String str1, String str2);
    public static native int x1a(ByteBuffer buf, int i1, int i2, long handle);
    public static native void x2(byte[] arr1, byte[] arr2);
    public static native int p(long handle);
    public static native int q(long handle);
    public static native int t(long handle);
    public static native void t2(long handle1, long handle2);
    public static native void f(long handle1, long handle2);
    public static native int g1(long handle);
    public static native long[] z11(long handle);
    public static native void z13(long handle, int i);
    public static native String z2();
    public static native String z4();
    public static native String y6();
    public static native int y7();
    public static native int j();
    public static native int l();
    public static native int y(int i);
    public static native long y1(long handle);
    public static native int y2(long handle);
    public static native int y4(long handle, byte[] arr, int i1, int i2);
    public static native void y5(byte[] arr1, byte[] arr2);
    public static native int z5(long handle, int i1, int i2);
    public static native long z61(int i);
    public static native void z71(long handle);
}
