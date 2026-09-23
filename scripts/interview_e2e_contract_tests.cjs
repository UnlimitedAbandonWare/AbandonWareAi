const {test}=require('node:test');const assert=require('node:assert/strict');
const core=require('../main/resources/static/assets/interview/interview-core.js');
test('display extraction retains a complete condition and never truncates a long sentence',()=>{
  assert.equal(core.shortHint('검색 결과가 없으면 답을 지어내지 않습니다. 출처를 함께 확인합니다.'),'검색 결과가 없으면 답을 지어내지 않습니다.');
  assert.throws(()=>core.shortHint('가'.repeat(121)),/summary-needs-edit/);
  assert.equal(core.shortHint('가'.repeat(125)+'. 미개봉 제품에만 환불이 적용됩니다.'),'미개봉 제품에만 환불이 적용됩니다.');
  assert.equal(core.shortHint('이 제품은 정해진 기간 안에 환불할 수 있습니다. 단, 미개봉 제품에만 적용됩니다.'),'이 제품은 정해진 기간 안에 환불할 수 있습니다. 단, 미개봉 제품에만 적용됩니다.');
});
test('extractor handles unicode codepoints and empty content',()=>{
  assert.equal(Array.from(core.shortHint('🙂'.repeat(118)+'!')).length,119);
  assert.throws(()=>core.shortHint('  '),/summary-needs-edit/);
});
test('public receiver address accepts only HTTPS origin and rejects credentials and extra paths',()=>{
  assert.equal(core.publicOrigin('https://demo.trycloudflare.com'),'https://demo.trycloudflare.com');
  for(const x of ['http://demo.trycloudflare.com','https://user:secret@demo.trycloudflare.com','https://demo.trycloudflare.com/path','javascript:alert(1)'])assert.equal(core.publicOrigin(x),'');
});
