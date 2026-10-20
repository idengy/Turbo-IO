#!/usr/bin/env bash
# C0 签名脚本(Bill 在自己的终端跑,不经 Claude):对 B 段 guarded 包 zipalign + apksigner v3 签名,
# 验证证书指纹不变、verify_apk --guarded 通过,然后把 SHA-256 记进 SHA256SUMS.local。
# apksigner 会交互式要 keystore 口令;口令不进聊天、不进文件、不进参数。
# 停止条件:任一步非零退出就停,不要手动续做下一步。
set -euo pipefail
cd "$(dirname "$0")/../.."
source custom/scripts/env.sh >/dev/null

UNSIGNED=android-addon/build/TurboIO-RayNeo-1.0.6-guarded-unsigned.apk
UNSIGNED_SHA=e2a4b24b9810bd7f69542383bebdb1ba68c80139ca97527d7665901c42071cbe  # r2 2026-10-19,NavReflect.guarded() 修复后;r1 80a485a6… 作废
OFFICIAL=local/downloads/official-android-1.0.6-216/RayNeoAI-1.0.6-216-official.apk
KEYSTORE=local/keys/turboio-bill.p12
CERT_SHA=4f941f050c77edce03b2d4771b9d56f5dcf59552e98492e25a58fc83240e8478
ALIGNED=local/build/TurboIO-RayNeo-1.0.6-guarded-aligned.apk
SIGNED=local/build/TurboIO-RayNeo-1.0.6-guarded-signed.apk
SUMS=local/downloads/SHA256SUMS.local

[ -f "$UNSIGNED" ] || { echo "STOP: 缺 $UNSIGNED,先跑 B 段 package.mjs + --guarded"; exit 1; }
[ -f "$OFFICIAL" ] || { echo "STOP: 缺官方 APK $OFFICIAL"; exit 1; }
[ -f "$KEYSTORE" ] || { echo "STOP: 缺 keystore $KEYSTORE"; exit 1; }
if [ -e "$SIGNED" ]; then  # 上一轮签名包不覆盖、不删除,按其 SHA 前 8 位改名留档
  prev=$(shasum -a 256 "$SIGNED" | cut -c1-8); mv "$SIGNED" "${SIGNED%.apk}.prev-$prev.apk"; [ -e "$SIGNED.idsig" ] && mv "$SIGNED.idsig" "${SIGNED%.apk}.prev-$prev.apk.idsig"
  echo "[0/5] 旧签名包已改名为 ${SIGNED%.apk}.prev-$prev.apk"
fi
actual=$(shasum -a 256 "$UNSIGNED" | cut -d' ' -f1)
[ "$actual" = "$UNSIGNED_SHA" ] || { echo "STOP: 未签名包 SHA 不符 $actual"; exit 1; }
echo "[1/5] 未签名包 SHA 核对 OK"

mkdir -p local/build
zipalign -p -f 4 "$UNSIGNED" "$ALIGNED"
zipalign -c -p 4 "$ALIGNED" && echo "[2/5] ALIGN-OK"

echo "[3/5] apksigner 签名(下面会要 keystore 口令,直接在这个终端输入)"
apksigner sign --ks "$KEYSTORE" --ks-key-alias turboio --v3-signing-enabled true --out "$SIGNED" "$ALIGNED"
rm -f "$ALIGNED"

certs=$(apksigner verify --verbose --print-certs "$SIGNED")
echo "$certs" | grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" || { echo "STOP: v3 验签未通过"; echo "$certs"; exit 1; }
echo "$certs" | grep -qi "SHA-256 digest: $CERT_SHA" || { echo "STOP: 证书指纹变了,adb install -r 会被拒"; echo "$certs" | grep -i "SHA-256 digest"; exit 1; }
echo "[4/5] v3 验签 OK,证书指纹仍是 ${CERT_SHA:0:8}…"

python3 android-addon/verify_apk.py "$OFFICIAL" "$SIGNED" --relocated classes5.dex --public-assets --guarded > local/build/guarded-signed.verify.json \
  || { echo "STOP: verify_apk --guarded 非零退出"; cat local/build/guarded-signed.verify.json; exit 1; }
python3 - <<'PY'
import json;r=json.load(open('local/build/guarded-signed.verify.json'))
assert r['unexpected_paths']==[],r['unexpected_paths']
assert r['changed']==['AndroidManifest.xml','classes2.dex','lib/arm64-v8a/libapp.so'],r['changed']
assert r['relocated_host_dex']==['classes5.dex'] and r['addon_dex']==['classes6.dex'],(r['relocated_host_dex'],r['addon_dex'])
assert r['guarded']=={'marker':True,'library_sha256_pinned':True},r['guarded']
print('[5/5] verify_apk --guarded OK:',{k:r[k] for k in ('changed','relocated_host_dex','addon_dex','guarded')})
PY

sha=$(shasum -a 256 "$SIGNED" | cut -d' ' -f1)
echo "$sha  ../build/$(basename "$SIGNED")  # guarded 签名包 $(date +%F),unsigned ${UNSIGNED_SHA:0:8}…,cert ${CERT_SHA:0:8}…,verify --guarded OK" >> "$SUMS"
echo
echo "DONE 签名包: $SIGNED"
echo "SHA-256:    $sha  (已追加到 $SUMS;填进 FLASH_GUIDE 第 4 节记录表)"
echo "下一步按 custom/docs/FLASH_GUIDE.md 第 2 节第 1 步:adb install -r $SIGNED(Bill 亲手)"
