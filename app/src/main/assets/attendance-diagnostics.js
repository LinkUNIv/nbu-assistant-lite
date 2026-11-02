;(() => {
  if (location.origin !== 'https://attendance.nbu.edu.cn' || window.__attendanceDetail) return;
  window.__attendanceDetail = true;
  const sensitive = /password|passwd|cookie|authorization|token|ticket|secret|session|credential/i;
  const scrub = value => {
    if (Array.isArray(value)) return value.map(scrub);
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k,v]) => [k, sensitive.test(k) ? '[redacted]' : scrub(v)]));
    return value;
  };
  const text = value => {
    if (typeof value !== 'string') return scrub(value);
    try { return scrub(JSON.parse(value)); } catch (_) {
      // Avoid persisting HTML authentication pages or arbitrary non-JSON responses.
      return {nonJson:true, length:value.length};
    }
  };
  const api = raw => { try { const u = new URL(raw, location.href); return u.origin === location.origin && u.pathname.startsWith('/api/') ? u : null; } catch (_) {return null;} };
  let sequence = 0;
  const emit = (kind, data) => {
    let diagnostic = JSON.stringify(scrub(data));
    const truncated = diagnostic.length > 64000;
    if (truncated) diagnostic = JSON.stringify({truncated:true, originalLength:diagnostic.length, prefix:diagnostic.slice(0,60000)});
    console.log('NBU_TRACE:' + JSON.stringify({kind, url:location.origin+location.pathname, diagnostic}));
  };
  const params = u => Object.fromEntries([...u.searchParams].map(([k,v]) => [k,sensitive.test(k)?'[redacted]':v]));
  const body = b => b instanceof FormData || b instanceof URLSearchParams ? scrub(Object.fromEntries(b.entries())) : text(b == null ? null : b);
  const open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
  const meta = new WeakMap();
  XMLHttpRequest.prototype.open = function(method, raw) { const u=api(raw); meta.set(this,u?{id:++sequence, method, path:u.pathname, query:params(u)}:null); return open.apply(this,arguments); };
  XMLHttpRequest.prototype.send = function(b) {
    const m=meta.get(this);
    if(m) {
      emit('api_request',{...m, body:body(b)});
      this.addEventListener('loadend',()=>{
        let response;
        try { response=this.responseType==='json'?scrub(this.response):this.responseType===''||this.responseType==='text'?text(this.responseText):{responseType:this.responseType}; } catch(_){response={unavailable:true};}
        emit('api_response',{...m,status:this.status,response});
      },{once:true});
    }
    return send.apply(this,arguments);
  };
  const fetch=window.fetch;
  window.fetch=async function(input,init) {
    const u=api(typeof input==='string'?input:input instanceof URL?input.href:input.url);
    if(!u) return fetch.apply(this,arguments);
    const m={id:++sequence,method:init?.method||input?.method||'GET',path:u.pathname,query:params(u)};
    emit('api_request',{...m,body:body(init?.body),requestObjectBodyUnavailable:input instanceof Request&&!init?.body});
    try {
      const r=await fetch.apply(this,arguments);
      r.clone().text().then(t=>emit('api_response',{...m,status:r.status,response:text(t)})).catch(()=>emit('api_response',{...m,status:r.status,unavailable:true}));
      return r;
    } catch(e) {emit('api_failed',m);throw e;}
  };
  // Stable node IDs distinguish identical digit keys and connect mutations to clicks.
  const nodeIds=new WeakMap(); let nodeSequence=0, eventSequence=0, snapshotSequence=0;
  const tracked=()=>location.pathname.startsWith('/h5/student/');
  const nodeId=n=>{if(!n)return null;if(!nodeIds.has(n))nodeIds.set(n,++nodeSequence);return nodeIds.get(n);};
  const describe=n=>{
    if(!n)return null;
    if(n.nodeType===3)return {id:nodeId(n),parent:nodeId(n.parentNode),text:n.textContent};
    if(n.nodeType!==1)return {id:nodeId(n),type:n.nodeType};
    if(["SCRIPT","STYLE"].includes(n.tagName))return {id:nodeId(n),tag:n.tagName};
    const a={};
    for(const k of ['id','class','role','type','name','placeholder','aria-label','aria-expanded','aria-disabled','aria-selected','maxlength','inputmode','readonly','disabled','tabindex'])
      if(n.hasAttribute(k))a[k]=n.getAttribute(k);
    const b=n.getBoundingClientRect(),c=getComputedStyle(n);
    return {id:nodeId(n),parent:nodeId(n.parentNode),tag:n.tagName,attrs:a,
      text:[...n.childNodes].filter(x=>x.nodeType===3).map(x=>x.textContent).join(''),
      value:n.matches('input,textarea,select')?(n.type==='password'||n.type==='hidden'||sensitive.test(n.name+' '+n.id)?'[redacted]':n.value):undefined,
      rect:{x:b.x,y:b.y,width:b.width,height:b.height},display:c.display,visibility:c.visibility,opacity:c.opacity,
      before:getComputedStyle(n,'::before').content,after:getComputedStyle(n,'::after').content};
  };
  const detail=(kind,data)=>{
    const serialized=JSON.stringify(scrub(data)),id=++eventSequence;
    const parts=Math.max(1,Math.ceil(serialized.length/14000));
    for(let i=0;i<parts;i++)emit(kind,{eventId:id,part:i+1,parts,time:Date.now(),monotonicMs:performance.now(),payload:serialized.slice(i*14000,(i+1)*14000)});
  };
  const snapshot=reason=>{
    if(!tracked())return;
    detail('page_snapshot',{snapshotId:++snapshotSequence,reason,path:location.pathname,
      viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio,scrollX,scrollY},
      title:document.title,text:document.body?.innerText,
      elements:[...document.querySelectorAll('body,body *:not(script):not(style):not(link):not(meta)')].map(describe)});
  };
  for(const type of ['pointerdown','pointerup','click','input','change','focusin','focusout','keydown','keyup']) {
    document.addEventListener(type,e=>{
      if(!tracked())return;
      detail('interaction',{type,trusted:e.isTrusted,target:describe(e.target),
        path:e.composedPath().filter(x=>x.nodeType===1).slice(0,8).map(nodeId),
        x:e.clientX,y:e.clientY,pointerType:e.pointerType,
        key:e.target?.type==='password'?'[redacted]':e.key});
      if(type==='click'||type==='input'||type==='change') {
        snapshot(type+'_before');
        setTimeout(()=>snapshot(type+'_after'),0);
      }
    },true);
  }
  new MutationObserver(records=>{
    if(!tracked())return;
    const changes=records.filter(r=>!['SCRIPT','STYLE'].includes(r.target.nodeName)).map(r=>({
      type:r.type,target:describe(r.target),attribute:r.attributeName,
      oldValue:r.type==='attributes'&&!['class','style','aria-expanded','aria-selected','disabled','readonly'].includes(r.attributeName)?undefined:r.oldValue,
      added:[...r.addedNodes].map(describe),removed:[...r.removedNodes].map(n=>({id:nodeId(n),tag:n.nodeName}))
    }));
    if(changes.length)detail('dom_changes',{changes});
  }).observe(document,{subtree:true,childList:true,characterData:true,characterDataOldValue:true,attributes:true,attributeOldValue:true});
  for(const key of ['pushState','replaceState']) {
    const fn=history[key];history[key]=function(){const r=fn.apply(this,arguments);setTimeout(()=>snapshot(key),0);return r;};
  }
  addEventListener('popstate',()=>setTimeout(()=>snapshot('popstate'),0));
  addEventListener('resize',()=>snapshot('resize'));
  document.addEventListener('DOMContentLoaded',()=>snapshot('dom_ready'));
  addEventListener('load',()=>snapshot('load'));
  snapshot('observer_ready');
})();
