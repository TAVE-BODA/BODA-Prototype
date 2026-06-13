let pendingChipType = null;

// ── 파일 업로드 ──────────────────────────────────────────────

function handleDrop(e, type) {
    e.preventDefault();
    const file = e.dataTransfer.files[0];
    if (file) uploadFile(file, type);
}
function handleFile(e, type) {
    const file = e.target.files[0];
    if (file) uploadFile(file, type);
}

async function uploadFile(file, type) {
    const nameEl   = document.getElementById(`${type}-file-name`);
    const statusEl = document.getElementById(`${type}-status`);
    nameEl.textContent = `📎 ${file.name}`;
    setStatus(statusEl, 'loading', '업로드 중...');

    const fd = new FormData();
    fd.append('file', file);

    try {
        const res  = await fetch(`/api/upload/${type}`, { method: 'POST', body: fd });
        const data = await res.json();
        if (!res.ok) { setStatus(statusEl, 'error', data.error || '오류가 발생했어요.'); return; }
        setStatus(statusEl, 'ok', '✅ ' + data.message);
        startPolling();
    } catch {
        setStatus(statusEl, 'error', '네트워크 오류가 발생했어요.');
    }
}

function setStatus(el, type, msg) {
    el.className = `upload-status ${type}`;
    el.textContent = msg;
}

// ── 상태 폴링 ─────────────────────────────────────────────────

let pollingTimer = null;

function startPolling() {
    if (pollingTimer) return;
    showBanner('분석 중이에요...');
    pollingTimer = setInterval(async () => {
        try {
            const data = await (await fetch('/api/upload/status')).json();
            const pDone = data.policyState === 'DONE';
            const tDone = data.termsState  === 'DONE' || data.termsState === 'NONE';
            if (pDone && tDone) {
                clearInterval(pollingTimer); pollingTimer = null;
                hideBanner();
                addBotMessage('📋 분석이 완료됐어요! 이제 질문해보세요 😊', null);
            } else if (data.policyState === 'ERROR') {
                clearInterval(pollingTimer); pollingTimer = null;
                hideBanner();
                addBotMessage('⚠️ 분석 중 오류가 발생했어요. 다시 업로드해주세요.', null);
            }
        } catch { /* 무시 */ }
    }, 2000);
}

function showBanner(msg) {
    document.getElementById('banner-text').textContent = msg;
    document.getElementById('analysis-banner').classList.remove('hidden');
}
function hideBanner() {
    document.getElementById('analysis-banner').classList.add('hidden');
}

// ── 칩 선택 ──────────────────────────────────────────────────

function selectChip(chipType, label) {
    if (chipType !== 'OVERVIEW') {
        pendingChipType = chipType;
        openModal();
    } else {
        sendChip(chipType, label);
    }
}

function sendChip(chipType, label) {
    addUserMessage(label);
    fetchAnswer(label, chipType);
}

// ── 보험 조건 팝업 ────────────────────────────────────────────

function openModal() {
    document.getElementById('condition-modal').classList.remove('hidden');
    document.getElementById('modal-overlay').classList.remove('hidden');
}

function closeModal() {
    document.getElementById('condition-modal').classList.add('hidden');
    document.getElementById('modal-overlay').classList.add('hidden');
    if (pendingChipType) {
        const labels = { CLAIM: '보험금 청구할 수 있어요?', AMOUNT: '얼마나 받을 수 있어요?', DOCUMENTS: '어떤 서류가 필요해요?' };
        sendChip(pendingChipType, labels[pendingChipType]);
        pendingChipType = null;
    }
}

async function submitCondition() {
    const treatment = document.getElementById('cond-treatment').value.trim();
    const hospital  = document.querySelector('input[name="hospital"]:checked');
    if (!treatment) { alert('치료/사고 유형을 입력해주세요.'); return; }
    if (!hospital)  { alert('병원 이용 방식을 선택해주세요.'); return; }

    await fetch('/api/chat/condition', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            treatmentType: treatment,
            hospitalUsage: hospital.value,
            treatmentDate: document.getElementById('cond-date').value || null,
            estimatedCost: document.getElementById('cond-cost').value || null
        })
    });

    document.getElementById('condition-modal').classList.add('hidden');
    document.getElementById('modal-overlay').classList.add('hidden');

    if (pendingChipType) {
        const labels = { CLAIM: '보험금 청구할 수 있어요?', AMOUNT: '얼마나 받을 수 있어요?', DOCUMENTS: '어떤 서류가 필요해요?' };
        sendChip(pendingChipType, labels[pendingChipType]);
        pendingChipType = null;
    }
}

// ── 메시지 전송 ───────────────────────────────────────────────

function sendMessage() {
    const input = document.getElementById('chat-input');
    const msg = input.value.trim();
    if (!msg) return;
    input.value = '';
    addUserMessage(msg);
    fetchAnswer(msg, null);
}

async function fetchAnswer(message, chipType) {
    const loadingId = addBotMessage('...', null, true);
    try {
        const res  = await fetch('/api/chat/message', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message, chipType })
        });
        const data = await res.json();
        removeMessage(loadingId);
        addBotMessage(data.answer, data.evidence || null);
    } catch {
        removeMessage(loadingId);
        addBotMessage('⚠️ 오류가 발생했어요. 잠시 후 다시 시도해주세요.', null);
    }
}

// ── 메시지 렌더링 ─────────────────────────────────────────────

let msgCounter = 0;

function addUserMessage(text) {
    const id = `msg-${++msgCounter}`;
    const el = document.createElement('div');
    el.id = id; el.className = 'msg user';
    el.innerHTML = `<div class="msg-bubble">${esc(text)}</div>`;
    msgs().appendChild(el); scroll();
    return id;
}

function addBotMessage(text, evidence, isLoading = false) {
    const id = `msg-${++msgCounter}`;
    const el = document.createElement('div');
    el.id = id; el.className = 'msg bot';
    let inner = `<div class="msg-avatar">🤖</div><div>
        <div class="msg-bubble">${isLoading ? '<span class="spinner" style="border-color:var(--border);border-top-color:var(--primary)"></span>' : md(text)}</div>`;
    if (evidence && !isLoading)
        inner += `<details class="evidence-toggle"><summary>📋 근거 보러가기</summary><div class="evidence-box">${esc(evidence)}</div></details>`;
    inner += '</div>';
    el.innerHTML = inner;
    msgs().appendChild(el); scroll();
    return id;
}

function removeMessage(id) { document.getElementById(id)?.remove(); }
function msgs()  { return document.getElementById('chat-messages'); }
function scroll(){ const m = msgs(); m.scrollTop = m.scrollHeight; }
function esc(s)  { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function md(s)   { return esc(s).replace(/\*\*(.*?)\*\*/g,'<strong>$1</strong>').replace(/\n/g,'<br>'); }
