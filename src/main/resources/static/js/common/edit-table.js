/* ============================================================
 * edit-table.js — 공통 편집 표 (위즈모 FlexGrid 대체, 라이브러리 없음)
 * 짝 CSS : edit-table.css (+ 화면 틀 ms-page.css)
 *
 * 사용 예)
 *   const grid = new EditTable('#acGrid', {
 *     columns: [
 *       {key:'acccd', header:'계정코드', width:80,  readOnly:true, align:'center'},
 *       {key:'accnm', header:'계정명칭', width:'1.4*', maxLength:100},
 *       {key:'drcr',  header:'차대', width:80, type:'select', options:[{v:'1',n:'차변'},{v:'2',n:'대변'}]},
 *       {key:'spyn',  header:'전표', width:60, type:'check'},
 *       {key:'useyn', header:'사용여부', width:170, type:'radio', options:[{v:'1',n:'예'},{v:'0',n:'아니오'}]},
 *       {key:'seq',   header:'순서', width:80, type:'number'}
 *     ],
 *     onChange: (row, key, value, grid) => {},   // 칸을 고친 직후
 *     onSelect: (row, grid) => {},               // 현재 행이 바뀐 직후
 *     countEl : '#acCount',                       // 건수 표시할 곳 (선택)
 *     saveKey : 'acc',                            // 주면 칸 너비 · 쪽당 행 수를 브라우저에 기억 (선택)
 *     pageSize: 50                                // 주면 페이징 (선택, 아래 '페이징' 참고)
 *   });
 *   grid.setData(list);      grid.getChanged();     grid.addRow({...}, {after: grid.current()});
 *   grid.removeRow(row);     grid.setFilter(fn);    grid.exportCsv('파일명');
 *
 * 칸 속성
 *   key, header, width(px 숫자 또는 '1*' 비율), align('left'|'center'|'right')
 *   type     : 'text'(기본) | 'number' | 'check' | 'select' | 'radio'
 *   readOnly : true 또는 함수(row) → 보기 전용 (예: 새 행만 코드 입력 → row => !row._new)
 *   options  : select / radio 선택지 [{v, n}]
 *   on, off  : check 의 값 (기본 '1' / '0')
 *   maxLength, placeholder, wrap(true 면 긴 글자 줄바꿈), format(값→표시글자, 보기전용 칸)
 *   sortable : false 면 그 칸은 정렬 안 함
 *   resizable: false 면 그 칸은 너비 조절 안 함 (표 전체는 opts.resizable)
 *   minWidth : 줄일 수 있는 최소 너비 (기본 40)
 *
 * 행 상태 : 새 행은 row._new = true, 고친 행은 row._dirty = true (getChanged 결과에는 빠진다)
 * ============================================================ */
(function (global) {
 'use strict';

 const esc = v => String(v == null ? '' : v).replace(/[&<>"']/g,
  c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
 let seq = 0;

 class EditTable {
  constructor(el, opts) {
   this.el = typeof el === 'string' ? document.querySelector(el) : el;
   if (!this.el) throw new Error('EditTable: 표를 넣을 요소가 없습니다. ' + el);
   this.id = 'et' + (++seq);
   this.opts = Object.assign({sortable: true, resizable: true, emptyText: '자료가 없습니다.'}, opts || {});
   this.columns = [];
   this.data = [];          // 원본 순서
   this.view = [];          // 필터 · 정렬 뒤 전체 행
   this.pv = [];            // 지금 페이지에 그려진 행 (페이징 안 하면 view 와 같다)
   this.page = 1;
   this.total = 0;          // 서버 페이징일 때 전체 건수
   this.sorts = [];         // [{key, dir: 1|-1}]
   this.filter = null;
   this.cur = null;         // 현재 행 (자료 객체)

   this.el.classList.add('et');
   this.el.tabIndex = this.el.tabIndex >= 0 ? this.el.tabIndex : 0;
   this.el.innerHTML = '<table class="et-t"><colgroup></colgroup><thead><tr></tr></thead><tbody></tbody></table>';
   this.table = this.el.querySelector('table');
   this.colgroup = this.table.querySelector('colgroup');
   this.headRow = this.table.querySelector('thead tr');
   this.tbody = this.table.querySelector('tbody');

   this._bind();
   this._initPager();
   this.setColumns(this.opts.columns || []);
   if ('ResizeObserver' in global) {
    this._ro = new ResizeObserver(() => this._sizeCols());
    this._ro.observe(this.el);
   } else {
    global.addEventListener('resize', () => this._sizeCols());
   }
  }

  /* ───────── 칸 ───────── */
  setColumns(cols) {
   const saved = this._loadWidths();
   this.columns = cols.map(c => {
    const o = Object.assign({type: 'text', align: c.type === 'check' ? 'center' : 'left', sortable: true}, c);
    o._w0 = c.width;                                  // 처음 너비 (두 번 누르면 되돌림)
    if (saved && typeof saved[c.key] === 'number') o.width = saved[c.key];
    return o;
   });
   this.sorts = this.sorts.filter(s => this.columns.some(c => c.key === s.key));
   this._renderHead();
   this._renderBody();
  }

  _isRO(col, row) { return typeof col.readOnly === 'function' ? !!col.readOnly(row) : !!col.readOnly; }

  _renderHead() {
   this.colgroup.innerHTML = this.columns.map(() => '<col>').join('');
   this.headRow.innerHTML = this.columns.map((c, i) => {
    const s = this.sorts.findIndex(x => x.key === c.key);
    const mark = s < 0 ? '' : '<span class="et-sort">' + (this.sorts[s].dir > 0 ? '▲' : '▼') + (this.sorts.length > 1 ? (s + 1) : '') + '</span>';
    const cls = [(c.readOnly === true ? '' : 'et-edit'), (this.opts.sortable && c.sortable ? 'et-sortable' : '')].join(' ');
    const rz = this.opts.resizable && c.resizable !== false ? '<span class="et-rz" title="끌어서 칸 너비 조절"></span>' : '';
    return '<th data-c="' + i + '" class="' + cls + '" title="' + esc(c.header) + '">' + esc(c.header) + mark + rz + '</th>';
   }).join('');
   this._sizeCols();
  }

  // 숫자 폭은 그대로, '1*' 같은 비율 폭은 남는 폭을 나눠 갖는다
  _sizeCols() {
   const cols = this.colgroup.children;
   if (!cols.length) return;
   const total = this.el.clientWidth || 0;
   let fixed = 0, stars = 0, sum = 0;
   this.columns.forEach(c => {
    if (typeof c.width === 'number') fixed += c.width;
    else stars += parseFloat(String(c.width || '1*')) || 1;
   });
   const rest = Math.max(0, total - fixed - 2);
   this.columns.forEach((c, i) => {
    const w = typeof c.width === 'number' ? c.width
     : Math.max(c.minWidth || 60, Math.floor(rest * ((parseFloat(String(c.width || '1*')) || 1) / (stars || 1))));
    cols[i].style.width = w + 'px';
    sum += w;
   });
   // 칸 합이 화면보다 넓으면 표를 그만큼 넓혀 가로 스크롤, 아니면 화면 폭
   this.table.style.width = sum > total ? sum + 'px' : '100%';
  }

  /* ───────── 칸 너비 조절 (머리글 오른쪽 끝을 끌기 · 두 번 누르면 처음 너비) ───────── */
  _startResize(e, ci) {
   e.preventDefault(); e.stopPropagation();
   const col = this.columns[ci];
   const startX = e.clientX;
   const startW = this.colgroup.children[ci].getBoundingClientRect().width;
   const min = col.minWidth || 40;
   this.el.classList.add('et-resizing');
   const move = ev => {
    col.width = Math.max(min, Math.round(startW + ev.clientX - startX));
    this._sizeCols();
   };
   const up = () => {
    document.removeEventListener('mousemove', move);
    document.removeEventListener('mouseup', up);
    this.el.classList.remove('et-resizing');
    this._saveWidths();
    this._justResized = true; setTimeout(() => { this._justResized = false; }, 0);
   };
   document.addEventListener('mousemove', move);
   document.addEventListener('mouseup', up);
  }

  /** 칸 너비를 처음 정의대로 되돌림 (저장된 너비도 지움) */
  resetWidths() {
   this.columns.forEach(c => { c.width = c._w0; });
   try { if (this.opts.saveKey) localStorage.removeItem('et.w.' + this.opts.saveKey); } catch (e) { /* 저장소 못 씀 */ }
   this._sizeCols();
  }

  // saveKey 를 주면 이 브라우저에 칸 너비를 기억한다 (사용자별 · 브라우저별)
  _saveWidths() {
   if (!this.opts.saveKey) return;
   const o = {};
   this.columns.forEach(c => { if (typeof c.width === 'number' && c.width !== c._w0) o[c.key] = c.width; });
   try { localStorage.setItem('et.w.' + this.opts.saveKey, JSON.stringify(o)); } catch (e) { /* 저장소 못 씀 */ }
  }
  _loadWidths() {
   if (!this.opts.saveKey) return null;
   try { return JSON.parse(localStorage.getItem('et.w.' + this.opts.saveKey) || 'null'); } catch (e) { return null; }
  }

  /* ───────── 자료 ───────── */
  /**
   * 자료 넣기
   *  - 일반 : setData(list)  → 1쪽으로
   *  - 서버 페이징 : setData(그 쪽 자료, {total: 전체건수, page: 쪽번호})
   */
  setData(list, info) {
   this.data = (list || []).slice();
   this.sorts = [];
   this.cur = this.data[0] || null;
   if (this.opts.serverPaging) {
    this.total = info && info.total != null ? +info.total : this.data.length;
    this.page = info && info.page ? +info.page : 1;
   } else if (!info || !info.keepPage) {
    this.page = 1;
   }
   this._renderHead();
   this._renderBody();
  }
  getData() { return this.data; }
  getView() { return this.view; }        // 필터 · 정렬 뒤 전체 행 (인쇄 · 엑셀용)
  getPageRows() { return this.pv; }      // 지금 쪽에 보이는 행
  current() { return this.cur; }
  getChanged() {
   return this.data.filter(r => r._new || r._dirty).map(r => {
    const o = {};
    Object.keys(r).forEach(k => { if (k.charAt(0) !== '_') o[k] = r[k]; });
    if (r._new) o._new = '1';
    return o;
   });
  }
  hasChanges() { return this.data.some(r => r._new || r._dirty); }
  markClean() { this.data.forEach(r => { delete r._new; delete r._dirty; }); this._renderBody(); }

  /** 새 행 : opts.after 가 있으면 그 행 바로 아래, 없으면 맨 아래 */
  addRow(row, opts) {
   row = Object.assign({}, row, {_new: true});
   const after = opts && opts.after;
   const at = after ? this.data.indexOf(after) : -1;
   if (at >= 0) this.data.splice(at + 1, 0, row); else this.data.push(row);
   this.sorts = [];            // 새 행이 놓인 자리에 보이도록 정렬을 푼다
   this._renderHead();
   this.cur = row;
   this._renderBody();
   this.focusCell(row, opts && opts.focus);
   return row;
  }

  removeRow(row) {
   const i = this.data.indexOf(row);
   if (i < 0) return;
   const vi = this.view.indexOf(row);
   this.data.splice(i, 1);
   this._renderBody();
   if (this.cur === row) this.select(this.view[Math.min(vi, this.view.length - 1)] || null);
  }

  /** 값 넣기 (코드에서) — 화면도 바뀐다 */
  setValue(row, key, value, markDirty) {
   row[key] = value;
   if (markDirty !== false && !row._new) row._dirty = true;
   this.refreshRow(row);
  }

  setFilter(fn) { this.filter = fn || null; this.page = 1; this._renderBody(); }
  refresh() { this._renderBody(); }

  /* ───────── 정렬 ───────── */
  sortBy(key, multi) {
   const s = this.sorts.find(x => x.key === key);
   if (multi) {
    if (!s) this.sorts.push({key, dir: 1});
    else if (s.dir > 0) s.dir = -1;
    else this.sorts = this.sorts.filter(x => x !== s);
   } else {
    this.sorts = [{key, dir: s && this.sorts.length === 1 && s.dir > 0 ? -1 : 1}];
   }
   this.page = 1;          // 정렬을 바꾸면 첫 쪽부터
   this._renderHead();
   this._renderBody();
  }
  clearSort() { this.sorts = []; this.page = 1; this._renderHead(); this._renderBody(); }

  _cmp(a, b) {
   for (const s of this.sorts) {
    let x = a[s.key], y = b[s.key];
    x = x == null ? '' : x; y = y == null ? '' : y;
    const nx = Number(x), ny = Number(y);
    const r = (x !== '' && y !== '' && !isNaN(nx) && !isNaN(ny)) ? nx - ny : String(x).localeCompare(String(y), 'ko');
    if (r) return r * s.dir;
   }
   return 0;
  }

  /* ───────── 그리기 ───────── */
  _renderBody() {
   let v = this.filter ? this.data.filter(this.filter) : this.data.slice();
   if (this.sorts.length) v = v.map((r, i) => [r, i]).sort((p, q) => this._cmp(p[0], q[0]) || p[1] - q[1]).map(p => p[0]);
   this.view = v;
   // 페이징 : 서버 페이징이면 받은 자료가 곧 한 쪽, 아니면 여기서 자른다
   const size = this._size();
   if (size && !this.opts.serverPaging) {
    const pages = Math.max(1, Math.ceil(v.length / size));
    this.page = Math.min(Math.max(1, this.page), pages);
    this.pv = v.slice((this.page - 1) * size, this.page * size);
   } else {
    this.pv = v;
   }
   if (!this.pv.length) {
    this.tbody.innerHTML = '<tr class="et-empty"><td colspan="' + (this.columns.length || 1) + '">' + esc(this.opts.emptyText) + '</td></tr>';
   } else {
    this.tbody.innerHTML = this.pv.map((r, i) => this._rowHtml(r, i)).join('');
   }
   if (this.cur && v.indexOf(this.cur) < 0) this.cur = null;
   this.el.scrollTop = 0;
   this._count();
   this._renderPager();
  }

  _rowCls(r) { return (r === this.cur ? 'et-sel ' : '') + (r._new ? 'et-new ' : '') + (!r._new && r._dirty ? 'et-dirty' : ''); }

  _rowHtml(r, i) {
   return '<tr data-r="' + i + '" class="' + this._rowCls(r) + '">' + this.columns.map((c, ci) => this._cellHtml(c, r, ci, i)).join('') + '</tr>';
  }

  _cellHtml(c, r, ci, ri) {
   const val = r[c.key];
   const al = 'et-' + (c.align === 'center' ? 'c' : c.align === 'right' ? 'r' : 'l');
   const ro = this._isRO(c, r);
   const at = ' data-c="' + ci + '"';

   if (c.type === 'check') {
    const on = c.on == null ? '1' : c.on;
    const chk = String(val) === String(on) || val === true;
    return '<td class="et-chk"' + at + '><input type="checkbox" class="et-native" data-k="' + esc(c.key) + '"'
     + (chk ? ' checked' : '') + (ro ? ' disabled' : '') + '/></td>';
   }
   if (c.type === 'radio') {
    const name = this.id + '_' + ri + '_' + ci;
    return '<td class="et-radio"' + at + '>' + (c.options || []).map(o =>
     '<label><input type="radio" class="et-native" name="' + name + '" data-k="' + esc(c.key) + '" value="' + esc(o.v) + '"'
     + (String(val) === String(o.v) ? ' checked' : '') + (ro ? ' disabled' : '') + '/>' + esc(o.n) + '</label>').join('') + '</td>';
   }
   if (ro) {
    let txt = val;
    if (c.format) txt = c.format(val, r);
    else if (c.type === 'select') { const o = (c.options || []).find(o => String(o.v) === String(val)); txt = o ? o.n : val; }
    return '<td class="et-ro ' + al + (c.wrap ? ' et-wrap' : '') + '"' + at + ' title="' + esc(txt) + '">' + esc(txt) + '</td>';
   }
   if (c.type === 'select') {
    const has = (c.options || []).some(o => String(o.v) === String(val));
    return '<td' + at + '><select class="et-sel ' + al + '" data-k="' + esc(c.key) + '">'
     + (has ? '' : '<option value=""' + (val == null || val === '' ? ' selected' : '') + '></option>')
     + (c.options || []).map(o => '<option value="' + esc(o.v) + '"' + (String(val) === String(o.v) ? ' selected' : '') + '>' + esc(o.n) + '</option>').join('')
     + '</select></td>';
   }
   return '<td' + at + '><input type="text" class="et-in ' + al + '" data-k="' + esc(c.key) + '"'
    + (c.type === 'number' ? ' inputmode="numeric"' : '')
    + (c.maxLength ? ' maxlength="' + c.maxLength + '"' : '')
    + (c.placeholder ? ' placeholder="' + esc(c.placeholder) + '"' : '')
    + ' value="' + esc(val) + '"/></td>';
  }

  /** 한 행만 다시 그리기 (다른 칸 값이 바뀌었을 때) */
  refreshRow(row) {
   const i = this.pv.indexOf(row);
   if (i < 0) return;
   const tr = this.tbody.querySelector('tr[data-r="' + i + '"]');
   if (!tr) return;
   const focusKey = document.activeElement && tr.contains(document.activeElement) ? document.activeElement.dataset.k : null;
   tr.className = this._rowCls(row);
   tr.innerHTML = this.columns.map((c, ci) => this._cellHtml(c, row, ci, i)).join('');
   if (focusKey) { const e = tr.querySelector('[data-k="' + focusKey + '"]'); if (e) e.focus(); }
  }

  _count() {
   if (!this.opts.countEl) return;
   const el = typeof this.opts.countEl === 'string' ? document.querySelector(this.opts.countEl) : this.opts.countEl;
   if (el) el.textContent = '전체 ' + this._totalCount().toLocaleString() + '건';
  }

  _totalCount() { return this.opts.serverPaging ? this.total : this.view.length; }

  /* ───────── 페이징 ─────────
   * opts.pageSize : 한 쪽 행 수 (0 또는 없음 = 페이징 안 함)
   * opts.pageSizes: 고를 수 있는 행 수 (기본 [20, 50, 100, 200, 0], 0 = 전체)
   * opts.pager    : 쪽 번호를 넣을 곳 (선택자/요소). 없으면 표 바로 아래에 만든다
   * opts.serverPaging + opts.onPage(page, size) : 쪽을 바꿀 때마다 서버에서 그 쪽만 받아 setData(list, {total, page})
   */
  _size() {
   return this.opts.pageSize ? +this.opts.pageSize : 0;
  }

  _initPager() {
   const o = this.opts;
   if (!o.pageSize && !o.serverPaging) { this.pagerEl = null; return; }
   if (o.saveKey) {   // 고른 행 수 기억
    try { const ps = localStorage.getItem('et.ps.' + o.saveKey); if (ps != null && ps !== '') o.pageSize = +ps; } catch (e) { /* 저장소 못 씀 */ }
   }
   let el = o.pager ? (typeof o.pager === 'string' ? document.querySelector(o.pager) : o.pager) : null;
   if (!el) {
    el = document.createElement('div');
    this.el.parentNode.insertBefore(el, this.el.nextSibling);
   }
   el.classList.add('et-pager');
   this.pagerEl = el;
   el.addEventListener('click', e => {
    const b = e.target.closest('button[data-p]');
    if (b && !b.disabled) this.goPage(+b.dataset.p);
   });
   el.addEventListener('change', e => {
    if (e.target.classList.contains('et-ps')) this.setPageSize(+e.target.value);
   });
  }

  _renderPager() {
   const el = this.pagerEl;
   if (!el) return;
   const size = this._size(), total = this._totalCount();
   const pages = size ? Math.max(1, Math.ceil(total / size)) : 1;
   const p = Math.min(this.page, pages);
   const sizes = this.opts.pageSizes || [20, 50, 100, 200, 0];
   // 쪽 번호는 10개씩 묶어서
   const from = Math.floor((p - 1) / 10) * 10 + 1, to = Math.min(pages, from + 9);
   const btn = (n, label, dis, on, title) => '<button type="button" data-p="' + n + '"' + (dis ? ' disabled' : '')
    + (on ? ' class="on"' : '') + (title ? ' title="' + title + '"' : '') + '>' + label + '</button>';
   let nums = '';
   for (let n = from; n <= to; n++) nums += btn(n, n, false, n === p);
   el.innerHTML = '<div class="et-pg">'
    + btn(1, '«', p <= 1, false, '처음') + btn(p - 1, '‹', p <= 1, false, '이전')
    + nums
    + btn(p + 1, '›', p >= pages, false, '다음') + btn(pages, '»', p >= pages, false, '마지막')
    + '</div><div class="et-pg-r"><span class="et-pg-info">' + p + ' / ' + pages + ' 쪽</span>'
    + '<select class="et-ps" title="한 쪽에 보일 행 수">'
    + sizes.map(n => '<option value="' + n + '"' + (n === size ? ' selected' : '') + '>' + (n ? n + '개씩' : '전체') + '</option>').join('')
    + '</select></div>';
  }

  goPage(n) {
   const size = this._size(), total = this._totalCount();
   const pages = size ? Math.max(1, Math.ceil(total / size)) : 1;
   n = Math.min(Math.max(1, n), pages);
   if (this.opts.serverPaging) {
    if (this.opts.onPage) this.opts.onPage(n, size, this);   // 페이지에서 서버 조회 → setData(list, {total, page: n})
    return;
   }
   if (n === this.page) return;
   this.page = n;
   this.cur = null;
   this._renderBody();
  }

  setPageSize(n) {
   this.opts.pageSize = n;
   try { if (this.opts.saveKey) localStorage.setItem('et.ps.' + this.opts.saveKey, String(n)); } catch (e) { /* 저장소 못 씀 */ }
   if (this.opts.serverPaging) { if (this.opts.onPage) this.opts.onPage(1, n, this); return; }
   const keep = this.cur;
   this.page = 1;
   if (keep) { this._ensurePage(keep, true); } else this._renderBody();
  }

  /** 그 행이 있는 쪽으로 넘어간다 (클라이언트 페이징) */
  _ensurePage(row, force) {
   const size = this._size();
   if (!size || this.opts.serverPaging || !row) { if (force) this._renderBody(); return; }
   const i = this.view.indexOf(row);
   if (i < 0) { if (force) this._renderBody(); return; }
   const p = Math.floor(i / size) + 1;
   if (p !== this.page || force) { this.page = p; this._renderBody(); }
  }

  /* ───────── 선택 · 포커스 ───────── */
  select(row, scroll) {
   const prev = this.cur;
   if (row && this.pv.indexOf(row) < 0) this._ensurePage(row);   // 다른 쪽에 있는 행이면 그 쪽으로
   this.cur = row || null;
   [prev, this.cur].forEach(r => {
    const i = this.pv.indexOf(r);
    const tr = i < 0 ? null : this.tbody.querySelector('tr[data-r="' + i + '"]');
    if (tr) tr.className = this._rowCls(r);
   });
   if (scroll !== false && this.cur) {
    const tr = this.tbody.querySelector('tr[data-r="' + this.pv.indexOf(this.cur) + '"]');
    if (tr) tr.scrollIntoView({block: 'nearest'});
   }
   if (prev !== this.cur && this.opts.onSelect) this.opts.onSelect(this.cur, this);
  }

  /** 그 행으로 가서 key 칸(없으면 처음 고칠 수 있는 칸)에 커서 */
  focusCell(row, key) {
   this.select(row);
   const i = this.pv.indexOf(row);
   const tr = i < 0 ? null : this.tbody.querySelector('tr[data-r="' + i + '"]');
   if (!tr) return;
   const e = key ? tr.querySelector('[data-k="' + key + '"]:not(:disabled)') : tr.querySelector('input:not(:disabled),select:not(:disabled)');
   if (e) e.focus();
  }

  findRow(fn) { return this.data.find(fn) || null; }

  /* ───────── 이벤트 ───────── */
  _rowOf(node) {
   const tr = node.closest && node.closest('tr[data-r]');
   return tr && this.tbody.contains(tr) ? this.pv[+tr.dataset.r] : null;
  }

  _commit(input) {
   const row = this._rowOf(input);
   if (!row) return;
   const col = this.columns.find(c => c.key === input.dataset.k);
   if (!col) return;
   let v;
   if (input.type === 'checkbox') v = input.checked ? (col.on == null ? '1' : col.on) : (col.off == null ? '0' : col.off);
   else if (input.type === 'radio') { if (!input.checked) return; v = input.value; }
   else v = input.value;
   if (String(row[col.key] == null ? '' : row[col.key]) === String(v)) return;
   row[col.key] = v;
   if (!row._new) row._dirty = true;
   const tr = input.closest('tr');
   if (tr) tr.className = this._rowCls(row);
   if (this.opts.onChange) this.opts.onChange(row, col.key, v, this);
  }

  _bind() {
   // 글자칸은 칠 때마다, 체크 · 라디오 · 콤보는 바뀔 때
   this.el.addEventListener('input', e => { if (e.target.classList.contains('et-in')) this._commit(e.target); });
   this.el.addEventListener('change', e => { if (e.target.dataset && e.target.dataset.k) this._commit(e.target); });

   this.el.addEventListener('mousedown', e => {
    const th = e.target.closest('th[data-c]');
    if (th && e.target.classList.contains('et-rz')) { this._startResize(e, +th.dataset.c); return; }
    if (th) {
     const c = this.columns[+th.dataset.c];
     if (this.opts.sortable && c && c.sortable) { e.preventDefault(); this.sortBy(c.key, e.ctrlKey || e.metaKey); }
     return;
    }
    const row = this._rowOf(e.target);
    if (row && row !== this.cur) this.select(row, false);
   });
   this.el.addEventListener('focusin', e => {
    const row = this._rowOf(e.target);
    if (row && row !== this.cur) this.select(row, false);
   });

   // 손잡이를 두 번 누르면 그 칸만 처음 너비로
   this.el.addEventListener('dblclick', e => {
    if (!e.target.classList.contains('et-rz')) return;
    const c = this.columns[+e.target.closest('th').dataset.c];
    c.width = c._w0; this._sizeCols(); this._saveWidths();
   });

   // 위 · 아래 화살표 : 같은 칸의 위 · 아래 행으로 (콤보 안에서는 콤보가 쓴다)
   this.el.addEventListener('keydown', e => {
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
    if (e.target.tagName === 'SELECT') return;
    const list = this.opts.serverPaging ? this.pv : this.view;   // 클라이언트 페이징은 쪽을 넘어 이어서 이동
    const i = this.cur ? list.indexOf(this.cur) : -1;
    const j = i + (e.key === 'ArrowDown' ? 1 : -1);
    if (j < 0 || j >= list.length) return;
    e.preventDefault();
    const key = e.target.dataset && e.target.dataset.k;
    if (key) this.focusCell(list[j], key); else this.select(list[j]);
   });
  }

  /* ───────── 엑셀(CSV) ───────── */
  /** 보이는 행을 CSV 로 받는다. 선택 · 라디오 칸은 이름으로, 체크 칸은 '예' / '' 로 */
  exportCsv(fileName, cols) {
   cols = cols || this.columns;
   const q = v => '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"';
   const text = (c, r) => {
    const v = r[c.key];
    if (c.type === 'check') return String(v) === String(c.on == null ? '1' : c.on) || v === true ? '예' : '';
    if (c.options) { const o = c.options.find(o => String(o.v) === String(v)); return o ? o.n : v; }
    return c.format ? c.format(v, r) : v;
   };
   const head = cols.map(c => q(c.header));
   const lines = [head.join(',')].concat(this.view.map(r => cols.map(c => q(text(c, r))).join(',')));
   const blob = new Blob(['﻿' + lines.join('\r\n')], {type: 'text/csv;charset=utf-8'});
   const a = document.createElement('a');
   a.href = URL.createObjectURL(blob);
   a.download = (fileName || 'data') + '.csv';
   document.body.appendChild(a); a.click(); a.remove();
   setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }
 }

 global.EditTable = EditTable;
})(window);