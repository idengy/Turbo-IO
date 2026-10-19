import fs from 'node:fs';
import path from 'node:path';

// Per-host obfuscated descriptors. Relocation evidence for 1.0.6: custom/docs/HOST_1.0.6.md (2026-10-18).
// Enum constant letters (MARS_FOTA/AI_SUBTITLE/LAUNCHER/NORMAL/SmallCore/MESSAGE_SEND_FAILED) are identical in both hosts.
// messageExtra/coreOption are the two remaining constructor parameter types of the message class; they are
// pinned explicitly rather than derived from business/core so a future host cannot pass by coincidence.
export const NAMES_105=Object.freeze({listener:'LH7/c$c;',sender:'LE3/u;',message:'LE3/Q;',queue:'LE3/V;',business:'LP3/h;',
  priority:'LE3/b;',core:'LQ3/q;',result:'LP3/A;',messageExtra:'LP3/Q;',coreOption:'LQ3/p;',send:'u',sendFile:'v'});
export const NAMES_106=Object.freeze({listener:'Lp8/c$c;',sender:'LX3/u;',message:'LX3/Q;',queue:'LX3/U;',business:'Li4/h;',
  priority:'LX3/b;',core:'Lj4/q;',result:'Li4/A;',messageExtra:'Li4/Q;',coreOption:'Lj4/p;',send:'t',sendFile:'u'});
export const PROFILES = Object.freeze({
  ef2e7dd346ca478e13d0f3f1bf31fa61412e44fb9b4ca9cf0d3e864ac608584b:
    Object.freeze({version:'1.0.4', code:195, directory:'host-104'}),
  '770ba0793d31609aa1e4477db2f8a7aec2c8acc4d9c6ab43d57b0dfc720d3ab3':
    Object.freeze({version:'1.0.5', code:201, directory:'host-105', names:NAMES_105, ota:true}),
  '4c1c57ae811cb959478efb5ec510000f7f51ddcb427afff369b1582c66b60206':
    // A-segment: no OTA wrapper. The official OTA adapter is pinned to 1.0.5 libapp.so offsets;
    // installing it against 1.0.6 would run wrong offsets. Re-enable in B after re-pinning.
    Object.freeze({version:'1.0.6', code:216, directory:'host-106', names:NAMES_106, ota:false}),
});
export function profileFor(sha) {
  const profile=PROFILES[sha];
  if(!profile)throw new Error('Unsupported APK SHA256; no host mutations performed');
  return profile;
}
// Apktool suffixes case-colliding names on case-insensitive APFS: u.1.smali when U.smali exists,
// and (apktool 3.x) i4.1/ when I4/ exists. Resolve by the exact DEX descriptor, never by filename alone.
const twin=(entry,wanted)=>entry.replace(/\.\d+$/,'').toLowerCase()===wanted.toLowerCase();
function resolveDirs(base,segments) {
  let dirs=[base];
  for(const segment of segments) {
    const next=[];
    for(const dir of dirs) {
      if(!fs.existsSync(dir))continue;
      for(const e of fs.readdirSync(dir))if(twin(e,segment)&&fs.statSync(path.join(dir,e)).isDirectory())next.push(path.join(dir,e));
    }
    dirs=next;
  }
  return dirs;
}
export function classFile(root,descriptor) {
  if(!/^L[A-Za-z0-9_$/]+;$/.test(descriptor))throw new Error('Invalid descriptor');
  const relative=descriptor.slice(1,-1),folder=path.dirname(relative),name=path.basename(relative);
  const segments=folder==='.'?[]:folder.split('/');
  const matches=[];
  for(const dex of fs.readdirSync(root).filter(n=>/^smali(?:_classes\d+)?$/.test(n))) {
    for(const directory of resolveDirs(path.join(root,dex),segments)) {
      for(const file of fs.readdirSync(directory)) {
        if(!file.endsWith('.smali')||!twin(file.slice(0,-6),name))continue;
        const full=path.join(directory,file),text=fs.readFileSync(full,'utf8');
        if(text.split('\n').some(line=>line.startsWith('.class ')&&line.endsWith(' '+descriptor)))matches.push(full);
      }
    }
  }
  if(matches.length!==1)throw new Error('Expected one exact class: '+descriptor+' (found '+matches.length+')');
  return matches[0];
}
export function verifyHost(root,profile) {
  const n=profile.names;
  if(!n)throw new Error('Profile '+profile.version+' has no descriptor table; use the prior builder');
  const expect=(descriptor,lines)=>{
    const file=classFile(root,descriptor),text=fs.readFileSync(file,'utf8');
    for(const line of lines)if(!text.split('\n').includes(line))throw new Error('Host ABI mismatch: '+descriptor+' '+line);
    return file;
  };
  const listener=expect(n.listener,[
    '.method public final onAsrResult(Ljava/lang/String;ZLjava/lang/String;)V',
    '.method public final onNlpResult(Lcom/rayneo/airuntime/controller/NlpResult;)V',
    '.method public final onResponseComplete()V']);
  const activity=expect('Lcom/rayneo/venus/MainActivity;',['.method public final onPostResume()V']);
  const event=expect('Lcom/rayneo/rayneo_venus_sdk_plugin/j;',['.method public final z(Ljava/lang/String;Ljava/util/Map;)V']);
  expect(n.sender,[
    '.field public static volatile h:'+n.sender,
    '.method public static g()Ljava/util/List;',
    '.method public final '+n.send+'('+n.message+'Lkotlin/jvm/functions/Function2;)V',
    '.method public final '+n.sendFile+'(Ljava/io/File;Ljava/lang/String;'+n.core+'Ljava/lang/String;)Ljava/lang/String;']);
  expect(n.message,['.method public constructor <init>([BLjava/lang/String;Ljava/lang/String;'+n.business+n.messageExtra+n.priority+'ZZ'+n.coreOption+'Lkotlin/jvm/functions/Function2;)V']);
  expect('Lcom/amap/api/maps/MapsInitializer;',['.method public static setApiKey(Ljava/lang/String;)V']);
  expect('Lcom/amap/api/location/AMapLocationClient;',['.method public static setApiKey(Ljava/lang/String;)V']);
  expect('Lcom/amap/api/services/core/ServiceSettings;',['.method public static getInstance()Lcom/amap/api/services/core/ServiceSettings;', '.method public setApiKey(Ljava/lang/String;)V']);
  for(const [descriptor,value] of [[n.business,'AI_SUBTITLE'],[n.business,'LAUNCHER'],[n.business,'MARS_FOTA'],[n.priority,'NORMAL'],[n.core,'SmallCore'],[n.result,'MESSAGE_SEND_FAILED']]) {
    const source=fs.readFileSync(classFile(root,descriptor),'utf8');
    if(!source.includes('"'+value+'"'))throw new Error('Missing enum '+value+' in '+descriptor);
  }
  const queue=expect(n.queue,['.method public static o('+n.message+')V']);
  const sender=classFile(root,n.sender);
  expect(n.message,['.field public final b:Ljava/lang/String;','.field public final c:'+n.business,'.field public final e:[B','.field public k:Lkotlin/jvm/functions/Function2;']);
  expect(n.result,['.field public static final enum r:'+n.result]);
  return {listener,activity,event,ota:{queue,sender},names:n,version:profile.version,code:profile.code};
}
