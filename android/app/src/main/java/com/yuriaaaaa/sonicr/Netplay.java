package com.yuriaaaaa.sonicr;

/**
 * Online rooms through a sonic-r-netplay hub (libsonicrnetplay.so, built from
 * android/netplay). The hub lists rooms and introduces players; the game's UDP
 * traffic goes through a local tunnel on 127.0.0.1:GAME_PORT.
 */
final class Netplay {
    static final int GAME_PORT = 5029;   // same port the netplay desktop launcher uses
    /** The public hub everyone shares, so all rooms show up in one list. */
    static final String DEFAULT_HUB = "sonicrrecomponline.duckdns.org";

    private static Boolean sLoaded;

    static synchronized boolean available() {
        if (sLoaded == null) {
            try {
                System.loadLibrary("sonicrnetplay");
                sLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                sLoaded = false;
            }
        }
        return sLoaded;
    }

    /** {"rooms":[{id,name,players,max,joinable,version}]} or {"error":"..."}; blocks up to 10 s. */
    static native String nativeListRooms(String hub);
    /** Runs a hub on this phone (TCP 8080, UDP 9000-9001) for the life of the process; null or an error. */
    static native String nativeStartHub();
    static native void nativeStartHost(String hub, String name, int gamePort);
    static native void nativeStartJoin(String hub, String roomId, int bindPort);
    /** Next session event as JSON ({"type":"status"|"registered"|"peer"|"tunnel"|"proxy"|"error"|"stopped",...}), or null. */
    static native String nativePollEvent();
    static native void nativeStop();

    /** True when a hub on this phone (for example HubService) answers on 127.0.0.1. */
    static boolean localHubAnswers() {
        try {
            return !new org.json.JSONObject(nativeListRooms("127.0.0.1")).has("error");
        } catch (org.json.JSONException e) {
            return false;
        }
    }

    private Netplay() { }
}
