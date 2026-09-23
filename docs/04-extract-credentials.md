# Getting your own camera's credentials

This SDK ships **no credentials**. You extract your own, from your own device, out of the vendor app
that is already talking to your own camera. If you don't own the camera, stop here.

You need five values for `camd.conf`:

| Key | What it is | Where it comes from |
|---|---|---|
| `vuid` | device id printed on the camera / shown in the app | you already have it |
| `uid` | internal UID the P2P layer actually uses | public lookup, see below |
| `server` | init string, `"<long uppercase blob>:vstarcam20xx"` | hook the app |
| `user` | `admin&userId=<digits>` | hook the app |
| `pwd` | device-local password (hex string on registered units) | hook the app |

## uid — no hooking needed

Public and unauthenticated:

```sh
curl "https://vuid.eye4.cn?vuid=<YOUR_DEVICE_ID>"
# -> {"uid":"VST............","supplier":"1","cluster":"10"}
```

Feeding the printed device id straight to the native layer fails with `INVALID_ID`; you must
translate it first. The daemon re-does this lookup on every start and falls back to the config value
when offline.

## server / user / pwd — hook the app

The three remaining values are passed as plain arguments to JNI entry points in
`libOKSMARTPPCS.so`. Attach frida to the vendor app and log them:

```js
const m = Process.findModuleByName("libOKSMARTPPCS.so");
["Java_com_vstarcam_JNIApi_connect",
 "Java_com_vstarcam_JNIApi_login"].forEach(n => {
  Interceptor.attach(m.findExportByName(n), {
    onEnter(a) { console.log(n, a[3].readCString?.() ?? a[3]); }
  });
});
```

The app's actual sequence, for reference:

```
create(uid, null)
clientSetVuid(vuid)          <-- easy to miss; required
connect(ptr, 126, server, 0) -> 3        (control/session layer)
login(ptr, user, pwd)        -> 1
connect(ptr, 121, server, 0) -> 3        (AV layer; only works AFTER the 126 connect)
```

Notes that cost real time to learn:

- `connect` type **126** is the session; type **121** activates AV and **fails on its own** — it only
  succeeds on an already-established 126 session, same handle.
- **`clientSetVuid()` must be called after `create()` and before `connect()`.**
- The libraries have **no anti-debug and no anti-frida**. Hooking is unobstructed.
- If frida can't find a Java bridge, that's the app being Flutter (logic in Dart), not protection.
  Native hooks are all you need.

## They rotate

When the camera re-registers or takes a firmware update, **uid, server, user and pwd all change**.
Symptom: connect succeeds but every CGI returns `result=-1` (auth failure). Re-extract.
