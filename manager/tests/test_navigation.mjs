// Exercise the real browser modules with generated data and delayed requests.
// Run: node --experimental-vm-modules --test manager/tests/test_navigation.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';

class Element {
 constructor(tag='div'){this.tag=tag;this.children=[];this.attrs={};this.events={};this.open=false;this.disabled=false;this.classList={add(){},remove(){},toggle(){}};}
 append(...children){this.children.push(...children);}
 replaceChildren(...children){this.children=children;this._text='';}
 setAttribute(k,v){this.attrs[k]=v;}
 removeAttribute(k){delete this.attrs[k];}
 addEventListener(k,v){this.events[k]=v;}
 get textContent(){return (this._text||'')+this.children.map(x=>x?.textContent??String(x)).join('');}
 set textContent(v){this._text=String(v);this.children=[];}
 click(){return this.events.click?.({type:'click'});}
 close(){this.open=false;}
 showModal(){this.open=true;}
 focus(){}
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};

async function browser(){
 const app=new Element(),drawer=new Element(),notice=new Element();
 const calls=[],routes=new Map(),timers=new Map(),intervals=[];
 let timer=0;
 const document={hidden:false,createElement:tag=>new Element(tag),createTextNode:text=>String(text),querySelector:s=>({'#app':app,'#drawer':drawer,'#notice':notice})[s]||null};
 const defaults={summary:{tracks:0,albums:0,artists:0,issues:{},drafts:0},members:[],corrections:[],'sources-config':{enabled:['qq','netease']},tasks:[],jobs:[],changes:[],suggestions:[],tracks:{items:[],ids:[],total:0},groups:{items:[],total:0},'ai/config':{usage:{},estimated_cost:null},navidrome:{config:{}}};
 const fetch=async(url,options)=>{
  const path=url.replace('/api/','').split('?')[0];
  const call={path,method:options.method,signal:options.signal};calls.push(call);
  const route=routes.get(path);
  if(route?.gate){
   await new Promise((resolve,reject)=>{
    route.gate.promise.then(resolve);
    if(!route.ignoreAbort){
     const abort=()=>reject(new DOMException('Aborted','AbortError'));
     if(options.signal?.aborted)abort();else options.signal?.addEventListener('abort',abort,{once:true});
    }
   });
  }
  return new Response(JSON.stringify(route?.value??defaults[path]??{}),{status:route?.status??200});
 };
 const context=vm.createContext({console,Node:Element,document,fetch,AbortController,DOMException,URLSearchParams,
  setTimeout:(f,ms)=>{timers.set(++timer,{f,ms});return timer;},clearTimeout:id=>timers.delete(id),setInterval:f=>{intervals.push(f);}});
 const files=new URL('../jiayin_manager/static/',import.meta.url);
 const source=await readFile(new URL('app.js',files),'utf8');
 const corrections=new vm.SourceTextModule(await readFile(new URL('corrections-ui.js',files),'utf8'),{context});
 const module=new vm.SourceTextModule(source+'\nglobalThis.hooks={state,render,api,button};',{context});
 await module.link(()=>corrections);await module.evaluate();await flush();
 return {h:context.hooks,app,drawer,document,notice,calls,routes,timers,intervals};
}

test('all management pages load without calling full-library statistics',async()=>{
 const b=await browser();
 for(const page of ['members','sources','corrections','library','artists','directory','advice','settings','jobs']){
  const before=b.calls.length;
  b.h.state.page=page;await b.h.render();
  assert.equal(b.calls.slice(before).some(x=>x.path==='summary'),false,page);
  assert.equal(b.h.state.renderPending,null);
  assert.ok(b.app.textContent.length>0);
 }
});

test('slow navigation has immediate feedback and never overwrites the latest page',async()=>{
 const b=await browser();const gate=deferred();
 b.routes.set('members',{gate,ignoreAbort:true,value:[]});
 b.h.state.page='members';const old=b.h.render();
 assert.match(b.app.textContent,/正在加载/);
 b.h.state.page='sources';await b.h.render();
 assert.match(b.app.textContent,/保存来源/);
 gate.resolve();await old;
 assert.match(b.app.textContent,/保存来源/);
 assert.doesNotMatch(b.app.textContent,/等待批准/);
});

test('leaving a slow page cancels its reads but does not cancel submitted writes',async()=>{
 const b=await browser();const read=deferred(),write=deferred();
 b.routes.set('members',{gate:read,value:[]});
 b.routes.set('generated-write',{gate:write,value:{ok:true}});
 b.h.state.page='members';const pending=b.h.render().catch(e=>e);
 const writing=b.h.api('generated-write','POST',{});
 b.h.state.page='sources';await b.h.render();
 assert.equal((await pending).name,'AbortError');
 assert.equal(b.calls.findLast(x=>x.path==='members').signal.aborted,true);
 assert.equal(b.calls.findLast(x=>x.path==='generated-write').signal,undefined);
 write.resolve();assert.equal((await writing).ok,true);
});

test('task polling does not overlap slow requests, actions, hidden tabs or dialogs',async()=>{
 const b=await browser();const gate=deferred();
 b.routes.set('tasks',{gate,value:[]});b.h.state.page='jobs';
 const pending=b.h.render();const before=b.calls.length;
 for(let i=0;i<15;i++)b.intervals[0]();
 assert.equal(b.calls.length,before);
 gate.resolve();await pending;
 b.h.state.actionPending=1;b.intervals[0]();assert.equal(b.calls.length,before);
 b.h.state.actionPending=0;b.document.hidden=true;b.intervals[0]();assert.equal(b.calls.length,before);
 b.document.hidden=false;b.drawer.open=true;b.intervals[0]();assert.equal(b.calls.length,before);
 b.drawer.open=false;b.intervals[0]();await flush();
 assert.equal(b.calls.length,before+3);
});

test('repeated clicks submit one action and restore the button after completion',async()=>{
 const b=await browser();const gate=deferred();let submitted=0;
 const button=b.h.button('Generated save',async()=>{submitted++;await gate.promise;});
 const first=button.click();const second=button.click();
 assert.equal(submitted,1);assert.equal(button.disabled,true);
 gate.resolve();await first;await second;
 assert.equal(button.disabled,false);assert.equal(b.h.state.actionPending,0);
});

test('corrections poll terminal failures and remain guarded during dialogs and actions',async()=>{
 const b=await browser();const gate=deferred();
 b.routes.set('corrections',{gate,value:[{id:'generated',username:'Generated',status:'executing',preview:[],stage:'检查文件'}]});
 b.h.state.page='corrections';const pending=b.h.render();const before=b.calls.length;
 b.intervals[0]();assert.equal(b.calls.length,before);
 gate.resolve();await pending;assert.match(b.app.textContent,/正在执行/);
 b.routes.set('corrections',{value:[{id:'generated',username:'Generated',status:'failed',preview:[],error:'原音频解码校验失败',progress:{total:1,completed:0,failed:1}}]});
 b.drawer.open=true;b.intervals[0]();assert.equal(b.calls.length,before);
 b.drawer.open=false;b.h.state.actionPending=1;b.intervals[0]();assert.equal(b.calls.length,before);
 b.h.state.actionPending=0;b.intervals[0]();await flush();
 assert.match(b.app.textContent,/执行失败/);assert.match(b.app.textContent,/原音频解码校验失败/);
 assert.match(b.app.textContent,/已完成 0 \/ 1 项 · 1 项失败/);
 assert.doesNotMatch(b.app.textContent,/正在执行/);
});

test('read timeout shows a retry view instead of leaving an inert page',async()=>{
 const b=await browser();const gate=deferred();b.routes.set('members',{gate});
 b.h.state.page='members';const pending=b.h.render().catch(e=>e);
 const timer=[...b.timers.values()].find(t=>t.ms===20000);assert.ok(timer);timer.f();
 assert.match((await pending).message,/加载超时/);
 assert.match(b.app.textContent,/重试/);assert.equal(b.h.state.renderPending,null);
});
