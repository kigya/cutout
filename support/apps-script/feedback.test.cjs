const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');
const source=fs.readFileSync(__dirname+'/Code.gs','utf8');
function setup(){
 const cache=new Map(),props=new Map(),sent=[];let held=false;
 const context={module:{exports:{}},Number,JSON,Error,String,Date,
  LockService:{getScriptLock:()=>({tryLock:()=>held=true,hasLock:()=>held,releaseLock:()=>held=false})},
  CacheService:{getScriptCache:()=>({get:k=>cache.get(k),put:(k,v)=>cache.set(k,v)})},
  PropertiesService:{getScriptProperties:()=>({getProperty:k=>props.get(k),setProperties:p=>Object.entries(p).forEach(([k,v])=>props.set(k,v))})},
  Utilities:{DigestAlgorithm:{SHA_256:0},computeDigest:(_a,email)=>[...Buffer.from(email)],formatDate:()=> '2026-10-09'},
  MailApp:{getRemainingDailyQuota:()=>100,sendEmail:p=>sent.push(p)},
  ContentService:{MimeType:{JSON:'json'},createTextOutput:text=>({setMimeType:()=>JSON.parse(text)})},
 };vm.createContext(context);vm.runInContext(source,context);return {context,cache,props,sent,api:context.module.exports};
}
const payload={platform:'android',appId: 'dev.kigya.cutout', email:'person@example.com',message:'This is a test question',rating:4,id:'12345678-1234-4321-a123-123456789abc',version:'0.5.0'};
const event=p=>({postData:{contents:JSON.stringify(p)}});
test('only explicit feedback fields are mailed to fixed recipient',()=>{const s=setup();const out=s.api.doPost(event(payload));assert.equal(out.ok,true);assert.equal(s.sent.length,1);assert.equal(s.sent[0].to,'kirillborichevskiy@gmail.com');assert.equal(s.sent[0].replyTo,payload.email);assert.match(s.sent[0].subject,/4\/5/);assert.match(s.sent[0].body,/test question/);assert.equal(s.api.doPost(event(payload)).ok,true);assert.equal(s.sent.length,1);});
test('support feedback can omit stars and trim the reply address',()=>{const s=setup();assert.equal(s.api.doPost(event({...payload,rating:0,appId: 'dev.kigya.cutout', email:' person@example.com '})).ok,true);assert.match(s.sent[0].body,/not supplied/);assert.equal(s.sent[0].replyTo,payload.email);});
test('invalid input never sends email',()=>{const s=setup();for(const p of [null,'x',{}, {...payload,platform:'bad'}, {...payload,platform:undefined}, {...payload,appId:'other.app'}, {...payload,appId:undefined}, {...payload,appId: 'dev.kigya.cutout', email:'bad'}, {...payload,appId: 'dev.kigya.cutout', email:'x'.repeat(255)}, {...payload,message:'tiny'}, {...payload,message:'a'.repeat(4001)}, {...payload,rating:6}, {...payload,rating:-1}, {...payload,rating:1.5}, {...payload,id:'bad'}, {...payload,version:'bad'}])assert.equal(s.api.doPost(event(p)).ok,false);assert.equal(s.api.doPost({postData:{contents:'bad json'}}).code,'SEND_FAILED');assert.equal(s.api.doPost({postData:{contents:'a'.repeat(20001)}}).code,'INVALID');assert.equal(s.api.doPost(null).code,'INVALID');assert.equal(s.sent.length,0);});
test('quota and flood protection do not trigger paid services',()=>{const s=setup();s.api.doPost(event(payload));assert.equal(s.api.doPost(event({...payload,id:'22345678-1234-4321-a123-123456789abc'})).code,'RATE_LIMIT');s.props.set('count','50');assert.equal(s.api.doPost(event({...payload,appId: 'dev.kigya.cutout', email:'other@example.com',id:'32345678-1234-4321-a123-123456789abc'})).code,'QUOTA');});
test('busy, service quota and email failure report failure',()=>{const s=setup();s.context.LockService.getScriptLock=()=>({tryLock:()=>false,hasLock:()=>false});assert.equal(s.api.doPost(event(payload)).code,'BUSY');const q=setup();q.context.MailApp.getRemainingDailyQuota=()=>0;assert.equal(q.api.doPost(event(payload)).code,'QUOTA');const f=setup();f.context.MailApp.sendEmail=()=>{throw new Error('provider failed');};assert.equal(f.api.doPost(event(payload)).code,'SEND_FAILED');assert.equal(f.sent.length,0);});
