package cam;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES11Ext;
import android.os.Handler;
import android.os.HandlerThread;
import com.veepai.AppPlayerApi;
import com.vstarcam.JNIApi;
import com.vstarcam.app_p2p_api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.net.URLDecoder;
import java.util.concurrent.CountDownLatch;

/**
 * Headless O-KAM / VStarcam camera daemon — fully App-independent.
 *
 * 2026-09-05 rebuild:
 *  - Credentials refreshed after the camera's cloud identity rotated (firmware/
 *    re-registration): new UID, serverParam, login user+pwd, and the previously
 *    missing clientSetVuid() step. UID is also re-fetched live from vuid.eye4.cn
 *    at startup so a future UID rotation self-heals (serverParam/pwd still need a
 *    re-capture from the App if THOSE rotate again).
 *  - The headless video pipeline now drains its output Surface with a real GL
 *    consumer (SurfaceTexture bound to a GL_TEXTURE_EXTERNAL_OES texture in an
 *    off-screen EGL pbuffer context, updateTexImage() on every frame). Without a
 *    consumer releasing buffers the vendor player's read/decode loop stalls after
 *    a few frames, which is why snapshots/recording produced nothing headless on
 *    the updated firmware. Format/size agnostic on purpose.
 */
public class Daemon implements ClientStateListener, ClientCommandListener, ClientReleaseListener,
                               AppPlayerApi.AppPlayerProgress {

    static final String DIR    = "/data/local/tmp/camd/";

    /** 从 DIR/camd.conf 读 key=value。缺文件或缺键就返回默认值。 */
    static java.util.Properties CONF = null;
    static String cfg(String k, String dflt) {
        if (CONF == null) {
            CONF = new java.util.Properties();
            try (java.io.FileInputStream f = new java.io.FileInputStream(DIR + "camd.conf")) {
                CONF.load(f);
            } catch (Exception e) {
                System.out.println("[camd] 读不到 " + DIR + "camd.conf —— 照着 camd.conf.example 建一个");
            }
        }
        String v = CONF.getProperty(k);
        return (v == null || v.trim().isEmpty()) ? dflt : v.trim();
    }
    static final int    PORT   = 8099;
    // ── 凭据:全部从 camd.conf 读,【不要】写死在源码里 ──────────────────
    //  怎么拿到你自己设备的凭据,见 docs/04-extract-credentials.md
    static       String UID    = cfg("uid",    "");   // 例 VSTx........  (内部 UID,不是机身编号)
    static final String VUID   = cfg("vuid",   "");   // 机身编号,App 里能看到
    static final String SERVER = cfg("server", "");   // 形如 "<一长串大写字母>:vstarcam20xx"
    static final String USER   = cfg("user",   "admin");
    static final String PWD    = cfg("pwd",    "");

    long ptr, player;
    int state = -99;
    boolean streaming;
    String lastCmd = "";

    // GL-backed drain
    SurfaceTexture st;
    Surface surface;
    HandlerThread glThread;
    Handler glHandler;
    EGLDisplay eglDpy = EGL14.EGL_NO_DISPLAY;
    EGLContext eglCtx = EGL14.EGL_NO_CONTEXT;
    EGLSurface eglSfc = EGL14.EGL_NO_SURFACE;
    int texId = 0;
    volatile long frameCount = 0;

    // recording state
    volatile boolean recording;
    volatile String recPath = "";
    volatile long recStarted;

    volatile String lastProgress = "";
    public void app_player_progress(long a,int b,int c,int d,int e,int f,int g,int h,long i,long j) {
        lastProgress = "p b=" + b + " c=" + c + " d=" + d + " e=" + e + " f=" + f + " g=" + g + " h=" + h;
    }
    public void app_player_head_info(long a,int b,int c,int d) { }
    public void app_player_gps_info(long a,int b,int c,float d,float e,float f,float g) { }
    public void app_player_draw_info(long a,int b,int c,int d,float e,float f,float g,float h) { }

    public void stateListener(long p, int s) { state = s; }
    public void releaseListener(long p) { }
    final java.util.Map<Integer, long[]> codeStats = new java.util.concurrent.ConcurrentHashMap<>();

    public void commandListener(long p, byte[] data, int code) {
        long[] st = codeStats.computeIfAbsent(code, k -> new long[3]);
        st[0]++; st[1] += data.length; st[2] = Math.max(st[2], data.length);
        try { lastCmd = new String(data, "UTF-8"); } catch (Exception e) { lastCmd = "<" + data.length + "B>"; }
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }

    // ---------- GL drain consumer ----------
    boolean setupGL() {
        glThread = new HandlerThread("gl");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = { false };
        glHandler.post(() -> {
            try {
                eglDpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                int[] ver = new int[2];
                EGL14.eglInitialize(eglDpy, ver, 0, ver, 1);
                int[] cfgAttr = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE
                };
                EGLConfig[] cfg = new EGLConfig[1];
                int[] nc = new int[1];
                EGL14.eglChooseConfig(eglDpy, cfgAttr, 0, cfg, 0, 1, nc, 0);
                int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
                eglCtx = EGL14.eglCreateContext(eglDpy, cfg[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
                int[] sfcAttr = { EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE };
                eglSfc = EGL14.eglCreatePbufferSurface(eglDpy, cfg[0], sfcAttr, 0);
                EGL14.eglMakeCurrent(eglDpy, eglSfc, eglSfc, eglCtx);
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                texId = t[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
                ok[0] = true;
            } catch (Throwable e) {
                System.out.println("[camd] GL setup FAIL: " + e);
            } finally { done.countDown(); }
        });
        try { done.await(); } catch (InterruptedException e) { }
        return ok[0];
    }

    // ---------- connect + stream ----------
    void teardown() {
        try { if (player != 0) { AppPlayerApi.stop(player); AppPlayerApi.destroy(player); } } catch (Throwable t) { }
        try { if (surface != null) surface.release(); } catch (Throwable t) { }
        try { if (st != null) st.release(); } catch (Throwable t) { }
        try { if (ptr != 0) { JNIApi.disconnect(ptr); JNIApi.destroy(ptr); } } catch (Throwable t) { }
        player = 0; ptr = 0; surface = null; st = null; streaming = false; recording = false;
    }

    boolean connectAndStart() {
        teardown();
        state = -99;
        ptr = JNIApi.create(UID, null);
        JNIApi.clientSetVuid(ptr, VUID);                 // ★ bind the device id (App does this pre-connect)
        int r = JNIApi.connect(ptr, 126, SERVER, 0);     // control/session layer
        for (int i = 0; i < 50 && state != 3 && r != 3; i++) sleep(300);
        if (r != 3 && state != 3) {
            System.out.println("[camd] connect(126) FAIL r=" + r + " state=" + state);
            return false;
        }
        boolean li = JNIApi.login(ptr, USER, PWD);
        System.out.println("[camd] login=" + li);
        sleep(600);
        // App's pre-video control CGIs on the 126 session (trace order), before the 121 connect.
        JNIApi.writeCgi(ptr, "get_params.cgi?", 3000);
        JNIApi.writeCgi(ptr, "get_camera_params.cgi?", 3000);
        JNIApi.writeCgi(ptr, "trans_cmd_string.cgi?cmd=4121&action=3&", 3000);
        JNIApi.writeCgi(ptr, "trans_cmd_string.cgi?cmd=4109&command=0&", 3000);
        // ★ AV-layer activation: a SECOND connect with type 121 on the SAME handle.
        //   Recovered by tracing the app (trace step [117]: connect type=0x79=121 after the
        //   126 session+login, right before createPlayer). Without this the video channel
        //   never opens: control CGIs work but the source read loop returns -1 forever.
        //   NB 121 alone (as a first connect) fails r=9/11 — it only works after 126.
        int r2 = JNIApi.connect(ptr, 121, SERVER, 0);
        System.out.println("[camd] AV connect(121)=" + r2);
        sleep(400);

        // GL-backed SurfaceTexture consumer: drains the player's output buffers so
        // the read/decode loop keeps running headless. setDefaultBufferSize is only
        // a hint; the vendor producer drives the real size, so this is size-agnostic.
        if (glThread == null && !setupGL())
            System.out.println("[camd] WARN GL unavailable, falling back to bare SurfaceTexture(0)");
        st = new SurfaceTexture(texId);
        st.setDefaultBufferSize(1280, 720);
        st.setOnFrameAvailableListener(s -> {
            try { s.updateTexImage(); frameCount++; } catch (Throwable t) { }
        }, glHandler);
        surface = new Surface(st);

        player = AppPlayerApi.createPlayer(1L, surface, 0, 0, 16000);
        AppPlayerApi.checkPlayerSource(player, 1, null, null, ptr, null);
        AppPlayerApi.stop(player);
        AppPlayerApi.setPlayerSource(player, 1, null, null, ptr, null);
        // ★ App order (trace): setPlayerSource -> livestream.cgi -> start (livestream BEFORE start).
        JNIApi.writeCgi(ptr, "livestream.cgi?streamid=10&substream=1&", 5000);
        AppPlayerApi.start(player);
        // App sends these right AFTER start (trace [146][147]) — likely the actual "push frames" enable.
        JNIApi.writeCgi(ptr, "trans_cmd_string.cgi?cmd=2126&command=1&", 3000);
        JNIApi.writeCgi(ptr, "trans_cmd_string.cgi?cmd=2123&command=1&sensor=0&", 3000);
        sleep(2500);
        streaming = true;
        System.out.println("[camd] ONLINE + streaming (ptr=" + ptr + " player=" + player
                + " frames=" + frameCount + ")");
        return true;
    }

    /** Start/stop only the decode+stream half, keeping the P2P session up. */
    synchronized String streamStart() {
        if (ptr == 0) return "ERR not connected";
        if (streaming) return "OK already streaming";
        AppPlayerApi.start(player);
        JNIApi.writeCgi(ptr, "livestream.cgi?streamid=10&substream=1&", 5000);
        sleep(2500);
        streaming = true;
        return "OK streaming";
    }

    synchronized String streamStop() {
        if (!streaming) return "OK already idle";
        if (recording) return "ERR recording in progress";
        JNIApi.writeCgi(ptr, "livestream.cgi?streamid=16&substream=1&", 3000);
        AppPlayerApi.pause(player);
        streaming = false;
        return "OK idle";
    }

    byte[] shoot() {
        try {
            AppPlayerApi.screenshot(player, DIR + "live.jpg", 0, 0, 0.0f, 0.0f, 0);
            return Files.readAllBytes(Paths.get(DIR + "live.jpg"));
        } catch (Throwable t) {
            System.out.println("[camd] shoot threw: " + t);
            return null;
        }
    }

    // ---------- recording ----------
    volatile int recFrames;
    Thread recThread;

    /**
     * Native recorder — energy-efficient: save() writes the camera's own H264/H265
     * elementary stream (zero re-encode) and saveMP4() remuxes it. This is the same
     * bitstream the App records (~31 kb/s on a static scene). It only works once the
     * read/decode loop is actually running (GL drain above), which is new in this build.
     *   save(player, base-path-without-extension, 177, 192)  -> 0 on success
     *   saveMP4(base, base+".mp4", 0, 0, 0)                  -> stop + wrap
     */
    volatile String natBase = "";
    synchronized String natStart() {
        if (player == 0 || !streaming) return "ERR not streaming (state=" + state + ")";
        if (recording) return "ERR already recording";
        new File("/sdcard/DCIM/OKCam").mkdirs();
        AppPlayerApi.setCacheDir(DIR);
        AppPlayerApi.setProgressCallback(this);
        natBase = DIR + "rec_" + System.currentTimeMillis();
        int r = AppPlayerApi.save(player, natBase, 177, 192);
        if (r != 0) { natBase = ""; return "ERR save returned " + r; }
        recording = true; recStarted = System.currentTimeMillis();
        System.out.println("[camd] NATIVE REC start -> " + natBase);
        return "OK " + natBase;
    }

    synchronized String natStop() {
        if (!recording) return "ERR not recording";
        recording = false;
        String mp4 = "/sdcard/DCIM/OKCam/OKCam_" + recStarted + ".mp4";
        boolean ok = false;
        try { ok = AppPlayerApi.saveMP4(natBase, mp4, 0, 0, 0); } catch (Throwable t) { }
        if (ok) {
            new File(natBase).delete();
            try { Runtime.getRuntime().exec(new String[]{"am","broadcast","-a",
                "android.intent.action.MEDIA_SCANNER_SCAN_FILE","-d","file://" + mp4}); } catch (Throwable t) { }
        }
        long ms = System.currentTimeMillis() - recStarted;
        String r = "saveMP4=" + ok + " ms=" + ms + " raw=" + new File(natBase).length()
                 + " mp4=" + new File(mp4).length() + " -> " + mp4;
        System.out.println("[camd] NATIVE REC stop  " + r);
        return r;
    }

    synchronized String recStart(int periodMs) {
        if (player == 0 || !streaming) return "ERR not streaming (state=" + state + ")";
        if (recording) return "ERR already recording -> " + recPath;
        String sess = "/sdcard/DCIM/OKCam/rec_" + System.currentTimeMillis();
        if (!new File(sess).mkdirs()) return "ERR cannot mkdir " + sess;
        recPath = sess;
        recFrames = 0;
        recording = true;
        recStarted = System.currentTimeMillis();
        final int period = periodMs > 0 ? periodMs : 250;
        recThread = new Thread(() -> {
            while (recording) {
                long t0 = System.currentTimeMillis();
                try {
                    String f = recPath + String.format("/%05d.jpg", recFrames + 1);
                    if (AppPlayerApi.screenshot(player, f, 0, 0, 0.0f, 0.0f, 0)
                            && new File(f).length() > 0) {
                        recFrames++;
                    }
                } catch (Throwable t) { }
                long rest = period - (System.currentTimeMillis() - t0);
                if (rest > 0) sleep(rest);
            }
        }, "rec");
        recThread.start();
        System.out.println("[camd] REC start -> " + sess + " period=" + period + "ms");
        return "OK " + sess;
    }

    synchronized String recStop() {
        if (!recording) return "ERR not recording";
        recording = false;
        try { if (recThread != null) recThread.join(3000); } catch (InterruptedException e) { }
        long ms = System.currentTimeMillis() - recStarted;
        String r = "OK dir=" + recPath + " frames=" + recFrames + " ms=" + ms
                 + " fps=" + (recFrames * 1000L / Math.max(ms, 1));
        System.out.println("[camd] REC stop  " + r);
        return r;
    }

    static String param(String q, String key) {
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(key)) {
                try { return URLDecoder.decode(kv.substring(e + 1), "UTF-8"); } catch (Exception ex) { return kv.substring(e + 1); }
            }
        }
        return null;
    }

    static int intParam(String q, String key, int dflt) {
        String v = param(q, key);
        try { return v == null ? dflt : Integer.parseInt(v); } catch (Exception e) { return dflt; }
    }

    void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String line = in.readLine();
            OutputStream out = sock.getOutputStream();
            if (line == null || !line.startsWith("GET ")) { return; }
            String url = line.substring(4).trim().split(" ")[0];
            String path = url, query = null;
            int qm = url.indexOf('?');
            if (qm >= 0) { path = url.substring(0, qm); query = url.substring(qm + 1); }

            if (path.equals("/status")) {
                text(out, "state=" + state + " streaming=" + streaming + " ptr=" + ptr
                        + " frames=" + frameCount
                        + " recording=" + recording + " recFrames=" + recFrames + " recPath=" + recPath + " prog=" + lastProgress
                        + "\nlastCmd=" + lastCmd + "\n");
            } else if (path.equals("/cgi")) {
                String c = param(query, "c");
                JNIApi.writeCgi(ptr, c, 5000);
                sleep(700);
                text(out, "OK sent: " + c + "\n--- resp ---\n" + lastCmd + "\n");
            } else if (path.equals("/nat/start")) {
                text(out, natStart() + "\n");
            } else if (path.equals("/nat/stop")) {
                text(out, natStop() + "\n");
            } else if (path.equals("/rec/start")) {
                text(out, recStart(intParam(query, "ms", 250)) + "\n");
            } else if (path.equals("/rec/stop")) {
                text(out, recStop() + "\n");
            } else if (path.equals("/stream/start")) {
                text(out, streamStart() + "\n");
            } else if (path.equals("/stream/stop")) {
                text(out, streamStop() + "\n");
            } else if (path.equals("/codes")) {
                StringBuilder sb = new StringBuilder("code count totalBytes maxBytes\n");
                for (java.util.Map.Entry<Integer, long[]> e : codeStats.entrySet())
                    sb.append(e.getKey()).append(' ').append(e.getValue()[0]).append(' ')
                      .append(e.getValue()[1]).append(' ').append(e.getValue()[2]).append('\n');
                text(out, sb.toString());
            } else if (path.startsWith("/api/")) {
                String fn = path.substring(5);
                String p  = param(query, "path");
                String src = param(query, "src"), dst = param(query, "dst");
                int a = intParam(query, "a", 0), bb = intParam(query, "b", 0), c = intParam(query, "c", 0);
                Object res;
                try {
                    switch (fn) {
                        case "startDown":       res = AppPlayerApi.startDown(player, p); break;
                        case "stopDown":        res = AppPlayerApi.stopDown(player); break;
                        case "save":            res = AppPlayerApi.save(player, p, a, bb); break;
                        case "saveNVR":         res = AppPlayerApi.saveNVR(player, p, a, bb, c); break;
                        case "saveMP4":         res = AppPlayerApi.saveMP4(src, dst, a, bb, c); break;
                        case "saveMP4Rate":     res = AppPlayerApi.saveMP4Rate(src, dst, a); break;
                        case "startVoiceRecord":res = AppPlayerApi.startVoiceRecord(player, a); break;
                        case "stopVoiceRecord": res = AppPlayerApi.stopVoiceRecord(player); break;
                        case "startVoice":      res = AppPlayerApi.startVoice(player); break;
                        case "stopVoice":       res = AppPlayerApi.stopVoice(player); break;
                        case "setCacheDir":     AppPlayerApi.setCacheDir(p); res = "void"; break;
                        case "tmpdir":          res = AppPlayerApi.getTmpfileDirPath(); break;
                        case "clearCache":      res = AppPlayerApi.clearCache(player); break;
                        case "checkPlayerSource": res = AppPlayerApi.checkPlayerSource(player, a, "", null, ptr, null); break;
                        default: res = "unknown fn"; break;
                    }
                } catch (Throwable t) { res = "THREW " + t; }
                String f = (p != null) ? p : (dst != null ? dst : null);
                text(out, fn + " -> " + res + (f != null ? ("  size=" + new File(f).length()) : "") + "\n");
            } else if (path.equals("/rec/mp4")) {
                String src = param(query, "src"), dst = param(query, "dst");
                boolean ok = AppPlayerApi.saveMP4(src, dst, intParam(query, "a", 0),
                                                  intParam(query, "b", 0), intParam(query, "c", 0));
                text(out, "saveMP4=" + ok + " dstBytes=" + new File(dst).length() + "\n");
            } else {
                long t0 = System.currentTimeMillis();
                byte[] jpg = shoot();
                if (jpg == null || jpg.length == 0) {
                    byte[] msg = ("no frame (state=" + state + " frames=" + frameCount + ")\n").getBytes("UTF-8");
                    out.write(("HTTP/1.0 503 Unavailable\r\nConnection: close\r\nContent-Length: "
                            + msg.length + "\r\n\r\n").getBytes("UTF-8"));
                    out.write(msg);
                } else {
                    out.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nConnection: close\r\nX-Shoot-Ms: "
                            + (System.currentTimeMillis() - t0) + "\r\nContent-Length: "
                            + jpg.length + "\r\n\r\n").getBytes("UTF-8"));
                    out.write(jpg);
                }
            }
            out.flush();
        } catch (Throwable t) {
            System.out.println("[camd] serve threw: " + t);
        }
    }

    static void text(OutputStream out, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: "
                + b.length + "\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
    }

    /** Best-effort: refresh the P2P UID from the public cloud map so a UID rotation self-heals. */
    static String fetchUid(String vuid, String dflt) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://vuid.eye4.cn?vuid=" + vuid).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(6000);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder(); String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            br.close();
            String s = sb.toString();
            int i = s.indexOf("\"uid\"");
            if (i >= 0) {
                int colon = s.indexOf(':', i);
                int q1 = s.indexOf('"', colon + 1);
                int q2 = s.indexOf('"', q1 + 1);
                String uid = s.substring(q1 + 1, q2);
                if (uid.length() > 5) { System.out.println("[camd] uid refreshed: " + uid); return uid; }
            }
        } catch (Throwable t) { System.out.println("[camd] uid fetch failed, baked: " + t); }
        return dflt;
    }

    public static void main(String[] args) throws Exception {
        for (String lib : new String[]{"libOKSMARTPPCS.so", "libOKSMARTPLAY.so", "libvp_log.so",
                                       "libc++_shared.so", "libyuv.so"}) {
            try { System.load(DIR + lib); } catch (Throwable t) { System.out.println("FAIL load " + lib + ": " + t); }
        }
        UID = fetchUid(VUID, UID);
        Daemon d = new Daemon();
        JNIApi.init(d, d, d);
        AppPlayerApi.setCacheDir(DIR);
        AppPlayerApi.setProgressCallback(d);
        System.out.println("[camd] starting, connecting... uid=" + UID);

        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress("0.0.0.0", PORT));
        new Thread(() -> {
            while (true) {
                try { Socket c = ss.accept(); new Thread(() -> d.serve(c)).start(); }
                catch (Throwable t) { sleep(200); }
            }
        }).start();

        d.connectAndStart();
        System.out.println("[camd] HTTP ready on :" + PORT
                + "  (GET / -> jpeg, /status, /cgi?c=, /nat/start,/nat/stop, /rec/start,/rec/stop)");
        while (true) {                                  // watchdog
            sleep(3000);
            if (d.state == 5 || d.state == 7 || d.ptr == 0 || !d.streaming) {
                if (d.recording) continue;
                System.out.println("[camd] watchdog: reconnect (state=" + d.state + ")");
                d.connectAndStart();
            }
        }
    }
}
