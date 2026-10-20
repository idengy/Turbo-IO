// Version-scoped wrapper generators, testable without rewriting a real APK.
// bill branch 2026-10-19: hook bodies call OtaGates once (DEX 64K method-id budget of the 1.0.6 host); policy lives in OtaGates.java.
// bill branch 2026-10-19: descriptors come from the host profile's name table (host-profiles.mjs NAMES_105/NAMES_106)
// instead of the upstream 1.0.5 literals, so the same gates install on 1.0.6 (216).
import {NAMES_105} from './host-profiles.mjs';
export function wrapExact(text,header,original,body){
 if(text.includes(original+'(')){
  const start=text.indexOf(header+'\n');if(start<0)throw new Error('Missing OTA wrapper');
  const end=text.indexOf('.end method',start);if(end<0)throw new Error('Truncated method');
  text=text.slice(0,start)+text.slice(end+11);
 }else{if(text.split(header).length!==2)throw new Error('OTA ABI mismatch');text=text.replace(header,header.replace(/ ([A-Za-z_][A-Za-z_0-9]*)\(/,' '+original+'('));}
 return text+'\n'+header+'\n'+body+'\n.end method\n';
}
const check=n=>{for(const k of ['queue','message','sender','core','sendFile'])if(typeof n?.[k]!=='string'||!n[k])throw new Error('Host descriptor table incomplete: '+k);return n;};
export function queueHook(text,names=NAMES_105){const n=check(names);return wrapExact(text,'.method public static o('+n.message+')V','turboioOriginal_o',`    .locals 1
    invoke-static {p0}, Lcom/turboio/addon/OtaGates;->queue(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :ota_blocked
    invoke-static {p0}, ${n.queue}->turboioOriginal_o(${n.message})V
    :ota_blocked
    return-void`);}
export function fileHook(text,names=NAMES_105){const n=check(names);const sig='(Ljava/io/File;Ljava/lang/String;'+n.core+'Ljava/lang/String;)Ljava/lang/String;';return wrapExact(text,'.method public final '+n.sendFile+sig,'turboioOriginal_'+n.sendFile,`    .locals 1
    invoke-static {}, Lcom/turboio/addon/OtaGates;->file()V
    invoke-virtual/range {p0 .. p4}, ${n.sender}->turboioOriginal_${n.sendFile}${sig}
    move-result-object v0
    return-object v0`);}
// One new method id in the event bridge's dex (classes2 has one spare after the A-segment hooks).
export const EVENT_GUARD=`    invoke-static {p1, p2}, Lcom/turboio/addon/OtaGates;->event(Ljava/lang/String;Ljava/util/Map;)Z
    move-result v0
    if-eqz v0, :ota_event_pass
    return-void
    :ota_event_pass
`;
