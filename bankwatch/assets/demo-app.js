'use strict';

const root = document.getElementById('app');
const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]
));
const labels = {
  LOGIN_FAILED: '로그인 실패', LOGIN_SUCCESS: '로그인 성공',
  UNASSIGNED: '미배정', IN_PROGRESS: '조사 중', CLOSED: '종료',
  ACTIVE: '접근 가능', LOCKED: '보호 잠금',
  CONFIRMED: '사고 확인', FALSE_POSITIVE: '정상 행위', INCONCLUSIVE: '판단 보류',
  CLAIM: '담당자 배정', LOCK_ACCOUNT: '계정 잠금', UNLOCK_ACCOUNT: '잠금 해제', CLOSE: '사건 종료',
};
const nav = [['alerts', '경보·사건'], ['logs', '접속 기록']];
const icons = {
  logs: '<path d="M8 3h10a2 2 0 0 1 2 2v16H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2"/><path d="M8 8h8M8 12h8M8 16h5"/>',
  alerts: '<path d="M12 3 2 20h20L12 3Z"/><path d="M12 9v5m0 3v.5"/>',
};
const state = {
  actor: null, csrf: '', page: 'alerts', selected: null, data: null, detail: null,
  logFilters: {}, alertFilters: {}, logCursors: [], alertCursors: [],
  busy: false, drafts: new Map(), action: null, results: {}, notice: '',
};

function fmt(value, full = true) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', ...(full ? { year: 'numeric', month: '2-digit', day: '2-digit' } : {}),
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).format(date);
}

function num(value) {
  if (typeof value === 'string' && /^\d+$/.test(value)) return BigInt(value).toLocaleString('ko-KR');
  return Number(value ?? 0).toLocaleString('ko-KR');
}

function badge(value) {
  const kind = { UNASSIGNED: 'pending', IN_PROGRESS: 'progress', CLOSED: 'closed', ACTIVE: 'closed', LOCKED: 'high' }[value] || 'pending';
  return `<span class="badge ${kind}">${esc(labels[value] || value)}</span>`;
}

function toast(message) {
  const element = $('#toast');
  element.textContent = message;
  element.style.display = 'block';
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { element.style.display = 'none'; }, 5000);
}

function showNotice(message) {
  state.notice = message;
  const element = $('#page-notice');
  if (element) {
    element.textContent = message;
    element.hidden = !message;
  }
}

async function http(path, method = 'GET', data) {
  const headers = { Accept: 'application/json' };
  let body;
  if (data instanceof FormData || data instanceof URLSearchParams) body = data;
  else if (data !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(data);
  }
  if (method !== 'GET') headers['X-CSRF-TOKEN'] = state.csrf;
  let response;
  try {
    response = await BankWatchDemo.fetch(path, { method, headers, body, credentials: 'same-origin' });
  } catch {
    throw new Error(method === 'GET' ? '서버 연결을 확인하세요.'
      : '처리 결과를 확인하지 못했습니다. 목록을 새로고침해 결과를 먼저 확인하세요.');
  }
  if (response.status === 204) return {};
  let result;
  try {
    if (!/\bapplication\/(?:[\w.-]+\+)?json\b/i.test(response.headers.get('content-type') || '')) throw new Error();
    result = await response.json();
  } catch {
    const error = new Error('서버 응답을 확인하지 못했습니다. 연결 상태와 처리 결과를 확인하세요.');
    error.status = response.status;
    throw error;
  }
  if (!response.ok) {
    const error = new Error(result.message || '요청을 처리하지 못했습니다.');
    error.status = response.status;
    throw error;
  }
  return result;
}

function clearPrivateState() {
  state.page = 'alerts';
  state.selected = null;
  state.data = null;
  state.detail = null;
  state.logFilters = {};
  state.alertFilters = {};
  state.logCursors = [];
  state.alertCursors = [];
  state.drafts.clear();
  state.results = {};
  state.notice = '';
  closeModal();
}

async function session() {
  const result = await http('/api/session');
  if (result.mode !== 'PAGES_DEMO' || typeof result.csrf !== 'string' || !result.csrf) {
    throw new Error('BankWatch 서버의 세션 응답을 확인하세요.');
  }
  const actor = result.authenticated ? result.actor : null;
  if (result.authenticated && (!actor || typeof actor.operator_id !== 'string'
      || !['ADMIN', 'ANALYST'].includes(actor.role))) throw new Error('운영자 정보를 확인하세요.');
  if (state.actor?.operator_id !== actor?.operator_id) clearPrivateState();
  state.actor = actor;
  document.getElementById('demo-role').value = actor?.login_id || '';
  state.csrf = result.csrf;
}

function shell() {
  root.innerHTML = `<div class="shell"><aside class="sidebar">
    <div class="brand"><span class="logo-mark">BW</span><div><div class="brand-name">BankWatch</div>
    <div class="brand-sub">MOBILE BANKING SECURITY</div></div></div>
    <div><div class="nav-section">WORKSPACE</div><nav class="nav-list">${nav.map(([key, label]) => `
      <button type="button" class="nav-button ${state.page === key ? 'active' : ''}" data-nav="${key}"
        ${state.page === key ? 'aria-current="page"' : ''}>
        <svg viewBox="0 0 24 24" aria-hidden="true">${icons[key]}</svg>${label}</button>`).join('')}</nav></div>
    <footer class="nav-tail"><b>신뢰할 수 있는 근거, 일관된 대응</b>가상 모바일뱅킹 인증 로그</footer></aside>
    <div class="main"><header class="topbar"><div class="breadcrumb">보안관제 센터
    <b>${nav.find(([key]) => key === state.page)[1]}</b></div><div class="top-actions">
    <span class="mode-badge">브라우저 체험</span><span class="operator"><span class="avatar" aria-hidden="true">
    ${esc(state.actor.display_name.slice(-1))}</span>${esc(state.actor.display_name)}
    · ${state.actor.role === 'ADMIN' ? '관리자' : '관제 담당자'}</span>
    <button type="button" class="btn quiet small" data-action="logout">로그아웃</button></div></header>
    <div class="content"><div id="page-notice" class="error-inline" role="alert" ${state.notice ? '' : 'hidden'}>
    ${esc(state.notice)}</div><div id="content"></div></div></div></div>`;
}

function heading(title, description, actions = '') {
  return `<div class="page-heading"><div><div class="eyebrow">SECURITY OPERATIONS</div>
    <h1>${title}</h1><p class="description">${description}</p></div><div class="page-actions">${actions}</div></div>`;
}

function empty(message) {
  return `<div class="empty"><strong>${esc(message)}</strong>검색 조건과 이전 처리 결과를 확인하세요.</div>`;
}

function option(value, text, selected) {
  return `<option value="${esc(value)}" ${value === selected ? 'selected' : ''}>${esc(text)}</option>`;
}

function pager(cursors, page) {
  return `<div class="inline-row"><button type="button" class="btn small" data-action="previous"
    ${cursors.length ? '' : 'disabled'}>이전</button><button type="button" class="btn small" data-action="next"
    ${page.hasMore && page.nextCursor ? '' : 'disabled'}>다음</button></div>`;
}

function sampleLinks() {
  return `<a class="btn small" href="./source/samples/events-r02.csv" download>정상 샘플 7건</a>
    <a class="btn small" href="./source/samples/events-invalid-customer.csv" download>고객 참조 오류 2건</a>
    <a class="btn small" href="./source/samples/events-boundaries.csv" download>경계 샘플 41건</a>`;
}

function importResult() {
  const result = state.results.import;
  return result ? `<div class="notice section-gap" role="status">최근 입력 응답: 전체 ${num(result.totalRows)}건 ·
    신규 저장 ${num(result.insertedRows)}건 · 동일 중복 ${num(result.duplicateRows)}건</div>` : '';
}

function logsPage(page) {
  const filter = state.logFilters;
  return heading('접속 기록', '가상 인증 서버가 확정한 로그인 결과와 고객의 이전 활동을 확인하세요.',
    state.actor.role === 'ADMIN' ? '<button type="button" class="btn primary" data-action="import-dialog">로그 입력</button>' : '') + `
    <section class="panel"><form id="log-filter" class="filterbar">
    <label class="field"><span>고객 참조</span><input name="account" maxlength="40" placeholder="C1001" value="${esc(filter.account || '')}"></label>
    <label class="field"><span>로그인 결과</span><select name="type">${option('', '전체', filter.type || '')}
    ${['LOGIN_FAILED', 'LOGIN_SUCCESS'].map(value => option(value, labels[value], filter.type)).join('')}</select></label>
    <label class="field" style="min-width:min(100%,240px)"><span>시작 · KST</span><input style="min-width:0" type="datetime-local" name="fromLocal" step="1" value="${esc(toLocal(filter.from))}"></label>
    <label class="field" style="min-width:min(100%,240px)"><span>종료 미만 · KST</span><input style="min-width:0" type="datetime-local" name="toLocal" step="1" value="${esc(toLocal(filter.to))}"></label>
    <button type="submit" class="btn primary">검색</button><button type="button" class="btn" data-action="clear-filter">초기화</button>
    </form><div class="table-wrap"><table><thead><tr><th>이벤트 ID</th><th>고객</th><th>발생 시각 · KST / 원본</th>
    <th>로그인 결과</th><th>기기</th><th>신규 기기</th></tr></thead><tbody>${page.items.map(event => `
    <tr><td class="mono">${esc(event.event_id)}</td><td class="table-primary">${esc(event.customer_ref)}
    <div class="table-secondary">${esc(event.customer_name)}</div></td><td>${esc(fmt(event.occurred_at))}
    <div class="table-secondary mono">${esc(event.occurred_at)}</div></td><td>${esc(labels[event.event_type] || event.event_type)}</td>
    <td class="mono">${esc(event.device_ref)}</td><td>${event.is_new_device == null ? '해당 없음' : event.is_new_device ? '신규' : '기존'}</td></tr>`).join('')}
    </tbody></table></div>${page.items.length ? '' : empty('검색된 접속 기록이 없습니다.')}
    <div class="table-footer"><span>조건 전체 ${num(page.total)}건 · 현재 페이지 ${num(page.items.length)}건</span>
    ${pager(state.logCursors, page)}</div></section>${importResult()}
    <section class="panel section-gap"><div class="panel-header"><h2>고정 10분 구간·결과별 집계</h2></div>
    <div class="table-wrap"><table><thead><tr><th>구간 시작 · KST</th><th>로그인 결과</th><th>건수</th></tr></thead><tbody>
    ${page.aggregates.map(row => `<tr><td>${esc(fmt(row.bucket_start))}</td><td>${esc(labels[row.event_type])}</td>
    <td>${num(row.event_count)}</td></tr>`).join('')}</tbody></table></div>
    ${page.aggregates.length ? '' : empty('집계할 기록이 없습니다.')}</section>
    <div class="inline-row section-gap">${sampleLinks()}</div>
    <p class="footer-note">집계는 현재 검색 조건 전체이며 페이지 이동의 영향을 받지 않습니다. 원본 시각과 이벤트 ID를 함께 보존합니다.</p>`;
}

function detectionResult() {
  const result = state.results.detection;
  return result ? `<div class="notice section-gap" role="status">최근 탐지 응답: 후보 성공 ${num(result.candidateSuccesses)}건 ·
    후보 구간 ${num(result.candidateBuckets)}개 · 신규 경보 ${num(result.createdAlerts)}건 ·
    기존 경보 제외 ${num(result.existingAlerts)}건 · 신규 근거 ${num(result.insertedEvidenceRows)}건</div>` : '';
}

function alertsPage(page, detail) {
  const filter = state.alertFilters;
  return heading('경보·사건', 'R02 · 같은 고객의 고정 10분 구간에서 신규 기기 성공보다 앞선 실패가 5건 이상이면 조사 후보입니다.',
    (state.actor.role === 'ADMIN' ? '<button type="button" class="btn primary" data-action="detect-dialog">탐지 실행</button>' : '') +
    '<button type="button" class="btn" data-action="refresh">새로고침</button>') + `
    <div class="split"><section class="panel"><div class="panel-header"><h2>탐지된 경보</h2>
    <span class="tiny muted">현재 페이지 ${num(page.items.length)}건</span></div>
    <form id="alert-filter" class="filterbar"><label class="field"><span>고객 참조</span>
    <input name="account" maxlength="40" placeholder="C1001" value="${esc(filter.account || '')}"></label>
    <label class="field"><span>사건 상태</span><select name="status">${option('', '전체', filter.status || '')}
    ${['UNASSIGNED', 'IN_PROGRESS', 'CLOSED'].map(value => option(value, labels[value], filter.status)).join('')}</select></label>
    <label class="field"><span>담당 범위</span><select name="mine">${option('', '전체 경보', filter.mine || '')}
    ${option('true', '내 담당 사건', filter.mine)}</select></label><button type="submit" class="btn small">적용</button>
    <button type="button" class="btn small" data-action="clear-filter">초기화</button></form>
    <div class="alert-list">${page.items.map(alert => `<button type="button" class="alert-card ${state.selected === alert.alert_id ? 'selected' : ''}"
      data-alert="${esc(alert.alert_id)}" aria-pressed="${state.selected === alert.alert_id}">
      <div class="alert-card-top"><span class="mono muted tiny">ALT-${esc(alert.alert_id)}</span>${badge(alert.processing_status)}</div>
      <div class="alert-card-title">${esc(alert.rule_name)}</div><div class="alert-card-meta"><span>${esc(alert.customer_ref)} · ${esc(alert.rule_code)}</span>
      <span>${esc(fmt(alert.bucket_start, false))}~${esc(fmt(alert.bucket_end, false))}</span></div>
      <div class="alert-card-meta"><span>${esc(alert.assignee_name || '담당자 미배정')}</span>${badge(alert.access_status)}</div></button>`).join('')}</div>
    ${page.items.length ? '' : empty('조건에 맞는 경보가 없습니다. 접속 기록을 입력한 뒤 탐지를 실행하세요.')}
    <div class="table-footer">${pager(state.alertCursors, page)}</div></section>${details(detail)}</div>
    ${detectionResult()}<p class="footer-note">미배정·조사 중·종료는 사건 진행 상태입니다. 계정의 보호 잠금은 사건 종료 후에도 유지될 수 있습니다.</p>`;
}

function timeline(events) {
  return `<ol class="timeline">${events.map(event => `<li class="${event.is_anchor ? 'success' : ''}">
    <time class="timeline-time">${esc(fmt(event.occurred_at, false))}</time><div class="timeline-main">
    ${esc(labels[event.event_type] || event.event_type)} ${event.is_anchor ? '<span class="badge progress">대표 성공</span>' : ''}
    <div class="timeline-meta mono">${esc(event.occurred_at)} · ID ${esc(event.event_id)}</div>
    <div class="timeline-meta">기기 ${esc(event.device_ref)}${event.is_new_device === true ? ' · 신규 기기' : ''}</div></div></li>`).join('')}</ol>`;
}

function details(detail) {
  if (!detail) return `<section class="panel">${empty('경보를 선택하면 원본 근거와 대응 이력을 확인할 수 있습니다.')}</section>`;
  const alert = detail.alert;
  const ownedOpen = state.actor.role === 'ANALYST' && alert.is_mine === true && alert.processing_status === 'IN_PROGRESS';
  const unlock = state.actor.role === 'ADMIN' && alert.access_status === 'LOCKED' && alert.is_lock_cause === true;
  return `<section class="panel"><div class="panel-body"><div class="detail-heading">${badge(alert.processing_status)}
    ${badge(alert.access_status)}<span class="tiny muted">${esc(alert.rule_code)}</span></div>
    <h2 class="detail-title">${esc(alert.rule_name)}</h2><p class="detail-sub">${esc(alert.customer_ref)} · ${esc(alert.customer_name)}</p>
    <div class="fact-grid"><div><div class="fact-label">탐지 구간 · KST</div><div class="fact-value">${esc(fmt(alert.bucket_start))}
    ~ ${esc(fmt(alert.bucket_end, false))}</div></div><div><div class="fact-label">담당자</div>
    <div class="fact-value">${esc(alert.assignee_name || '미배정')}</div></div><div><div class="fact-label">저장된 원본 근거</div>
    <div class="fact-value">${num(detail.evidence.length)}건</div></div><div><div class="fact-label">계정 잠금 원인 사건</div>
    <div class="fact-value">${alert.lock_incident_id ? `INC-${esc(alert.lock_incident_id)}` : '없음'}</div></div></div>
    <h3>탐지 근거 · KST</h3>${timeline(detail.evidence)}
    <p class="tiny muted">대표 성공과 그보다 앞선 실패의 저장된 원본만 표시합니다. 성공과 같은 시각의 실패는 제외합니다.</p>
    ${!alert.incident_id && state.actor.role === 'ANALYST' ? `<div class="detail-actions">
      <button type="button" class="btn primary" data-action="claim">이 사건 맡기</button></div>` : ''}
    ${alert.incident_id ? `<div class="divider"></div><h3>조사와 대응</h3>
      <p class="tiny muted">INC-${esc(alert.incident_id)} · 버전 ${num(alert.incident_version)}</p>
      ${alert.closed_at ? `<div class="reason-card">종료 판정: ${esc(labels[alert.resolution] || alert.resolution)}
        <div>종료 시각: ${esc(fmt(alert.closed_at))}</div></div>` : ''}
      <div class="detail-actions">${ownedOpen && alert.access_status === 'ACTIVE' ? '<button type="button" class="btn danger" data-case="lock">보호 잠금</button>' : ''}
      ${ownedOpen ? '<button type="button" class="btn primary" data-case="close">사건 종료</button>' : ''}
      ${unlock ? '<button type="button" class="btn" data-case="unlock">잠금 해제</button>' : ''}</div>
      ${!ownedOpen && !unlock ? '<p class="footer-note">담당 관제 담당자는 조사 중인 사건을 조치하고, 관리자는 잠금 원인 사건에서 보호를 해제할 수 있습니다.</p>' : ''}
      <div class="divider"></div><h3>처리 이력</h3>${detail.history.map(history => `<div class="history-item">
      <div class="history-item-title"><span>${esc(labels[history.action_type] || history.action_type)} · ${esc(history.actor_name)}</span>
      <time class="tiny muted">${esc(fmt(history.created_at))}</time></div><p>${esc(history.reason)}</p></div>`).join('')}` : ''}
    </div></section>`;
}

function toLocal(value) {
  return value ? new Date(new Date(value).getTime() + 9 * 3600000).toISOString().slice(0, 19) : '';
}

function fromLocal(value) {
  if (!value) return '';
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(value)) throw new Error('시각을 확인하세요.');
  return value + (value.length === 16 ? ':00' : '') + '+09:00';
}

async function refresh() {
  shell();
  $('#content').innerHTML = '<div class="loading"><span class="spinner"></span><p>기록을 불러오고 있습니다.</p></div>';
  const logs = state.page === 'logs';
  const cursors = logs ? state.logCursors : state.alertCursors;
  const filters = logs ? state.logFilters : state.alertFilters;
  const page = await http((logs ? '/logs?' : '/alerts?') + new URLSearchParams({ ...filters, limit: '20', ...(cursors.at(-1) || {}) }));
  if (!logs) {
    if (!page.items.some(alert => alert.alert_id === state.selected)) state.selected = page.items[0]?.alert_id || null;
    state.detail = state.selected ? await http('/alerts/' + encodeURIComponent(state.selected)) : null;
  }
  state.data = page;
  $('#content').innerHTML = logs ? logsPage(page) : alertsPage(page, state.detail);
}

function modal(title, body, formId, label) {
  closeModal();
  const element = document.createElement('div');
  element.className = 'modal-backdrop';
  element.id = 'modal';
  element.innerHTML = `<section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-head"><h2 id="modal-title">${esc(title)}</h2><button type="button" class="btn quiet small" data-action="dismiss" aria-label="닫기">×</button></div>
    <form id="${formId}"><div class="modal-body">${body}<div class="form-error error-inline" role="alert" hidden></div></div>
    <div class="modal-footer"><button type="button" class="btn" data-action="dismiss">취소</button>
    <button type="submit" class="btn primary">${esc(label)}</button></div></form></section>`;
  state.modalFocus = state.taskFocus || document.activeElement;
  document.body.append(element);
  element.querySelector('input,textarea,select,button').focus();
}

function closeModal() {
  $('#modal')?.remove();
  state.action = null;
  const previous = state.modalFocus;
  if (previous?.isConnected) setTimeout(() => { if (previous.isConnected && !previous.disabled) previous.focus(); }, 0);
  state.modalFocus = null;
}

function openCase(action) {
  const alert = state.detail.alert;
  const ownedOpen = state.actor.role === 'ANALYST' && alert.is_mine && alert.processing_status === 'IN_PROGRESS';
  if (action === 'unlock' ? !(state.actor.role === 'ADMIN' && alert.is_lock_cause && alert.access_status === 'LOCKED')
    : !ownedOpen || action === 'lock' && alert.access_status !== 'ACTIVE') throw new Error('현재 상태에서 가능한 조치가 아닙니다. 새로고침하세요.');
  const title = { lock: '보호 잠금', unlock: '잠금 해제', close: '사건 종료' }[action];
  if (!title || !Number.isInteger(alert.incident_version) || alert.incident_version < 0) throw new Error('사건 정보를 다시 확인하세요.');
  const key = alert.incident_id + ':' + action;
  modal(title, `${action === 'close' ? '<div class="notice">사건을 종료해도 현재 보호 잠금은 유지됩니다.</div>' : ''}
    <p class="small muted">INC-${esc(alert.incident_id)} · 확인 버전 ${num(alert.incident_version)}</p>
    <label class="field"><span>조치 사유 · 1~2,000자</span><textarea name="reason" required maxlength="4000">${esc(state.drafts.get(key) || '')}</textarea></label>
    ${action === 'close' ? `<label class="field"><span>종료 판정</span><select name="resolution">
      ${['CONFIRMED', 'FALSE_POSITIVE', 'INCONCLUSIVE'].map(value => option(value, labels[value])).join('')}</select></label>` : ''}`,
  'case-form', title + ' 저장');
  // Keep the version observed when the operator opened the form; never replace it before POST.
  state.action = { incidentId: alert.incident_id, version: alert.incident_version, action, key };
}

function openImport() {
  if (state.actor.role !== 'ADMIN') throw new Error('관리자만 로그를 입력할 수 있습니다.');
  modal('CSV 로그 입력', `<p class="small muted">UTF-8 CSV 한 파일, 최대 2 MiB·5,000행입니다.
    잘못된 고객 참조나 내용 충돌이 있으면 이번 입력의 새 기록 전체를 취소합니다.</p>
    <label class="field"><span>CSV 파일</span><input type="file" name="file" accept=".csv,text/csv" required></label>
    <div class="inline-row">${sampleLinks()}</div>`, 'import-form', '검사 후 저장');
}

function openDetection() {
  if (state.actor.role !== 'ADMIN') throw new Error('관리자만 탐지를 실행할 수 있습니다.');
  modal('R02 탐지 실행', `<p class="small muted">고정 10분 경계의 시작·종료를 선택하세요. 종료 시각은 포함하지 않으며 최대 24시간입니다.
    같은 구간의 첫 후보 성공이 대표가 됩니다.</p>
    <label class="field"><span>시작 · KST</span><input type="datetime-local" name="from" step="600" value="2026-11-02T10:00" required></label>
    <label class="field"><span>종료 미만 · KST</span><input type="datetime-local" name="to" step="600" value="2026-11-02T10:10" required></label>`,
  'detect-form', '탐지 시작');
}

async function submitCase(values) {
  const context = state.action;
  if (!context) throw new Error('조치 창을 다시 열어 주세요.');
  const reason = String(values.reason || '').trim();
  if (!reason || Array.from(reason).length > 2000) throw new Error('조치 사유를 1~2,000자로 입력하세요.');
  const body = { version: context.version, reason };
  if (context.action === 'close') body.resolution = values.resolution;
  await http('/incidents/' + encodeURIComponent(context.incidentId) + '/' + context.action, 'POST', body);
  state.drafts.delete(context.key);
  closeModal();
  await refresh();
  toast('조치와 이력을 저장했습니다.');
}

async function submitImport(form) {
  if (state.actor.role !== 'ADMIN') throw new Error('관리자만 로그를 입력할 수 있습니다.');
  const file = form.elements.file.files[0];
  if (!file || file.size === 0) throw new Error('CSV 파일을 선택하세요.');
  if (file.size > 2097152) throw new Error('CSV 파일은 2 MiB 이하여야 합니다.');
  const body = new FormData();
  body.append('file', file);
  state.results.import = await http('/logs/import', 'POST', body);
  closeModal();
  state.logCursors = [];
  await refresh();
  toast('로그 입력 응답을 확인했습니다.');
}

async function submitDetection(values) {
  if (state.actor.role !== 'ADMIN') throw new Error('관리자만 탐지를 실행할 수 있습니다.');
  const from = fromLocal(values.from), to = fromLocal(values.to);
  const start = Date.parse(from), end = Date.parse(to);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start % 600000 || end % 600000
      || end <= start || end - start > 86400000) throw new Error('최대 24시간, 고정 10분 경계의 구간을 선택하세요.');
  state.results.detection = await http('/alerts/detect', 'POST', { from, to });
  closeModal();
  state.alertCursors = [];
  state.selected = null;
  await refresh();
  toast('탐지 응답을 확인했습니다.');
}

async function submitFilter(form, values) {
  const logs = form.id === 'log-filter';
  const filter = {};
  for (const key of logs ? ['account', 'type'] : ['account', 'status', 'mine']) {
    if (values[key]) filter[key] = values[key];
  }
  if (logs) {
    if (values.fromLocal) filter.from = fromLocal(values.fromLocal);
    if (values.toLocal) filter.to = fromLocal(values.toLocal);
    if (filter.from && filter.to && Date.parse(filter.from) >= Date.parse(filter.to)) throw new Error('시작 시각은 종료 시각보다 앞서야 합니다.');
    state.logFilters = filter;
    state.logCursors = [];
  } else {
    state.alertFilters = filter;
    state.alertCursors = [];
    state.selected = null;
  }
  await refresh();
}

async function clickAction(action) {
  if (action === 'dismiss') return closeModal();
  if (action === 'retry') return boot();
  if (action === 'logout') {
    await http('/logout', 'POST');
    clearPrivateState();
    state.actor = null;
    state.csrf = '';
    return boot();
  }
  if (!state.actor) return;
  if (action === 'refresh') return refresh();
  if (action === 'import-dialog') return openImport();
  if (action === 'detect-dialog') return openDetection();
  if (action === 'claim') {
    const alert = state.detail?.alert;
    if (state.actor.role !== 'ANALYST' || !alert || alert.incident_id) throw new Error('미배정 경보를 확인하세요.');
    await http('/alerts/' + encodeURIComponent(alert.alert_id) + '/claim', 'POST', {});
    await refresh();
    return toast('사건을 맡았습니다.');
  }
  const logs = state.page === 'logs';
  const cursors = logs ? state.logCursors : state.alertCursors;
  if (action === 'next' && state.data?.hasMore && state.data.nextCursor) cursors.push({ ...state.data.nextCursor });
  if (action === 'previous') cursors.pop();
  if (action === 'clear-filter') {
    if (logs) { state.logFilters = {}; state.logCursors = []; }
    else { state.alertFilters = {}; state.alertCursors = []; state.selected = null; }
  }
  if (['next', 'previous', 'clear-filter'].includes(action)) await refresh();
}

function formError(form, message) {
  let element = form?.querySelector('.form-error');
  if (form && !element) {
    element = document.createElement('div');
    element.className = 'form-error error-inline';
    element.setAttribute('role', 'alert');
    form.append(element);
  }
  if (element?.isConnected) { element.textContent = message; element.hidden = false; }
  else showNotice(message);
  toast(message);
}

async function failure(error, form) {
  if (error.status === 401 && form?.id === 'real-login') {
    if (form.elements.password) form.elements.password.value = '';
    return formError(form, '로그인 정보를 확인하세요.');
  }
  if (error.status === 401 || error.status === 403) {
    closeModal();
    try { await boot(); } catch (sessionError) { connectionError(sessionError); }
    return formError($('#real-login'), error.status === 401 ? '세션이 만료됐습니다. 다시 로그인하세요.'
      : '권한 또는 보안 토큰을 확인하세요. 세션을 갱신했으며 요청은 다시 보내지 않았습니다.');
  }
  if (error.status === 409) {
    closeModal();
    showNotice(error.message + ' 최신 상태를 확인한 뒤 조치를 다시 선택하세요. 작성한 사유는 보관했습니다.');
    try { await refresh(); } catch (refreshError) { formError(null, refreshError.message); }
    return toast(state.notice);
  }
  formError(form, error.message);
  const loading = $('#content .loading');
  if (loading) loading.innerHTML = '<p>기록을 가져오지 못했습니다.</p><button type="button" class="btn" data-action="refresh">다시 조회</button>';
}

async function perform(task, form = null) {
  if (state.busy) return;
  state.busy = true;
  state.taskFocus = document.activeElement;
  const controls = [...document.querySelectorAll('button,input,select,textarea')].filter(element => !element.disabled);
  controls.forEach(element => { element.disabled = true; });
  showNotice('');
  form?.querySelector('.form-error')?.setAttribute('hidden', '');
  try { await task(); }
  catch (error) { await failure(error, form); }
  finally {
    state.busy = false;
    state.taskFocus = null;
    controls.forEach(element => { if (element.isConnected) element.disabled = false; });
  }
}

function login() {
  root.innerHTML = '<div class="login-layout"><section class="login-brand"><span class="logo-mark">BW</span>'+
    '<h1>근거를 연결하고,<br>대응을 직접 경험하세요.</h1><p>고정 10분 R02, 원본 근거와 사건 이력을 가상 데이터로 살펴봅니다.</p></section>'+
    '<section class="login-box"><form class="login-form" id="real-login"><div><div class="eyebrow">BANKWATCH · PAGES DEMO</div>'+
    '<h2>체험 역할 선택</h2><p class="description">실제 운영자 계정이나 비밀번호가 필요하지 않습니다.</p></div>'+
    '<label class="field"><span>역할</span><select name="username">'+BankWatchDemo.roles.map(role=>'<option value="'+esc(role.login_id)+'">'+esc(role.display_name)+'</option>').join('')+'</select></label>'+
    '<button type="submit" class="btn primary">체험 시작</button><div class="login-note">가상 데이터는 이 브라우저에만 저장됩니다. PostgreSQL을 사용하는 실제 앱의 테스트 결과는 홈페이지에서 확인하세요.</div>'+
    '<a href="../">개인 홈페이지로</a><div class="form-error error-inline" role="alert" hidden></div></form></section></div>';
}

function connectionError(error) {
  clearPrivateState();
  state.actor = null;
  state.csrf = '';
  root.innerHTML = `<div class="boot"><span class="logo-mark">BW</span><h2>연결을 확인해 주세요</h2>
    <p role="alert">${esc(error.message)}</p><button type="button" class="btn" data-action="retry">다시 연결</button></div>`;
}

async function boot() {
  closeModal();
  await session();
  if (!state.actor) return login();
  await refresh();
}

document.addEventListener('input', event => {
  if (event.target.name === 'reason' && state.action) state.drafts.set(state.action.key, event.target.value);
});

document.addEventListener('keydown', event => {
  const dialog = $('#modal');
  if (!dialog) return;
  if (event.key === 'Escape' && !state.busy) { event.preventDefault(); closeModal(); }
  if (event.key === 'Tab') {
    const controls = [...dialog.querySelectorAll('button,input,select,textarea,a[href]')].filter(element => !element.disabled);
    const index = controls.indexOf(document.activeElement);
    if (controls.length && (index < 0 || !event.shiftKey && index === controls.length - 1 || event.shiftKey && index === 0)) {
      event.preventDefault();
      controls[event.shiftKey ? controls.length - 1 : 0].focus();
    }
  }
});

document.addEventListener('click', async event => {
  const navButton = event.target.closest('[data-nav]');
  const alertButton = event.target.closest('[data-alert]');
  const caseButton = event.target.closest('[data-case]');
  const actionButton = event.target.closest('[data-action]');
  if (!navButton && !alertButton && !caseButton && !actionButton) return;
  event.preventDefault();
  await perform(async () => {
    if (navButton) {
      if (!nav.some(([key]) => key === navButton.dataset.nav)) return;
      state.page = navButton.dataset.nav;
      await refresh();
    } else if (alertButton) {
      state.selected = alertButton.dataset.alert;
      await refresh();
    } else if (caseButton) openCase(caseButton.dataset.case);
    else await clickAction(actionButton.dataset.action);
  });
});

document.addEventListener('submit', async event => {
  event.preventDefault();
  if (state.busy) return;
  const form = event.target;
  const values = Object.fromEntries(new FormData(form));
  await perform(async () => {
    if (form.id === 'real-login') {
      await http('/login', 'POST', new URLSearchParams({ username: values.username, password: '' }));
      await boot();
    } else if (['log-filter', 'alert-filter'].includes(form.id)) await submitFilter(form, values);
    else if (form.id === 'import-form') await submitImport(form);
    else if (form.id === 'detect-form') await submitDetection(values);
    else if (form.id === 'case-form') await submitCase(values);
  }, form);
});

state.busy = true;
boot().catch(connectionError).finally(() => { state.busy = false; });

async function sampleInput(name) {
  if (state.actor?.role !== 'ADMIN') throw new Error('관리자 역할을 먼저 선택하세요.');
  const response = await fetch('./source/samples/'+name);
  if (!response.ok) throw new Error('샘플을 가져오지 못했습니다.');
  const body = new FormData();
  body.append('file', new File([await response.blob()], name, {type:'text/csv'}));
  state.results.import = await http('/logs/import', 'POST', body);
  state.page='logs';state.logFilters={};state.logCursors=[];
  await refresh();toast('신규 '+state.results.import.insertedRows+'건 · 중복 '+state.results.import.duplicateRows+'건');
}
document.getElementById('demo-role').addEventListener('change', async event => {
  const name=event.target.value;
  if (!name) return;
  await perform(async()=>{BankWatchDemo.selectRole(name);await boot();});
});
document.getElementById('demo-normal').addEventListener('click',()=>perform(()=>sampleInput('events-r02.csv')));
document.getElementById('demo-invalid').addEventListener('click',()=>perform(()=>sampleInput('events-invalid-customer.csv')));
document.getElementById('demo-reset').addEventListener('click',async()=>{
  if (!confirm('이 브라우저의 BankWatch 체험 데이터와 이력을 초기화할까요?')) return;
  await perform(async()=>{await BankWatchDemo.reset();clearPrivateState();await boot();toast('체험 데이터를 초기화했습니다.');});
});
window.addEventListener('storage',event=>{
  if(event.key==='bankwatch-pages-v110-data') showNotice('다른 탭에서 체험 데이터가 바뀌었습니다. 새로고침해 최신 상태를 확인하세요.');
});
