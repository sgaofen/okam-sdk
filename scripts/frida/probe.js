'use strict';
function log(s){ console.log(s); }
var m = Process.findModuleByName("libOKSMARTPPCS.so");
if(!m){ log("!! libOKSMARTPPCS.so not loaded"); } else {
  log("[mod] base="+m.base+" size="+m.size);
  function ex(n){ var p=m.findExportByName(n); if(!p) log("  MISSING "+n); return p; }
  var pCheck = ex("PPCS_Check"), pCheckBuf = ex("PPCS_Check_Buffer"), pRead = ex("PPCS_Read");

  var Check    = new NativeFunction(pCheck,   'int', ['int','pointer']);
  var CheckBuf = new NativeFunction(pCheckBuf,'int', ['int','uchar','pointer','pointer']);
  var Read     = new NativeFunction(pRead,    'int', ['int','uchar','pointer','pointer','uint32']);

  function sockaddr(p){
    var port = (p.add(2).readU8()<<8) | p.add(3).readU8();     // big-endian
    var a=[]; for(var i=0;i<4;i++) a.push(p.add(4+i).readU8());
    return a.join(".")+":"+port;
  }

  var sinfo = Memory.alloc(128);
  var sessions=[];
  log("\n===== 1) enumerate session handles 0..15 (PPCS_Check) =====");
  for(var s=0;s<16;s++){
    Memory.protect(sinfo,128,'rw-'); sinfo.writeByteArray(new Array(128).fill(0));
    var r = Check(s, sinfo);
    if(r===0){
      var skt   = sinfo.readS32();
      var remote= sockaddr(sinfo.add(4));
      var lan   = sockaddr(sinfo.add(20));
      var wan   = sockaddr(sinfo.add(36));
      var ctime = sinfo.add(52).readU32();
      var did   = sinfo.add(56).readCString();
      var bCorD = sinfo.add(80).readU8();
      var bMode = sinfo.add(81).readU8();
      log("  session "+s+" OK  skt="+skt+"  remote="+remote+"  lan="+lan+"  wan="+wan
          +"  up="+ctime+"s  DID="+did+"  role="+(bCorD?"DEVICE":"CLIENT")
          +"  MODE="+(bMode?"*** RELAY ***":"P2P-DIRECT"));
      sessions.push(s);
    }
  }
  if(sessions.length===0) log("  (no valid sessions)");

  var wsz=Memory.alloc(4), rsz=Memory.alloc(4);
  log("\n===== 2) PPCS_Check_Buffer per channel (pending bytes) =====");
  sessions.forEach(function(s){
    for(var ch=0; ch<6; ch++){
      wsz.writeU32(0); rsz.writeU32(0);
      var r = CheckBuf(s, ch, wsz, rsz);
      log("  sess "+s+" ch"+ch+"  ret="+r+"  writePending="+wsz.readU32()+"  readAvail="+rsz.readU32());
    }
  });

  log("\n===== 3) direct PPCS_Read attempts =====");
  var buf = Memory.alloc(65536), lenp = Memory.alloc(4);
  sessions.forEach(function(s){
    for(var ch=0; ch<4; ch++){
      lenp.writeS32(40);
      var r = Read(s, ch, buf, lenp, 3000);
      var got = lenp.readS32();
      var hex = (r>=0 && got>0) ? hexdump(buf,{length:Math.min(got,64),ansi:false}) : "";
      log("  sess "+s+" ch"+ch+"  READ ret="+r+" got="+got+(hex?("\n"+hex):""));
    }
  });
  log("\n[probe done]");
}
