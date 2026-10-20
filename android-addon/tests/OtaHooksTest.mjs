import assert from 'node:assert/strict';
import {queueHook,fileHook,EVENT_GUARD} from '../ota-hooks.mjs';
import {NAMES_105,NAMES_106} from '../host-profiles.mjs';
let total=0;
const methodRefs=t=>new Set([...t.matchAll(/L[A-Za-z0-9_$/]+;->[A-Za-z0-9_$<>]+\([^)]*\)[^\s]+/g)].map(m=>m[0]));
function run(n,label){
 let q='.class public '+n.queue+'\n.method public static o('+n.message+')V\n .locals 0\n return-void\n.end method\n';
 let result=queueHook(q,n);assert.match(result,/\.method public static turboioOriginal_o/);assert.equal(result.split('.method public static o(').length,2);assert.match(result,/OtaGates;->queue\(Ljava\/lang\/Object;\)Z/);assert.match(result,/if-eqz v0, :ota_blocked/);
 let repeated=queueHook(result,n);assert.equal(repeated.split('.method public static turboioOriginal_o').length,2);assert.equal(repeated.split('OtaGates;->queue').length,2);assert.throws(()=>queueHook(q.replace('o('+n.message+')','o(Ljava/lang/Object;)'),n));
 assert.ok(result.includes('invoke-static {p0}, '+n.queue+'->turboioOriginal_o('+n.message+')V'));
 // DEX budget: the wrapper introduces exactly two method ids in the host dex (OtaGates.queue + the renamed original).
 assert.equal(methodRefs(result.slice(q.length)).size,2);
 const sig='(Ljava/io/File;Ljava/lang/String;'+n.core+'Ljava/lang/String;)Ljava/lang/String;';
 let f='.class public '+n.sender+'\n.method public final '+n.sendFile+sig+'\n .locals 0\n return-object p1\n.end method\n';
 result=fileHook(f,n);assert.match(result,new RegExp('\\.method public final turboioOriginal_'+n.sendFile+'\\('));assert.match(result,/invoke-virtual\/range \{p0 .. p4\}/);assert.match(result,/OtaGates;->file\(\)V/);assert.equal(fileHook(result,n).split('OtaGates;->file').length,2);
 assert.ok(result.includes('invoke-virtual/range {p0 .. p4}, '+n.sender+'->turboioOriginal_'+n.sendFile+sig));
 assert.equal(result.split('.method public final '+n.sendFile+'(').length,2);
 assert.equal(methodRefs(result.slice(f.length)).size,2);
 total+=16;console.log('  '+label+': queue '+n.queue+' file '+n.sender+'.'+n.sendFile+' ok');
}
run(NAMES_105,'1.0.5 (201)');run(NAMES_106,'1.0.6 (216)');
// Cross-host: a 1.0.5 smali body never matches the 1.0.6 table and vice versa (no silent wrong-host gate).
assert.throws(()=>queueHook('.class public LE3/V;\n.method public static o(LE3/Q;)V\n .locals 0\n return-void\n.end method\n',NAMES_106));
assert.throws(()=>fileHook('.class public LX3/u;\n.method public final u(Ljava/io/File;Ljava/lang/String;Lj4/q;Ljava/lang/String;)Ljava/lang/String;\n .locals 0\n return-object p1\n.end method\n',NAMES_105));
assert.throws(()=>queueHook('x',{queue:'LX3/U;'}));
// Default table is upstream 1.0.5 so upstream callers keep working.
const legacy='.class public LE3/V;\n.method public static o(LE3/Q;)V\n .locals 0\n return-void\n.end method\n';
assert.equal(queueHook(legacy),queueHook(legacy,NAMES_105));
assert.match(EVENT_GUARD,/OtaGates;->event\(Ljava\/lang\/String;Ljava\/util\/Map;\)Z/);assert.match(EVENT_GUARD,/if-eqz v0, :ota_event_pass/);assert.match(EVENT_GUARD,/return-void/);
assert.equal(methodRefs(EVENT_GUARD).size,1);
assert.ok(!/L[A-Za-z0-9]+\/[A-Za-z]+;/.test(EVENT_GUARD.replace(/Lcom\/turboio\/addon\/[A-Za-z]+;|Ljava\/[a-z/]+\/[A-Za-z]+;/g,'')),'EVENT_GUARD must not embed host descriptors');
total+=9;
console.log('OTA wrapper tests passed ('+total+' assertions, two host tables, one OtaGates call per hook); exact ABI checked again before package mutation.');
