const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
// DEMO1-DEVIN-META-DISPLAY-JEV-PORT-20260930 S4: 렌즈 자산(meta/)은 대화·힌트만 표시한다.
// Jev 상태·판단·비용 토큰은 어떤 렌즈 파일에도 없어야 한다 (예외 없음).
const BANNED=/jev|reasonCode|surfaceModes|budget_skip|plan_gate|callsAllowed/i;
function*walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isDirectory())yield*walk(p);else if(e.isFile())yield p;}}
test('lens assets never reference jev decision fields',()=>{
 const dir=path.join('main','resources','static','assets','display','meta');
 const files=[...walk(dir)].filter(f=>/\.(js|mjs|cjs|html|css|json|webmanifest)$/i.test(f));
 assert.ok(files.length>0,'meta lens assets exist');
 for(const file of files)assert.ok(!BANNED.test(fs.readFileSync(file,'utf8')),file);
});
