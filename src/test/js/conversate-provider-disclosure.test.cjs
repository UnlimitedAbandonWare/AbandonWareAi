const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
test('microphone UI discloses configured external ASR without claiming local-only operation',()=>{
  const html=fs.readFileSync('main/resources/static/conversate/index.html','utf8');
  const js=fs.readFileSync('main/resources/static/conversate/app.js','utf8');
  assert.match(html,/외부 음성 인식 서비스를 사용할 수/);
  assert.doesNotMatch(html+js,/외부 STT 전송 없음|로컬 한국어 STT|외부 유료 호출 0회/);
});
