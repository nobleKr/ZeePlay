/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * JNI binding to the Zeekr system fdbus bridge (libfdbus-systemhmi-jni), the
 * transport the system HMI (AR-HUD service) listens on. The native library binds
 * to this exact class/package by name, so the binding surface matches the system
 * component; no third-party code is used. The library is present only on the head
 * unit, so loadLibrary is guarded — off-car the class still loads and calls fail
 * through the caller's try/catch (ZeekrArHudOutput handles that).
 */
package com.zeekrlife.systemhmi.adapter;

public final class FDbus implements AutoCloseable {
    public interface Listener {
        void onConnect();
        void onDisconnect();
        void onReceive(int code, byte[] payload);
    }

    private static boolean libReady;
    static {
        try {
            System.loadLibrary("fdbus-systemhmi-jni.system");
            libReady = true;
        } catch (Throwable t) {
            libReady = false;
        }
    }

    public static boolean available() { return libReady; }

    private final Listener listener;
    private boolean opened;

    public FDbus(Listener listener) { this.listener = listener; }

    public void open(String url) {
        initClientCommon(this, url, false);
        opened = true;
    }

    public boolean send(int code, byte[] payload) {
        return sendCommon(this, code, payload.length, payload);
    }

    @Override public void close() {
        if (opened) {
            opened = false;
            destoryClientCommon(this);
        }
    }

    // Native entry points (resolved from libfdbus-systemhmi-jni on the head unit).
    private native void initClientCommon(Object client, String url, boolean server);
    private native void destoryClientCommon(Object client);
    private native boolean sendCommon(Object client, int code, int length, byte[] payload);

    // JNI callbacks invoked by the native library.
    private void onReceiveCommon(Object client, int code, int length, byte[] payload) {
        listener.onReceive(code, payload);
    }
    private void onClientConnectCommon(Object client) { listener.onConnect(); }
    private void onClientDisconnectCommon(Object client) { listener.onDisconnect(); }
}
