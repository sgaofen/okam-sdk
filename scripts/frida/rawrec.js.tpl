'use strict';
/* rawrec.js — App 无关的原生码流录制:直接从 PPCS 通道1 裸读 STREAMHEAD 帧,
 * 剥壳成 Annex-B。drain the backlog first (or the clip starts with stale footage),再录 DUR_MS 新鲜画面。
 * 帧格式: magic 55 aa 15 a8 | 头32B | off4=帧类型(0=I) | off16=u32LE 荷载长度 | 荷载@32 */
var DUR_MS = __DUR__;
var OUT    = "__OUT__";

var m = Process.findModuleByName("libOKSMARTPPCS.so");
if (!m) { console.log("ERR nolib"); } else {
var Check    = new NativeFunction(m.findExportByName("PPCS_Check"), 'int', ['int','pointer']);
var Read     = new NativeFunction(m.findExportByName("PPCS_Read"),  'int', ['int','uchar','pointer','pointer','uint32']);
var CheckBuf = new NativeFunction(m.findExportByName("PPCS_Check_Buffer"),'int',['int','uchar','pointer','pointer']);

var sinfo = Memory.alloc(128), sess = -1, mode = -1;
for (var s=0; s<16; s++){ if (Check(s, sinfo) === 0){ sess = s; mode = sinfo.add(81).readU8(); break; } }
if (sess < 0) { console.log("ERR nosession"); } else {
  console.log("[rawrec] session="+sess+" mode="+(mode?"RELAY":"P2P-DIRECT"));
  var hdr=Memory.alloc(64), body=Memory.alloc(4*1024*1024), lenp=Memory.alloc(4);
  var wsz=Memory.alloc(4), rsz=Memory.alloc(4);

  function pending(){ wsz.writeU32(0); rsz.writeU32(0); CheckBuf(sess,1,wsz,rsz); return rsz.readU32(); }
  function readExact(dst,n,tmo){
    var got=0, spins=0;
    while (got<n){
      lenp.writeS32(n-got);
      var r=Read(sess,1,dst.add(got),lenp,tmo); var k=lenp.readS32();
      if (r<0 || k<=0){ if(++spins>3) return got; continue; }
      got+=k; spins=0;
    }
    return got;
  }
  function isMagic(p){ return p.readU8()===0x55&&p.add(1).readU8()===0xaa&&p.add(2).readU8()===0x15&&p.add(3).readU8()===0xa8; }
  // 读一帧(头+荷载);写盘与否由 sink 决定。返回 {ok, ftype, plen}
  function oneFrame(sink){
    if (readExact(hdr,32,2000)!==32) return null;
    if (!isMagic(hdr)) return {resync:true};
    var ftype=hdr.add(4).readU8(), plen=hdr.add(16).readU32();
    if (plen<=0 || plen>4*1024*1024) return {bad:true};
    if (readExact(body,plen,5000)!==plen) return null;
    if (sink) sink(plen);
    return {ftype:ftype, plen:plen};
  }

  // ---- 阶段1:排空积压(丢弃),直到缓冲区基本见底 ----
  var p0 = pending(), dropped = 0, t0 = Date.now();
  while (pending() > 4096 && Date.now()-t0 < 8000){
    var r = oneFrame(null); if (!r) break; if (r.plen) dropped++;
  }
  console.log("[rawrec] drained backlog: pending "+p0+" -> "+pending()+" ("+dropped+" stale frames dropped)");

  // ---- 阶段2:录新鲜画面 ----
  var f = new File(OUT, "wb");
  var frames=0, keys=0, bytes=0, resync=0, bad=0;
  var tStart = Date.now();
  // 从关键帧开始,避免开头花屏
  var waited = 0;
  while (Date.now()-tStart < 3000){
    var r = oneFrame(null);
    if (!r) break;
    if (r.ftype === 0){ f.write(body.readByteArray(r.plen)); frames++; keys++; bytes+=r.plen; break; }
    waited++;
  }
  var tRec = Date.now();
  while (Date.now()-tRec < DUR_MS){
    var r = oneFrame(function(plen){ f.write(body.readByteArray(plen)); });
    if (!r){ break; }
    if (r.resync){ resync++; continue; }
    if (r.bad){ bad++; if(bad>5) break; continue; }
    frames++; bytes+=r.plen; if (r.ftype===0) keys++;
    if (frames%50===0) f.flush();
  }
  f.flush(); f.close();
  console.log("[rawrec] DONE frames="+frames+" keys="+keys+" bytes="+bytes+
              " skippedToKey="+waited+" resync="+resync+" bad="+bad+" leftPending="+pending());
}
}
