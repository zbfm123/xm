/* ===================================================================
   前端共享 API 封装与工具

   三条纪律，都是从后端踩过的坑反推出来的：
   1. **错误码原样保留**——后端把 AI 失败映射成 503/429/502 并带
      degradable/retryable，前端不能把它压成一句"请求失败"，
      否则用户无法区分"AI 不可用（可继续）"与"服务端崩了"。
   2. **分页对外 1 基**——后端契约如此，前端不要自己减一。
   3. **Token 只在内存 + localStorage，不写 cookie**——避免 CSRF。
   =================================================================== */

const TOKEN_KEY = 'cr_token';
const USER_KEY = 'cr_user';

const Auth = {
    get token() { return localStorage.getItem(TOKEN_KEY); },
    get user() {
        try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); }
        catch { return null; }
    },
    save(token, user) {
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USER_KEY, JSON.stringify(user || {}));
    },
    clear() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
    },
    /** 未登录则跳登录页，并记住来路。 */
    require() {
        if (!this.token) {
            location.href = './index.html?next=' + encodeURIComponent(location.pathname + location.search);
            return false;
        }
        return true;
    },
    logout() {
        // 先通知后端拉黑令牌，再清本地。失败也清本地——
        // 用户点了登出就该登出，不能因为网络问题把他留在登录态。
        api('POST', '/api/auth/logout').catch(() => {}).finally(() => {
            this.clear();
            location.href = './index.html';
        });
    }
};

/**
 * 统一请求封装。
 *
 * 返回 { ok, status, data }，**不抛异常**——页面用 if 判断即可，
 * 不必到处写 try/catch。网络异常也归一成 ok=false。
 */
async function api(method, path, body) {
    const headers = { 'Accept': 'application/json' };
    let payload;

    if (body !== undefined) {
        headers['Content-Type'] = 'application/json; charset=utf-8';
        payload = JSON.stringify(body);
    }
    if (Auth.token) {
        headers['Authorization'] = 'Bearer ' + Auth.token;
    }

    try {
        const res = await fetch(path, { method, headers, body: payload });
        // 401：令牌过期或已失效，直接回登录页
        if (res.status === 401) {
            Auth.clear();
            location.href = './index.html?expired=1';
            return { ok: false, status: 401, data: null };
        }
        const text = await res.text();
        let data = null;
        if (text) {
            try { data = JSON.parse(text); } catch { data = { raw: text }; }
        }
        return { ok: res.ok, status: res.status, data };
    } catch (e) {
        // 网络层失败：与"服务端返回错误"区分开，提示文案不同
        return { ok: false, status: 0, data: { code: 'NETWORK_ERROR', message: '网络请求失败：' + e.message } };
    }
}

/** 上传文件（multipart）。 */
async function apiUpload(path, file, title) {
    const form = new FormData();
    form.append('file', file);
    if (title) form.append('title', title);

    try {
        const res = await fetch(path, {
            method: 'POST',
            headers: Auth.token ? { 'Authorization': 'Bearer ' + Auth.token } : {},
            body: form
        });
        if (res.status === 401) {
            Auth.clear();
            location.href = './index.html?expired=1';
            return { ok: false, status: 401, data: null };
        }
        const text = await res.text();
        let data = null;
        if (text) {
            try { data = JSON.parse(text); } catch { data = { raw: text }; }
        }
        return { ok: res.ok, status: res.status, data };
    } catch (e) {
        return { ok: false, status: 0, data: { code: 'NETWORK_ERROR', message: '上传失败：' + e.message } };
    }
}

/* ---------------- 展示工具 ---------------- */

function esc(s) {
    return String(s === null || s === undefined ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function qs(name) {
    return new URLSearchParams(location.search).get(name);
}

/**
 * 规则三态 → 徽标。
 *
 * ⚠️ UNDETERMINED 必须是灰色而不是绿色。
 * 如果"无法判定"看起来像"通过"，三态设计在视觉上就退化成两态了——
 * 这正是整个项目最想避免的事。
 */
function ruleTag(result) {
    const map = {
        HIT: ['err', '命中'],
        PASS: ['ok', '通过'],
        UNDETERMINED: ['unknown', '无法判定']
    };
    const [cls, label] = map[result] || ['unknown', result || '?'];
    return `<span class="tag ${cls}">${esc(label)}</span>`;
}

/** AI 结论状态 → 徽标。所有状态都是"候选"，没有"已生效"。 */
function aiTag(status) {
    const map = {
        PENDING: ['warn', '待人工采信'],
        LOW_CONFIDENCE: ['warn', '置信度偏低'],
        EVIDENCE_MISMATCH: ['err', '证据无法定位'],
        EVIDENCE_AMBIGUOUS: ['err', '证据多处命中'],
        ACCEPTED: ['ok', '已采纳'],
        REJECTED: ['unknown', '已驳回'],
        ESCALATED: ['warn', '已升级'],
        NEED_INFO: ['warn', '待补充'],
        CONFIRMED_NO_RISK: ['ok', '确认无风险']
    };
    const [cls, label] = map[status] || ['unknown', status || '?'];
    return `<span class="tag ${cls}">${esc(label)}</span>`;
}

function taskTag(status) {
    const map = {
        PENDING: ['unknown', '待处理'],
        IN_PROGRESS: ['warn', '处理中'],
        AI_UNAVAILABLE: ['warn', 'AI 不可用（已降级）'],
        AWAITING_REVIEW: ['warn', '待人工复核'],
        COMPLETED: ['ok', '已完成'],
        CANCELLED: ['unknown', '已取消']
    };
    const [cls, label] = map[status] || ['unknown', status || '?'];
    return `<span class="tag ${cls}">${esc(label)}</span>`;
}

function fmtTime(s) {
    if (!s) return '';
    try { return new Date(s).toLocaleString('zh-CN', { hour12: false }); }
    catch { return s; }
}

function fmtSize(bytes) {
    if (bytes === null || bytes === undefined) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

/** 把响应里的错误渲染成可读提示；**保留错误码与 degradable**。 */
function errorHtml(res, fallback) {
    if (!res || !res.data) return `<div class="alert err">${esc(fallback || '请求失败')}</div>`;

    const code = res.data.code || ('HTTP ' + res.status);
    const msg = res.data.message || fallback || '请求失败';

    let hint = '';
    if (res.data.degradable) {
        // 这是本项目的核心可区分性：降级 ≠ 崩溃
        hint = '<br><b>这是可继续的状态</b>：规则结论与要素抽取均已保留，人工复核流程照常可用。';
    } else if (res.status === 0) {
        hint = '<br>请确认后端服务正在运行（<span class="mono">.\\run-dev.ps1</span>）。';
    }

    return `<div class="alert err"><b>${esc(code)}</b>：${esc(msg)}${hint}</div>`;
}

/** 统一顶栏。当前页高亮。 */
function renderTopbar(active) {
    const u = Auth.user || {};
    const nav = (href, label, key) =>
        `<a class="nav ${active === key ? 'on' : ''}" href="${href}">${esc(label)}</a>`;

    document.body.insertAdjacentHTML('afterbegin', `
        <div class="topbar">
            <div class="brand">合同智能审查平台<span>证据可核验 · 结论可追溯</span></div>
            ${nav('./contracts.html', '合同列表', 'list')}
            ${nav('./report.html', '审查报告', 'report')}
            <div class="spacer"></div>
            <div class="who">已登录：<b>${esc(u.displayName || u.username || '-')}</b>
                ${u.role ? '（' + esc(u.role) + '）' : ''}</div>
            <button class="ghost sm" onclick="Auth.logout()">登出</button>
        </div>`);
}

/** 简易提示（右上角浮层）。 */
function toast(msg, kind) {
    const el = document.createElement('div');
    el.className = 'alert ' + (kind || 'info');
    el.style.cssText = 'position:fixed;right:20px;top:64px;z-index:99;max-width:380px;'
        + 'box-shadow:0 4px 16px rgba(0,0,0,.12);margin:0';
    el.innerHTML = esc(msg);
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 4000);
}
