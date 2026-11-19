'use strict';
// Pages-only simulation. No real authentication, JDBC, database locks or server API.
{
  const KEY = 'bankwatch-pages-v110-data';
  const ACTOR_KEY = 'bankwatch-pages-v110-role';
  const TOKEN = 'pages-local-simulation';
  const STEP = 600000000n;
  const ORIGIN = BigInt(Date.parse('2000-01-01T00:00:00+09:00')) * 1000n;
  const roles = [
    {operator_id:'1',login_id:'demo-admin',display_name:'체험 관리자',role:'ADMIN'},
    {operator_id:'2',login_id:'demo-a',display_name:'체험 담당자 A',role:'ANALYST'},
    {operator_id:'3',login_id:'demo-b',display_name:'체험 담당자 B',role:'ANALYST'},
  ];
  const customers = [1,2,3].map(n => ({account_id:String(n),customer_ref:'C100'+n,display_name:'가상 고객 '+n,lock_incident_id:null}));
  const fail = (status, message) => { throw Object.assign(new Error(message), {status}); };
  const fresh = () => ({format:1,customers:structuredClone(customers),events:[],alerts:[],evidence:[],incidents:[],history:[],seq:{alert:1,incident:1,history:1}});
  let opening;
  function database() {
    if (!opening) opening=new Promise((resolve,reject)=>{
      const request=indexedDB.open(KEY,1);
      request.onupgradeneeded=()=>request.result.createObjectStore('state');
      request.onsuccess=()=>resolve(request.result);
      request.onerror=()=>reject(new Error('이 브라우저에서 체험 저장소를 열지 못했습니다.'));
    });
    return opening;
  }
  async function transaction(work,write=true) {
    const db=await database();
    return new Promise((resolve,reject)=>{
      const tx=db.transaction('state',write?'readwrite':'readonly'),store=tx.objectStore('state');
      let result,error;
      const request=store.get('data');
      request.onsuccess=()=>{
        const snapshot=request.result||fresh();
        Promise.resolve(work(snapshot)).then(value=>{
          result=value;if(write)store.put(snapshot,'data');
        }).catch(failure=>{error=failure;try{tx.abort();}catch{reject(error);}});
      };
      tx.oncomplete=()=>{
        if(write) {try{localStorage.setItem(KEY,String(Date.now())+Math.random());}catch{}}
        resolve(result);
      };
      tx.onerror=()=>reject(error||Object.assign(new Error('브라우저 저장에 실패했습니다. 이번 변경은 취소했습니다.'),{status:503}));
      tx.onabort=()=>reject(error||Object.assign(new Error('체험 저장을 취소했습니다.'),{status:503}));
    });
  }
  function actor() {
    let name;
    try { name = sessionStorage.getItem(ACTOR_KEY); } catch {}
    return roles.find(row => row.login_id === name) || null;
  }
  function authorize(role) {
    const value = actor();
    if (!value) fail(401, '체험 역할을 선택하세요.');
    if (role && value.role !== role) fail(403, '현재 체험 역할에는 권한이 없습니다.');
    return value;
  }
  function time(value) {
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,6}))?(Z|[+-]\d{2}:\d{2})$/.exec(String(value));
    if (!match) fail(400, '오프셋이 있는 ISO 시각을 입력하세요. 소수 초는 최대 6자리입니다.');
    const [,year,month,day,hour,minute,second,fraction='',zone] = match;
    const days = new Date(Date.UTC(Number(year),Number(month),0)).getUTCDate();
    const offset = zone === 'Z' ? [0,0] : zone.slice(1).split(':').map(Number);
    if (+month<1 || +month>12 || +day<1 || +day>days || +hour>23 || +minute>59 || +second>59 ||
        offset[0]>18 || offset[1]>59 || offset[0]===18 && offset[1]!==0) fail(400,'유효하지 않은 시각입니다.');
    const base = Date.parse(year+'-'+month+'-'+day+'T'+hour+':'+minute+':'+second+zone);
    if (!Number.isFinite(base)) fail(400,'시각을 확인하세요.');
    return BigInt(base)*1000n + BigInt(fraction.padEnd(6,'0'));
  }
  function iso(micros) {
    const sec = micros / 1000000n;
    return new Date(Number(sec)*1000).toISOString().replace('.000Z','.'+(micros%1000000n).toString().padStart(6,'0')+'Z');
  }
  const bucket = value => ((value-ORIGIN)/STEP)*STEP+ORIGIN;
  const key = event => time(event.occurred_at).toString()+'|'+event.event_id;
  function compare(a,b) {
    const left=time(a.occurred_at), right=time(b.occurred_at);
    return left<right?-1:left>right?1:BigInt(a.event_id)<BigInt(b.event_id)?-1:BigInt(a.event_id)>BigInt(b.event_id)?1:0;
  }
  function parseCsv(text) {
    const rows=[];let row=[],cell='',quoted=false,closed=false;
    for (let i=0;i<text.length;i++) {
      const char=text[i];
      if (quoted) {
        if (char==='"' && text[i+1]==='"') {cell+='"';i++;}
        else if (char==='"') {quoted=false;closed=true;}
        else cell+=char;
      } else if (char==='"' && !cell && !closed) quoted=true;
      else if (char===',' || char==='\n' || char==='\r') {
        row.push(cell);cell='';closed=false;
        if (char!==',') {rows.push(row);row=[];if (char==='\r' && text[i+1]==='\n') i++;}
      } else {
        if (closed || char==='"') fail(400,'CSV 따옴표 형식을 확인하세요.');
        cell+=char;
      }
    }
    if (quoted) fail(400,'CSV 따옴표가 닫히지 않았습니다.');
    if (cell || row.length || closed) {row.push(cell);rows.push(row);}
    return rows;
  }
  async function importCsv(db, body, preparedText) {
    authorize('ADMIN');
    const file=body instanceof FormData ? body.get('file') : null;
    if (!(file instanceof Blob) || !file.size || file.size>2097152) fail(400,'2 MiB 이하 CSV 한 파일을 선택하세요.');
    const text=preparedText;
    const rows=parseCsv(text), header=['event_id','customer_ref','occurred_at','device_ref','event_type','is_new_device'];
    if (JSON.stringify(rows.shift())!==JSON.stringify(header) || !rows.length || rows.length>5000) fail(400,'6열 CSV 헤더와 1~5,000개 행을 확인하세요.');
    const values=rows.map((r,index)=>{
      if (r.length!==6 || !/^[1-9]\d*$/.test(r[0]) || BigInt(r[0])>9223372036854775807n) fail(400,'CSV '+(index+2)+'행 이벤트 ID/열 수를 확인하세요.');
      const timestamp=time(r[2]);
      if (timestamp<time('2026-10-01T00:00:00+09:00') || timestamp>=time('2027-01-01T00:00:00+09:00')) fail(400,'이번 체험은 KST 2026년 10~12월 로그를 받습니다. 이번 파일은 취소했습니다.');
      if (!r[1].trim() || r[1].length>40 || !r[3].trim() || Array.from(r[3]).length>100) fail(400,'고객/기기 참조를 확인하세요.');
      if (!['LOGIN_FAILED','LOGIN_SUCCESS'].includes(r[4]) || (r[4]==='LOGIN_FAILED' ? r[5]!=='' : !['true','false'].includes(r[5]))) fail(400,'성공은 신규 기기 true/false, 실패는 빈 값이어야 합니다.');
      return {event_id:r[0],customer_ref:r[1],occurred_at:iso(timestamp),device_ref:r[3],event_type:r[4],is_new_device:r[5]===''?null:r[5]==='true'};
    }).sort(compare);
    let insertedRows=0,duplicateRows=0;
    for (const event of values) {
      const customer=db.customers.find(c=>c.customer_ref===event.customer_ref);
      if (!customer) fail(400,'없는 고객 '+event.customer_ref+'입니다. 이번 파일의 신규 입력 전체를 취소했습니다.');
      const existing=db.events.find(row=>key(row)===key(event));
      if (existing) {
        const fields=['customer_ref','device_ref','event_type','is_new_device'];
        if (!fields.every(field=>existing[field]===event[field])) fail(400,'같은 원본 복합키의 내용이 다릅니다. 이번 파일 전체를 취소했습니다.');
        duplicateRows++;
      } else {
        db.events.push({...event,account_id:customer.account_id,customer_name:customer.display_name,ingested_at:new Date().toISOString()});
        insertedRows++;
      }
    }
    return {totalRows:values.length,insertedRows,duplicateRows};
  }
  function detect(db, body) {
    authorize('ADMIN');
    const start=time(body.from), end=time(body.to);
    if ((start-ORIGIN)%STEP || (end-ORIGIN)%STEP || end<=start || end-start>86400000000n) fail(400,'최대 24시간의 고정 10분 경계 구간이 필요합니다.');
    const events=db.events.filter(e=>time(e.occurred_at)>=start && time(e.occurred_at)<end).sort(compare);
    const buckets=new Map(),groups=new Map();let candidateSuccesses=0,createdAlerts=0,existingAlerts=0,insertedEvidenceRows=0;
    for (const event of events) {
      const group=event.account_id+'|'+bucket(time(event.occurred_at));
      if (!buckets.has(group)) buckets.set(group,[]);
      buckets.get(group).push(event);
    }
    for (const [group,rows] of buckets) {
      const failed=[];
      for (let i=0;i<rows.length;) {
        let end=i+1;
        while (end<rows.length && time(rows[end].occurred_at)===time(rows[i].occurred_at)) end++;
        const peers=rows.slice(i,end);
        for (const success of peers) {
          if (success.event_type==='LOGIN_SUCCESS' && success.is_new_device && failed.length>=5) {
            candidateSuccesses++;
            if (!groups.has(group)) groups.set(group,{success,failed:failed.slice()});
          }
        }
        failed.push(...peers.filter(e=>e.event_type==='LOGIN_FAILED'));
        i=end;
      }
    }
    for (const [group,{success,failed}] of groups) {
      if (db.alerts.some(a=>a.dedup===group)) {existingAlerts++;continue;}
      const id=String(db.seq.alert++), start=bucket(time(success.occurred_at));
      db.alerts.push({alert_id:id,account_id:success.account_id,rule_id:'1',rule_code:'R02',rule_name:'반복 실패 후 신규 기기 로그인',bucket_start:iso(start),bucket_end:iso(start+STEP),detected_at:new Date().toISOString(),dedup:group});
      for (const event of [...failed,success]) db.evidence.push({alert_id:id,event_occurred_at:event.occurred_at,event_id:event.event_id,is_anchor:event===success});
      createdAlerts++;insertedEvidenceRows+=failed.length+1;
    }
    return {candidateSuccesses,candidateBuckets:groups.size,createdAlerts,existingAlerts,insertedEvidenceRows};
  }
  function view(db, alert) {
    const customer=db.customers.find(c=>c.account_id===alert.account_id);
    const incident=db.incidents.find(i=>i.alert_id===alert.alert_id);
    const assigned=roles.find(a=>a.operator_id===incident?.assignee_id);
    return {...alert,customer_ref:customer.customer_ref,customer_name:customer.display_name,
      processing_status:incident?(incident.closed_at?'CLOSED':'IN_PROGRESS'):'UNASSIGNED',
      access_status:customer.lock_incident_id?'LOCKED':'ACTIVE',lock_incident_id:customer.lock_incident_id,
      incident_id:incident?.incident_id||null,incident_version:incident?.version??null,
      assignee_name:assigned?.display_name||null,is_mine:incident?.assignee_id===actor()?.operator_id,
      is_lock_cause:!!incident && customer.lock_incident_id===incident.incident_id,
      closed_at:incident?.closed_at||null,resolution:incident?.resolution||null};
  }
  function history(db, incident, action, reason) {
    const user=authorize();
    db.history.push({history_id:String(db.seq.history++),incident_id:incident.incident_id,actor_id:user.operator_id,
      actor_name:user.display_name,action_type:action,reason,created_at:new Date().toISOString()});
  }
  function claim(db, id) {
    const user=authorize('ANALYST'), alert=db.alerts.find(a=>a.alert_id===id);
    if (!alert) fail(404,'경보가 없습니다.');
    if (db.incidents.some(i=>i.alert_id===id)) fail(409,'이미 선점된 경보입니다. 최신 상태를 조회하세요.');
    const incident={incident_id:String(db.seq.incident++),alert_id:id,assignee_id:user.operator_id,version:0,created_at:new Date().toISOString(),closed_at:null,resolution:null};
    db.incidents.push(incident);history(db,incident,'CLAIM','체험 담당자로 배정');
    return {incidentId:incident.incident_id,alertId:id,assigneeId:user.operator_id,version:0};
  }
  function action(db,id,operation,body) {
    const user=authorize(operation==='unlock'?'ADMIN':'ANALYST'), incident=db.incidents.find(i=>i.incident_id===id);
    if (!incident) fail(404,'사건이 없습니다.');
    const alert=db.alerts.find(a=>a.alert_id===incident.alert_id), customer=db.customers.find(c=>c.account_id===alert.account_id);
    if (!Number.isInteger(body.version) || body.version<0 || !body.reason?.trim() || Array.from(body.reason.trim()).length>2000) fail(400,'기대 버전과 1~2,000자 사유를 입력하세요.');
    if (operation!=='unlock' && user.operator_id!==incident.assignee_id) fail(403,'담당자만 조치할 수 있습니다.');
    if (incident.version!==body.version) fail(409,'오래된 버전입니다. 최신 상태를 확인하세요.');
    if (operation!=='unlock' && incident.closed_at) fail(409,'종료된 사건입니다.');
    if (operation==='lock') {
      if (customer.lock_incident_id) fail(409,'이미 보호 잠금 상태입니다.');
      customer.lock_incident_id=id;
    } else if (operation==='unlock') {
      if (customer.lock_incident_id!==id) fail(409,'현재 보호 원인 사건이 아닙니다.');
      customer.lock_incident_id=null;
    } else if (operation==='close') {
      if (!['CONFIRMED','FALSE_POSITIVE','INCONCLUSIVE'].includes(body.resolution)) fail(400,'종료 판정을 선택하세요.');
      incident.closed_at=new Date().toISOString();incident.resolution=body.resolution;
    } else fail(404,'지원하지 않는 조치입니다.');
    incident.version++;history(db,incident,{lock:'LOCK_ACCOUNT',unlock:'UNLOCK_ACCOUNT',close:'CLOSE'}[operation],body.reason.trim());
    return {incidentId:id,version:incident.version,processingStatus:incident.closed_at?'CLOSED':'IN_PROGRESS',
      accessStatus:customer.lock_incident_id?'LOCKED':'ACTIVE',lockIncidentId:customer.lock_incident_id,closedAt:incident.closed_at,resolution:incident.resolution};
  }
  function logPage(db, query) {
    const limit=Math.min(100,Math.max(1,Number(query.get('limit')||20)));
    const filtered=db.events.filter(e=>(!query.get('account')||e.customer_ref===query.get('account')) &&
      (!query.get('type')||e.event_type===query.get('type')) && (!query.get('from')||time(e.occurred_at)>=time(query.get('from'))) &&
      (!query.get('to')||time(e.occurred_at)<time(query.get('to'))));
    const groups=new Map();
    for (const event of filtered) {
      const start=iso(bucket(time(event.occurred_at))), key=start+'|'+event.event_type;
      if (!groups.has(key)) groups.set(key,{bucket_start:start,event_type:event.event_type,event_count:0});
      groups.get(key).event_count++;
    }
    let ordered=filtered.toSorted((a,b)=>-compare(a,b));
    if (query.get('cursorTime')) ordered=ordered.filter(e=>compare(e,{occurred_at:query.get('cursorTime'),event_id:query.get('cursorId')})<0);
    const items=ordered.slice(0,limit),last=items.at(-1),hasMore=ordered.length>limit;
    return {items,total:filtered.length,aggregates:[...groups.values()].sort((a,b)=>a.bucket_start.localeCompare(b.bucket_start)),hasMore,nextCursor:hasMore?{cursorTime:last.occurred_at,cursorId:last.event_id}:null};
  }
  function alertPage(db,query) {
    let all=db.alerts.map(a=>view(db,a)).filter(a=>(!query.get('account')||a.customer_ref===query.get('account')) &&
      (!query.get('status')||a.processing_status===query.get('status')) && (query.get('mine')!=='true'||a.is_mine));
    all.sort((a,b)=>Number(b.alert_id)-Number(a.alert_id));
    if (query.get('cursorId')) all=all.filter(a=>Number(a.alert_id)<Number(query.get('cursorId')));
    const limit=Math.min(100,Math.max(1,Number(query.get('limit')||20))),items=all.slice(0,limit),hasMore=all.length>limit;
    return {items,hasMore,nextCursor:hasMore?{cursorId:items.at(-1).alert_id}:null};
  }
  async function route(path,options,db) {
    const method=options.method||'GET',url=new URL(path,location.href),body=options.body;
    const data=typeof body==='string'?JSON.parse(body):body||{};
    if (url.pathname==='/api/session') return {mode:'PAGES_DEMO',csrf:TOKEN,authenticated:!!actor(),actor:actor(),lab_enabled:false};
    if (url.pathname==='/login' && method==='POST') {
      const name=body.get('username');
      if (!roles.some(r=>r.login_id===name)) fail(400,'체험 역할을 선택하세요.');
      sessionStorage.setItem(ACTOR_KEY,name);return {ok:true};
    }
    if (url.pathname==='/logout' && method==='POST') {sessionStorage.removeItem(ACTOR_KEY);return {ok:true};}
    authorize();
    if (method!=='GET' && options.headers?.['X-CSRF-TOKEN']!==TOKEN) fail(403,'체험 세션을 새로 선택하세요.');
    if (url.pathname==='/logs/import' && method==='POST') return importCsv(db,body,options.preparedText);
    if (url.pathname==='/alerts/detect' && method==='POST') return detect(db,data);
    if (url.pathname==='/logs' && method==='GET') return logPage(db,url.searchParams);
    if (url.pathname==='/alerts' && method==='GET') return alertPage(db,url.searchParams);
    const claimPath=/^\/alerts\/(\d+)\/claim$/.exec(url.pathname);
    if (claimPath && method==='POST') return claim(db,claimPath[1]);
    const actionPath=/^\/incidents\/(\d+)\/(lock|unlock|close)$/.exec(url.pathname);
    if (actionPath && method==='POST') return action(db,actionPath[1],actionPath[2],data);
    const detail=/^\/alerts\/(\d+)$/.exec(url.pathname);
    if (detail && method==='GET') {
      const alert=db.alerts.find(a=>a.alert_id===detail[1]);if (!alert) fail(404,'경보가 없습니다.');
      const evidence=db.evidence.filter(e=>e.alert_id===alert.alert_id).map(e=>{
        const original=db.events.find(row=>key(row)===time(e.event_occurred_at)+'|'+e.event_id);
        return {...original,is_anchor:e.is_anchor};
      }).sort(compare);
      const incident=db.incidents.find(i=>i.alert_id===alert.alert_id);
      return {alert:view(db,alert),evidence,history:db.history.filter(h=>h.incident_id===incident?.incident_id)};
    }
    fail(404,'체험에서 제공하지 않는 경로입니다.');
  }
  async function request(path,options={}) {
    try {
      if(path==='/logs/import' && options.body instanceof FormData) {
        const file=options.body.get('file');
        if(file instanceof Blob && file.size<=2097152) {
          try {options={...options,preparedText:new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer())};}
          catch {fail(400,'UTF-8 CSV가 필요합니다.');}
        }
      }
      const write=(options.method||'GET')!=='GET' && !['/login','/logout'].includes(path);
      const result=await transaction(db=>route(path,options,db),write);
      return new Response(JSON.stringify(result),{status:200,headers:{'Content-Type':'application/json; charset=utf-8'}});
    } catch(error) {
      return new Response(JSON.stringify({message:error.message||'체험 처리에 실패했습니다.',status:error.status||400}),
        {status:error.status||400,headers:{'Content-Type':'application/json; charset=utf-8'}});
    }
  }
  window.BankWatchDemo=Object.freeze({
    fetch:request,roles:structuredClone(roles),inspect:()=>transaction(db=>structuredClone(db),false),
    selectRole:name=>{if (!roles.some(r=>r.login_id===name)) fail(400,'역할을 확인하세요.');sessionStorage.setItem(ACTOR_KEY,name);},
    reset:()=>transaction(db=>{Object.assign(db,fresh());return {ok:true};}),
  });
}
