import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {profileFor,classFile,verifyHost} from '../host-profiles.mjs';
assert.equal(profileFor('770ba0793d31609aa1e4477db2f8a7aec2c8acc4d9c6ab43d57b0dfc720d3ab3').code,201);
assert.equal(profileFor('4c1c57ae811cb959478efb5ec510000f7f51ddcb427afff369b1582c66b60206').code,216);
assert.equal(profileFor('4c1c57ae811cb959478efb5ec510000f7f51ddcb427afff369b1582c66b60206').ota,false);
assert.throws(()=>profileFor('unknown'));
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'turbo-host-test-'));
try {
  fs.mkdirSync(path.join(dir,'smali/E3'),{recursive:true});
  fs.writeFileSync(path.join(dir,'smali/E3/U.smali'),'.class public LE3/U;\n');
  fs.writeFileSync(path.join(dir,'smali/E3/u.1.smali'),'.class public LE3/u;\n');
  assert.equal(path.basename(classFile(dir,'LE3/u;')),'u.1.smali');
  assert.equal(path.basename(classFile(dir,'LE3/U;')),'U.smali');
  assert.throws(()=>classFile(dir,'LE3/nonexistent;'));
  assert.throws(()=>classFile(dir,'../../secret'));
  // apktool 3.x also suffixes case-colliding package directories (I4/ vs i4.1/); both must be scanned.
  fs.mkdirSync(path.join(dir,'smali_classes2/I4'),{recursive:true});
  fs.mkdirSync(path.join(dir,'smali_classes2/i4.1'),{recursive:true});
  fs.writeFileSync(path.join(dir,'smali_classes2/I4/h.smali'),'.class public LI4/h;\n');
  fs.writeFileSync(path.join(dir,'smali_classes2/i4.1/h.smali'),'.class public Li4/h;\n');
  assert.equal(path.relative(dir,classFile(dir,'Li4/h;')),'smali_classes2/i4.1/h.smali');
  assert.equal(path.relative(dir,classFile(dir,'LI4/h;')),'smali_classes2/I4/h.smali');
  assert.throws(()=>verifyHost(dir,{version:'1.0.5',code:201}),/no descriptor table/);
  assert.throws(()=>verifyHost(dir,profileFor('770ba0793d31609aa1e4477db2f8a7aec2c8acc4d9c6ab43d57b0dfc720d3ab3')),/Expected one exact class: LH7\/c\$c;/);
} finally { fs.rmSync(dir,{recursive:true}); }
console.log('Host profile tests passed (13 assertions).');
