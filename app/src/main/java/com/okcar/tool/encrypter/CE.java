package com.okcar.tool.encrypter;

import android.content.Context;

/**
 * JNI bridge to OkcarOS's MFi-SAP / AES helpers in libnative-lib.so.
 *
 * The methods most relevant to the AirPlay handshake:
 *   - {@link #f()}: returns the MFi-SAP client material. First 32 bytes go
 *     into the body of POST /auth-setup (after a 0x01 prefix). Used by
 *     OkcarOS as `CE.f()` in `sources/n2/g.java:165`.
 *   - {@link #r(byte[])}: drives one round of MFi-SAP exchange given the
 *     server reply bytes; returns (iv ++ key) per OkcarOS observation.
 *   - {@link #z(Context)}: initializes the MFi machinery; safe to call once.
 *
 * The other native entry points (d, e, h, i, k, l, m) cover AES-CBC frame
 * encryption helpers and key-derivation primitives; we declare them so the
 * JVM picks up their symbols when the libraries load even if we don't
 * call them ourselves.
 */
public abstract class CE {
    public static native byte[] f();
    public static native byte[] r(byte[] serverResponse);
    public static native int z(Context context);

    // Helpers we don't drive directly but include to mirror the JNI table.
    public static native byte[] d(byte[] in);
    public static native byte[] e(byte[] in);
    public static native int h();
    public static native int i();
    public static native int k();
    public static native int l();
    public static native int m();
}
