'use strict';
// Use the existing bundled Playwright through NODE_PATH; never install browsers.
const fs=require('node:fs/promises'),path=require('node:path');
const origin='http://127.0.0.1:18180',url=origin+'/assets/display/index.html';
const out=path.join(__dirname,'..','output','playwright','lens-preview',new Date().toISOString().replace(/[:.]/g,'-'));
const result={schema:'lens-preview-network.v1',status:'RUNNING',browserLaunches:0,liveLlmCalls:0,publicChatSends:0,cases:[]};
function check(ok,code){if(!ok)throw Object.assign(new Error(code),{code});}
async function run(browser,width,height){
 const context=await browser.newContext({viewport:{width,height}}),rows=[],links={},pending=new WeakMap();
 const report={width,height,requests:rows,grantRequests:links,checks:{},consoleEntries:0,pageErrors:0,fullViewPatterns:0};result.cases.push(report);
 let phase='page_load',fatal='',page;
 context.on('request',request=>{
  const parsed=new URL(request.url());if(parsed.origin!==origin)return;
  if(parsed.pathname==='/api/assist/display/lens/link'){
   links[phase]=(links[phase]||0)+1;
   if(phase!=='setup'){fatal='UNEXPECTED_GRANT_ISSUANCE';void context.close().catch(()=>{});}
  }
  if(parsed.pathname!=='/api/assist/display/lens/text')return;
  let preview=null;try{const value=request.postDataJSON()?.preview;preview=typeof value==='boolean'?value:null;}catch{}
  const row={id:rows.length+1,phase,method:request.method(),path:parsed.pathname,preview,status:null,at:new Date().toISOString()};
  rows.push(row);pending.set(request,row);
 });
 context.on('response',response=>{
  const request=response.request(),parsed=new URL(request.url());if(parsed.origin!==origin)return;
  const row=pending.get(request);if(row)row.status=response.status();
  if(parsed.pathname.startsWith('/api/')&&[401,403,429].includes(response.status())){
   fatal='HTTP_'+response.status();void context.close().catch(()=>{});
  }
 });
 context.on('requestfailed',request=>{const row=pending.get(request);if(row)row.failed=true;});
 function watch(p){p.on('console',message=>{report.consoleEntries++;if(/[a-f0-9]{64}/i.test(message.text()))report.fullViewPatterns++;});p.on('pageerror',()=>report.pageErrors++);}
 const count=name=>rows.filter(row=>row.phase===name).length;
 const wait=async ms=>{await page.waitForTimeout(ms);check(!fatal,fatal||'HTTP_FAILURE');};
 const ready=async()=>{await page.goto(url,{waitUntil:'domcontentloaded'});await page.waitForFunction(()=>!document.getElementById('connect-lens').disabled);};
 const blank=async()=>{await page.waitForFunction(()=>document.getElementById('lens-preview-frame').getAttribute('src')==='about:blank');};
 const open=async()=>{
  const before=rows.length;phase='preview_open';
  await page.locator('#lens-preview > summary').click();await page.locator('#lens-preview-open').click();
  await page.waitForFunction(()=>!document.getElementById('lens-preview-viewport').hidden);
  await wait(3000);check(rows.length>before,'PREVIEW_NO_REQUEST');
  check(rows.slice(before).every(row=>row.preview===true&&row.method==='POST'),'PREVIEW_BODY');
  check(rows.slice(before).some(row=>row.status===200),'PREVIEW_NO_SUCCESS');
 };
 try{
  page=await context.newPage();watch(page);await ready();await wait(10000);
  report.checks.pageLoadReads=count('page_load');check(report.checks.pageLoadReads===0,'STARTUP_LENS_READ');await blank();
  phase='setup';await page.locator('#connect-lens').click();
  await page.waitForFunction(()=>{try{return /^[a-f0-9]{64}$/.test(JSON.parse(localStorage.getItem('awx.display.lens.live')||'null')?.token||'');}catch{return false;}});
  check(links.setup===1,'CONTEXT_GRANT_SETUP');await open();
  await page.frameLocator('#lens-preview-frame').locator('body').waitFor({state:'visible'});
  report.checks.noHorizontalOverflow=await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth);
  report.checks.maskedAddress=await page.locator('#lens-address').getAttribute('type')==='password';
  report.fullViewPatterns+=await page.evaluate(()=>/[a-f0-9]{64}/i.test(document.body.innerText)?1:0);
  check(report.checks.noHorizontalOverflow,'HORIZONTAL_OVERFLOW');check(report.checks.maskedAddress,'UNMASKED_ADDRESS');
  await page.locator('#lens-preview-frame').scrollIntoViewIfNeeded();
  report.screenshot=width+'x'+height+'.png';await page.screenshot({path:path.join(out,report.screenshot)});
  await page.locator('#lens-preview-close').click();await blank();phase='after_close';await wait(10000);
  report.checks.afterCloseReads=count('after_close');check(report.checks.afterCloseReads===0,'READ_AFTER_CLOSE');
  await open();await page.locator('#lens-preview > summary').click();await blank();phase='after_fold';await wait(10000);
  report.checks.afterFoldReads=count('after_fold');check(report.checks.afterFoldReads===0,'READ_AFTER_FOLD');
  // Keep this isolated context alive; localStorage survives page closure without collecting storageState cookies.
  report.checks.previewSaved=await page.evaluate(()=>{try{return /^[a-f0-9]{64}$/.test(JSON.parse(localStorage.getItem('awx.display.lensPreview.live')||'null')?.token||'');}catch{return false;}});
  check(report.checks.previewSaved,'PREVIEW_NOT_SAVED');
  await page.evaluate(()=>{const key='awx.display.lens.live',saved=JSON.parse(localStorage.getItem(key));saved.expiresAt=0;localStorage.setItem(key,JSON.stringify(saved));});
  await page.close();phase='reopen_page_load';page=await context.newPage();watch(page);await ready();await wait(1000);await blank();
  report.checks.reopenInputEmpty=await page.locator('#lens-preview-address').inputValue()==='';
  check(report.checks.reopenInputEmpty,'REOPEN_INPUT_NOT_EMPTY');check(count('reopen_page_load')===0,'REOPEN_STARTUP_READ');
  const before=rows.length;await open();report.checks.reopenSucceeded=rows.length>before;
  report.checks.reopenGrantRequests=links.reopen_page_load||0;check(report.checks.reopenGrantRequests===0,'REOPEN_GRANT_ISSUANCE');
  await page.locator('#lens-preview-close').click();await blank();
  report.checks.allPreviewRequests=rows.filter(row=>row.phase==='preview_open').every(row=>row.preview===true);
  report.checks.rateLimited=rows.filter(row=>row.status===429).length;check(report.checks.rateLimited===0,'RATE_LIMITED');
  report.checks.previewGrantRequests=links.preview_open||0;check(report.checks.previewGrantRequests===0,'PREVIEW_GRANT_ISSUANCE');
  check(report.fullViewPatterns===0,'VIEW_EXPOSURE');check(report.pageErrors===0,'PAGE_ERROR');report.status='PASS';
 }catch(error){report.status='FAIL';throw Object.assign(new Error(fatal||error.code||'PROBE_ERROR'),{code:fatal||error.code||'PROBE_ERROR'});}
 finally{await context.close().catch(()=>{});}
}
async function main(){
 let browser;
 await fs.mkdir(out,{recursive:true});
 try{
  const {chromium}=require('playwright');result.browserLaunches++;
  browser=await chromium.launch(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH,headless:true}:{channel:'msedge',headless:true});
  for(const [width,height] of [[360,800],[840,900]])await run(browser,width,height);
  result.status='PASS';
 }catch(error){result.status='FAIL';result.errorCode=/^[A-Z0-9_]+$/.test(error.code||'')?error.code:'PROBE_ERROR';}
 finally{if(browser)await browser.close().catch(()=>{});}
 result.finishedAt=new Date().toISOString();
 result.outputHygiene={headersCollected:false,cookiesCollected:false,storageStateCollected:false,rawBodiesStored:false,fullHexPatterns:0};
 let json=JSON.stringify(result,null,2);
 if(/[a-f0-9]{64}/i.test(json)){result.status='FAIL';result.errorCode='HEX_EXPOSURE';result.outputHygiene.fullHexPatterns=1;json=JSON.stringify(result,null,2).replace(/[a-f0-9]{64}/ig,'[redacted]');}
 await fs.writeFile(path.join(out,'result.json'),json+'\n');
 process.stdout.write(JSON.stringify({status:result.status,errorCode:result.errorCode||null,browserLaunches:result.browserLaunches,result:path.join(out,'result.json')})+'\n');
 process.exitCode=result.status==='PASS'?0:1;
}
main().catch(()=>{process.stderr.write('PROBE_SETUP_FAILED\n');process.exitCode=1;});
