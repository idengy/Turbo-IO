package com.turboio.addon;

import java.util.Map;

/** Single-reference OTA gate entry points for the smali hooks (bill branch, 2026-10-19).
 *
 * The official 1.0.6 (216) classes2.dex sits two method ids under the 65,536 DEX limit, so the upstream
 * hook bodies (three policy calls each) can no longer be inlined in smali. Each hook now makes exactly one
 * static call here; the policy order and failure behaviour are copied from upstream ota-hooks.mjs:
 *   queue: OfficialOtaBridge → OfficialOtaPreparation → OtaController; deny or any Throwable ⇒ blocked()
 *   file : OfficialOtaBridge.allowFile && !OfficialOtaPreparation.enabled && OtaController.allowFile, else throw
 *   event: Bridge enabled ⇒ observe and pass; Preparation enabled ⇒ pass; else OtaController.consume;
 *          any Throwable ⇒ stop (upstream catch block returned without delivering the event)
 * No Bluetooth APIs, no firmware bytes, no state of its own.
 */
public final class OtaGates {
 private OtaGates(){}
 /** @return true when the vendor queue submission may proceed; false after blocked() was recorded. */
 public static boolean queue(Object message){
  boolean allowed;
  try{
   Boolean verdict=OfficialOtaBridge.allowQueue(message);
   if(verdict==null)verdict=OfficialOtaPreparation.allowQueue(message);
   allowed=verdict!=null?verdict:OtaController.allowQueue(message);
  }catch(Throwable t){allowed=false;}
  if(!allowed)OtaController.blocked(message);
  return allowed;
 }
 /** Throws IllegalStateException when the vendor file send must not run (same as the upstream smali body). */
 public static void file(){
  if(!(OfficialOtaBridge.allowFile()&&!OfficialOtaPreparation.enabled()&&OtaController.allowFile()))throw new IllegalStateException();
 }
 /** @return true when the official event must NOT be delivered further (consumed, or guard failure). */
 public static boolean event(String kind,Map<?,?> data){
  try{
   if(OfficialOtaBridge.enabled()){OfficialOtaBridge.observe(kind,data);return false;}
   if(OfficialOtaPreparation.enabled())return false;
   return OtaController.consume(kind,data);
  }catch(Throwable t){return true;}
 }
}
