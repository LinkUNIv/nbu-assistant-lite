const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const traces=[], calls=[], events={};
const response={status:200};
const context={URL,WeakMap,Date,JSON,location:{href:'https://i.nbu.edu.cn/home?ticket=SECRET#token=SECRET'},
 console:{log:s=>traces.push(JSON.parse(s.slice('NBU_TRACE:'.length)))},
 document:{addEventListener:(k,fn)=>{events[k]=fn}},
 history:{pushState(){return 7},replaceState(){return 8}},
 addEventListener:(k,fn)=>{events[k]=fn},
 fetch:async(...args)=>{calls.push(args);if(args[0]==='fail')throw new Error('SECRET');return response},
 XMLHttpRequest:class {open(...args){this.openArgs=args}send(...args){this.sendArgs=args;this.status=204;this.finish()}addEventListener(k,fn){this.finish=fn}}
};context.window=context;vm.createContext(context);
const source=fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/java/app/nbult/lite/browser/AttendanceBrowserActivity.kt'),'utf8');
const script=source.split('private val OBSERVER by lazy { "\"\"')[1].split('"\"\".trimIndent()')[0];vm.runInContext(script,context);
(async()=>{
 const init={method:'POST',body:'password=SECRET'};
 assert.equal(await context.fetch('https://i.nbu.edu.cn/login?ticket=SECRET',init),response);
 assert.equal(calls[0][1],init);assert.equal(traces.at(-1).url,'https://i.nbu.edu.cn/login');
 await context.fetch(new URL('https://i.nbu.edu.cn/api?token=SECRET'));
 assert.equal(traces.at(-1).url,'https://i.nbu.edu.cn/api');
 await assert.rejects(context.fetch('fail'),/SECRET/);
 const xhr=new context.XMLHttpRequest();xhr.open('POST','https://i.nbu.edu.cn/x?token=SECRET');xhr.send('password=SECRET');
 assert.equal(xhr.sendArgs[0],'password=SECRET');assert.equal(traces.at(-1).status,204);
 events.click({target:{tagName:'BUTTON'}});events.submit();
 assert.equal(context.history.pushState({},'', '/next'),7);
 const before=context.fetch;vm.runInContext(script,context);assert.equal(context.fetch,before);
 assert.ok(!JSON.stringify(traces).includes('SECRET'));
 console.log('Observer regression checks passed: POST preservation, URL object, failures, XHR, clicks, history, idempotency, query redaction.');
})().catch(e=>{console.error(e);process.exitCode=1});
