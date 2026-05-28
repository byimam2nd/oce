if (!CSS.escape) {
  CSS.escape = function(v) {
    return String(v).replace(/([!"#$%&'()*+,./:;<=>?@[\]^`{|}~])/g, '\\$1');
  };
}

function switchDomTab(tab) {
  $$('.sub-tab').forEach(t => t.classList.toggle('active', t.dataset.domtab === tab));
  const actions = $('#domTreeActions');
  const tree = $('#domTree');
  const visual = $('#domVisual');
  if (tab === 'visual') {
    tree.classList.add('hidden'); visual.classList.remove('hidden');
    actions.classList.add('hidden');
    if (state.html && !$('#domVisualIframe')) renderDomVisual(state.html);
  } else {
    tree.classList.remove('hidden'); visual.classList.add('hidden');
    actions.classList.remove('hidden');
  }
}

function $(id) { return document.getElementById(id); }
function $$(s) { return document.querySelectorAll(s); }
function escapeAttr(s) { return String(s).replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function escapeHtml(s) { var d = document.createElement('div'); d.textContent = s; return d.innerHTML; }

const state = {
  html: '', baseUrl: '', doc: null, selectedEl: null,
  currentStep: 'mainpage',
  configMode: { mainpage: 'manual', detail: 'manual', loadlinks: 'manual' },
  configs: { mainpage: {}, detail: {}, loadlinks: {} },
  customFields: { mainpage: [], detail: [], loadlinks: [] },
  providers: [], selectedProvider: null,
  nextUrls: { detail: '', loadlinks: '' },
  extractedMainItems: [],
  extractedEpisodes: [],
};

const STEP_FIELDS = {
  mainpage: [
    { key: 'searchItems', label: 'Search Items Container', desc: 'Container for each result' },
    { key: 'searchTitle', label: 'Search Title', desc: 'Title inside each item', extract: 'text' },
    { key: 'searchHref', label: 'Search Href', desc: 'Link inside each item', extract: 'href' },
    { key: 'searchPoster', label: 'Search Poster', desc: 'Poster image', extract: 'img' },
    { key: 'searchRating', label: 'Search Rating', desc: 'Rating/score text', extract: 'text' },
    { key: 'searchEpText', label: 'Episode Text', desc: 'Episode number label', extract: 'text' },
  ],
  detail: [
    { key: 'loadTitle', label: 'Detail Title', desc: 'Page title', extract: 'text' },
    { key: 'loadPoster', label: 'Detail Poster', desc: 'Main poster', extract: 'img' },
    { key: 'loadBanner', label: 'Banner', desc: 'Background image', extract: 'img' },
    { key: 'loadDesc', label: 'Description', desc: 'Synopsis text', extract: 'text' },
    { key: 'loadInfoBox', label: 'Info Box', desc: 'Metadata container', extract: 'text' },
    { key: 'loadTags', label: 'Tags', desc: 'Genre tags container', extract: 'text' },
    { key: 'loadRating', label: 'Rating', desc: 'Score/rating', extract: 'text' },
    { key: 'loadStatus', label: 'Status', desc: 'Airing status', extract: 'text' },
    { key: 'loadTrailer', label: 'Trailer', desc: 'Trailer link/iframe', extract: 'href' },
    { key: 'loadRecommend', label: 'Recommendations', desc: 'Related items container' },
    { key: 'loadQuality', label: 'Quality', desc: 'Quality indicator', extract: 'text' },
  ],
  loadlinks: [
    { key: 'episodeItems', label: 'Episode Items Container', desc: 'Episode list container' },
    { key: 'episodeHref', label: 'Episode Href', desc: 'Episode link', extract: 'href' },
    { key: 'episodeTitle', label: 'Episode Title', desc: 'Episode name', extract: 'text' },
    { key: 'episodeNum', label: 'Episode Num', desc: 'Episode number', extract: 'text' },
    { key: 'episodeDesc', label: 'Episode Desc', desc: 'Episode description', extract: 'text' },
    { key: 'linkOptions', label: 'Link Options', desc: 'Server/quality options' },
    { key: 'downloadItems', label: 'Download Items', desc: 'Download link container' },
  ],
};

function $(sel, ctx) { return (ctx || document).querySelector(sel); }
function $$(sel, ctx) { return Array.from((ctx || document).querySelectorAll(sel)); }

function toast(msg, type) {
  const t = $('#toast'); t.textContent = msg;
  t.className = 'toast ' + (type || '');
  clearTimeout(t._timer); t._timer = setTimeout(() => t.classList.add('hidden'), 3000);
}

function escapeHtml(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function textPreview(el, max) {
  let t = el?.textContent || ''; t = t.replace(/\s+/g, ' ').trim();
  return t.length > max ? t.slice(0, max) + '…' : t;
}

function tagColor(tag) {
  const c = { div:'#79c0ff', a:'#58a6ff', img:'#3fb950', span:'#d2a8ff', h1:'#ffa657', h2:'#ffa657', h3:'#ffa657',
    p:'#8b949e', ul:'#8b949e', li:'#8b949e', table:'#8b949e', tr:'#8b949e', td:'#8b949e', section:'#79c0ff',
    article:'#79c0ff', nav:'#79c0ff', header:'#79c0ff', footer:'#79c0ff', main:'#79c0ff', aside:'#79c0ff',
    form:'#f0883e', input:'#f0883e', button:'#f0883e', select:'#f0883e', option:'#f0883e',
    iframe:'#3fb950', script:'#f85149', style:'#f85149', link:'#58a6ff', meta:'#8b949e' };
  return c[tag] || '#e6edf3';
}

// ── Provider Selection ──
async function loadProviders() {
  try {
    const r = await fetch('/api/providers');
    state.providers = await r.json();
    const sel = $('#providerSelect');
    sel.innerHTML = '<option value="">Select Provider...</option>';
    state.providers.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.id;
      opt.textContent = `${p.name} (${p.id})`;
      sel.appendChild(opt);
    });
    toast(`Loaded ${state.providers.length} providers`, 'success');
  } catch(e) {
    toast('Failed to load providers: ' + e.message, 'error');
  }
}

function onProviderSelect(providerId) {
  if (!providerId) return;
  const prov = state.providers.find(p => p.id === providerId);
  if (!prov) return;
  state.selectedProvider = prov;
  state.customFields = { mainpage: [], detail: [], loadlinks: [] };

  // Populate all step configs from provider data
  Object.keys(STEP_FIELDS).forEach(step => {
    const cfg = {};
    STEP_FIELDS[step].forEach(f => {
      const val = prov[f.key];
      cfg[f.key] = val !== undefined && val !== null ? String(val) : '';
    });
    state.configs[step] = cfg;

    // Try to load saved config
    loadSavedConfig(state.selectedProvider, step);
  });

  populatePageSelect();
  updateUrlForStep();
  renderConfigFields();

  toast(`Selected: ${prov.name}`, 'success');
  saveUiState();

  // Re-apply current config mode if AI
  if (state.configMode[state.currentStep] === 'ai') {
    setTimeout(() => switchConfigMode('ai'), 50);
  }
}

function populatePageSelect() {
  const prov = state.selectedProvider;
  const sel = $('#pageSelect');
  sel.innerHTML = '<option value="">Select page category...</option>';
  if (!prov || !Array.isArray(prov.mainPageLists)) { sel.classList.add('hidden'); return; }

  let added = 0;
  prov.mainPageLists.forEach(function(entry) {
    let path, label;
    if (typeof entry === 'string') {
      const sep = entry.indexOf('" to "');
      if (sep > -1) {
        path = entry.slice(0, sep);
        label = entry.slice(sep + 6);
      } else {
        path = entry;
        label = entry;
      }
    } else if (Array.isArray(entry)) {
      path = entry[0]; label = entry[1];
    } else { return; }

    path = path.replace(/^"|"$/g, '').replace(/\\"/g, '"');
    label = label.replace(/^"|"$/g, '').replace(/\\"/g, '"');
    if (!path && !label) return;
    const fullUrl = buildPageUrl(prov, path);
    const opt = document.createElement('option');
    opt.value = fullUrl;
    opt.textContent = label;
    sel.appendChild(opt);
    added++;
  });

  if (added > 0) {
    sel.classList.remove('hidden');
    sel.value = '';
  } else {
    sel.classList.add('hidden');
  }
}

function buildPageUrl(prov, path) {
  const base = (prov.mainUrl || '').replace(/\/+$/, '');
  const pattern = prov.mainPagePathPattern || '';
  if (pattern) {
    return pattern
      .replace('{baseUrl}', base)
      .replace('{data}', path.replace(/^\/+|\/+$/g, ''))
      .replace('{page}', '1');
  }
  if (path.startsWith('http')) return path;
  return base + '/' + path.replace(/^\/+/, '');
}

function updateUrlForStep() {
  const prov = state.selectedProvider;
  const pageSel = $('#pageSelect');
  const urlInput = $('#urlInput');
  if (!prov) return;
  const base = prov.mainUrl || 'https://example.com';

  if (state.currentStep === 'mainpage') {
    if (pageSel.options.length > 1) {
      pageSel.classList.remove('hidden');
      urlInput.placeholder = 'or type URL manually...';
    } else {
      pageSel.classList.add('hidden');
      urlInput.placeholder = 'Enter URL to fetch...';
      urlInput.value = base.endsWith('/') ? base + 'page/1/' : base + '/page/1/';
    }
  } else {
    pageSel.classList.add('hidden');
    urlInput.placeholder = 'Enter URL to fetch...';
    switch (state.currentStep) {
      case 'detail': urlInput.value = prov.seriesUrl || base; break;
      case 'loadlinks': urlInput.value = prov.seriesUrl || base; break;
    }
  }
}

// ── Fetch ──
async function fetchUrl(url, retries = 3) {
  const btn = $('#fetchBtn'); btn.disabled = true; btn.innerHTML = '<span class="loading-spinner"></span>Fetching…';
  try {
    for (let attempt = 1; attempt <= retries; attempt++) {
      try {
        const hdrs = {};
        const ref = $('#refererInput').value.trim(); if (ref) hdrs['Referer'] = ref;
        const ck = $('#cookieInput').value.trim(); if (ck) hdrs['Cookie'] = ck;
        const body = { url, headers: hdrs };
        const useProxy = $('#proxyToggle').checked;
        const proxyUrl = $('#proxyUrl').value.trim();
        if (useProxy && proxyUrl) body.proxy = proxyUrl;

        const r = await fetch('/api/fetch', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        const data = await r.json();
        state.baseUrl = data.url || url;
        state.html = data.html || '';

        // Check for Cloudflare block
        const isBlocked = data.status === 403 || (data.html && /cloudflare|attention.*required|just a moment/i.test(data.html));
        if (isBlocked) {
          const useProx = $('#proxyToggle').checked && $('#proxyUrl').value.trim();
          const msg = useProx ? 'Proxy tidak bisa bypass Cloudflare — coba ganti proxy atau pakai node server.js lokal' : 'Diblokir Cloudflare — centang Proxy atau pakai node server.js lokal';
          toast(msg, 'error');
          parseAndRender(state.html);
          if (state.configMode[state.currentStep] === 'ai') renderAiPanel();
          updateDataPreview();
          return;
        }

        if (!state.html) {
          if (attempt < retries) {
            btn.innerHTML = `<span class="loading-spinner"></span>Retry ${attempt}/${retries}…`;
            await new Promise(r => setTimeout(r, 1000));
            continue;
          }
          toast('Empty response after ' + retries + ' attempts', 'error');
          return;
        }
        parseAndRender(state.html);
        const proxyInfo = data.proxy === 'enabled' ? ' [Proxy]' : '';
        toast(`Fetched ${state.html.length.toLocaleString()} bytes (HTTP ${data.status})${proxyInfo}`, 'success');
        if (state.configMode[state.currentStep] === 'ai') { renderAiPanel(); }
        updateDataPreview();
        return;
      } catch(e) {
        if (attempt < retries) {
          btn.innerHTML = `<span class="loading-spinner"></span>Retry ${attempt}/${retries}…`;
          await new Promise(r => setTimeout(r, 1000));
          continue;
        }
        toast('Fetch failed: ' + e.message, 'error');
      }
    }
  } finally {
    btn.disabled = false; btn.textContent = 'Fetch';
  }
}

// ── Parse & Render DOM ──
function parseAndRender(html) {
  const p = new DOMParser();
  state.doc = p.parseFromString(html, 'text/html');
  const tree = buildTree(state.doc.body || state.doc.documentElement);
  $('#domTree').innerHTML = '';
  if (tree) { $('#domTree').appendChild(tree); updateCounts(); }
  else $('#domTree').innerHTML = '<div class="placeholder">No body found</div>';
  state.selectedEl = null;
  $('#elementDetail').classList.add('hidden');
  updateAssignButtons();
  updateAllFields();
  renderDomVisual(html);
}

function renderDomVisual(html) {
  const container = $('#domVisual');
  container.innerHTML = '';
  if (!html) { container.innerHTML = '<div class="placeholder">Fetch a URL to see the visual preview</div>'; return; }

  const hasFullDoc = /<html[\s>]/i.test(html) && /<body[\s>]/i.test(html);
  const fullHtml = hasFullDoc ? html : `<!DOCTYPE html><html><head><base href="${escapeAttr(state.baseUrl)}"></head><body>${html}</body></html>`;

  const iframe = document.createElement('iframe');
  iframe.id = 'domVisualIframe';
  iframe.srcdoc = fullHtml;
  container.appendChild(iframe);

  iframe.addEventListener('load', function injectClickHandler() {
    try {
      const doc = iframe.contentDocument || iframe.contentWindow.document;
      const script = doc.createElement('script');
      script.textContent = `
        document.addEventListener('click', function(e) {
          e.preventDefault(); e.stopPropagation();
          var el = e.target;
          var link = el.closest('a');
          if (link && link.href) {
            parent.postMessage({ type: 'domVisualClick', selector: makeSelector(link) }, '*');
            return;
          }
          parent.postMessage({ type: 'domVisualClick', selector: makeSelector(el) }, '*');
        }, true);
        function makeSelector(el) {
          var parts = [];
          while (el && el !== document.body && el !== document.documentElement) {
            var tag = el.tagName.toLowerCase();
            var seg = tag;
            if (el.id) { seg = '#' + CSS.escape(el.id); parts.unshift(seg); break; }
            var cls = Array.from(el.classList).filter(function(c) { return !c.startsWith('ng-') && !c.startsWith('_'); });
            if (cls.length) seg += '.' + cls.map(function(c) { return CSS.escape(c); }).join('.');
            var p = el.parentElement;
            if (p) {
              var sib = Array.from(p.children).filter(function(c) { return c.tagName === el.tagName; });
              if (sib.length > 1) { var idx = Array.from(p.children).indexOf(el) + 1; seg += ':nth-child(' + idx + ')'; }
            }
            parts.unshift(seg);
            el = el.parentElement;
          }
          return parts.join(' > ');
        }
      `;
      doc.body.appendChild(script);
      const style = doc.createElement('style');
      style.textContent = `
        *:hover { outline: 2px solid #58a6ff !important; outline-offset: -2px !important; cursor: crosshair !important; }
        ins.adsbygoogle, .advertisement, .ad-container, .ad-slot, .ad-unit,
        [id*="google_ads"], iframe[src*="doubleclick"], iframe[src*="googleads"],
        [data-ad-client], [data-ad-slot], [data-ad],
        .footer-sticky, .sticky-footer-ad,
        div[style*="z-index: 99999"], div[style*="z-index: 999999"]
        { display: none !important; }
        body { overflow: auto !important; }
      `;
      doc.head.appendChild(style);
      const adScript = doc.createElement('script');
      adScript.textContent = `
        (function() {
          var adPatterns = [
            'google_ads', 'doubleclick', 'googlesyndication',
            'ad-client', 'ad-slot', 'data-ad',
            'popmake', 'adsbygoogle',
          ];
          function isAd(el) {
            if (!el || el === document.body || el === document.documentElement) return false;
            var tag = el.tagName.toLowerCase();
            if (tag === 'ins' && el.className.indexOf('adsbygoogle') !== -1) return true;
            if (tag === 'iframe') {
              var src = el.src || '';
              if (src.indexOf('doubleclick') !== -1 || src.indexOf('googleads') !== -1) return true;
            }
            if (el.getAttribute('data-ad-client') || el.getAttribute('data-ad-slot')) return true;
            var id = el.id || '', cls = el.className || '';
            if (typeof cls === 'string') {
              if (cls.indexOf('adsbygoogle') !== -1 || cls.indexOf('ad-container') !== -1 || cls.indexOf('ad-slot') !== -1) return true;
            }
            var zIdx = window.getComputedStyle(el).zIndex;
            if (zIdx && parseInt(zIdx) >= 99999) return true;
            var pos = window.getComputedStyle(el).position;
            if ((pos === 'fixed' || pos === 'sticky') && zIdx && parseInt(zIdx) >= 9999) return true;
            return false;
          }
          function removeAds() {
            var all = document.querySelectorAll('ins.adsbygoogle, [data-ad-client], [data-ad-slot], iframe[src*="doubleclick"], iframe[src*="googleads"]');
            for (var i = 0; i < all.length; i++) { var a = all[i]; if (a.parentNode) a.parentNode.removeChild(a); }
            var fixed = document.querySelectorAll('body > div, body > section');
            for (var i = 0; i < fixed.length; i++) {
              var el = fixed[i];
              if (el.offsetHeight < 100) continue;
              var zIdx = window.getComputedStyle(el).zIndex;
              var pos = window.getComputedStyle(el).position;
              if ((pos === 'fixed' || pos === 'sticky' || pos === 'absolute') && zIdx && parseInt(zIdx) >= 9999) {
                if (el.parentNode) el.parentNode.removeChild(el);
              }
            }
          }
          removeAds();
          var observer = new MutationObserver(function() { removeAds(); });
          observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class', 'src'] });
          setInterval(removeAds, 2000);
        })();
      `;
      doc.body.appendChild(adScript);
    } catch(e) { console.error('Visual init error:', e); }
  });
}

window.addEventListener('message', function(e) {
  if (e.data && e.data.type === 'domVisualClick') {
    if (!state.doc) return;
    try {
      const el = state.doc.querySelector(e.data.selector);
      if (el) {
        const nodes = $$('.tree-node');
        let match = null;
        nodes.forEach(function(n) { if (n.dataset.path === e.data.selector || n.dataset.path?.endsWith(' > ' + e.data.selector)) match = n; });
        selectElement(el, match);
        toast('Selected: ' + e.data.selector, 'success');
      }
    } catch(_) {}
  }
});

function filterDomTree(query) {
  const countEl = $('#domFindCount');

  // Clear previous highlights
  $$('mark.hl').forEach(function(m) {
    const p = m.parentNode;
    p.replaceChild(document.createTextNode(m.textContent), m);
    p.normalize();
  });

  if (!query.trim()) {
    $$('.tree-node').forEach(function(n) { n.style.display = ''; });
    $$('.tree-children').forEach(function(c) { c.style.display = 'none'; });
    $$('.toggle:not(.leaf)').forEach(function(t) { t.classList.add('collapsed'); t.classList.remove('expanded'); });
    countEl.classList.add('hidden');
    return;
  }

  const q = query.toLowerCase();
  const allNodes = $$('.tree-node');
  const matchSet = new Set();

  allNodes.forEach(function(n) {
    const label = getNodeLabel(n);
    if (label.toLowerCase().includes(q)) matchSet.add(n);
  });

  // Expand ancestor path for each match so it's visible
  matchSet.forEach(function(n) {
    let p = n;
    while (p) {
      const ch = p.querySelector(':scope > .tree-children');
      const t = p.querySelector(':scope > .toggle');
      if (ch && t && !t.classList.contains('leaf')) {
        ch.style.display = '';
        t.classList.add('expanded'); t.classList.remove('collapsed');
      }
      p = p.parentElement?.closest('.tree-node');
    }
  });

  // Highlight matching text in each match node
  var hlRegex = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
  matchSet.forEach(function(n) {
    // Highlight label wrapper (skip toggle and child-count)
    var wrapper = n.firstElementChild;
    if (wrapper) highlightTextNodes(wrapper, hlRegex);
    // Also highlight children for deeper matches
    var ch = n.querySelector(':scope > .tree-children');
    if (ch) highlightTextNodes(ch, hlRegex);
  });

  countEl.textContent = matchSet.size + ' matches';
  countEl.classList.remove('hidden');
}

function highlightTextNodes(container, regex) {
  var walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null, false);
  var nodes = [];
  while (walker.nextNode()) {
    var node = walker.currentNode;
    if (node.parentNode && node.parentNode.closest('.toggle, .child-count, .hl')) continue;
    regex.lastIndex = 0;
    if (regex.test(node.textContent)) nodes.push(node);
  }
  nodes.forEach(function(node) {
    regex.lastIndex = 0;
    var text = node.textContent;
    var frag = document.createDocumentFragment();
    var lastIdx = 0, match;
    while ((match = regex.exec(text)) !== null) {
      if (match.index > lastIdx) frag.appendChild(document.createTextNode(text.slice(lastIdx, match.index)));
      var mark = document.createElement('mark');
      mark.className = 'hl';
      mark.textContent = match[0];
      frag.appendChild(mark);
      lastIdx = regex.lastIndex;
    }
    if (lastIdx < text.length) frag.appendChild(document.createTextNode(text.slice(lastIdx)));
    node.parentNode.replaceChild(frag, node);
  });
}

function getNodeLabel(node) {
  const ch = node.querySelector(':scope > .tree-children');
  if (ch && ch.textContent.trim()) {
    const full = node.textContent;
    const idx = full.indexOf(ch.textContent);
    if (idx > 0) return full.slice(0, idx).replace(/\s+/g, ' ').trim();
  }
  return node.textContent.replace(/\s+/g, ' ').trim();
}

function buildTree(node) {
  if (!node || node.nodeType !== 1) {
    if (node?.nodeType === 3 && node.textContent.replace(/\s+/g, ' ').trim()) {
      const el = document.createElement('div'); el.className = 'tree-node';
      el.innerHTML = `<span class="toggle leaf"></span><span class="text-snip">"${escapeHtml(node.textContent.replace(/\s+/g,' ').trim().slice(0, 80))}"</span>`;
      return el;
    }
    return null;
  }
  const tag = node.tagName.toLowerCase();
  if (tag === 'script' || tag === 'style') return null;
  const wrapper = document.createElement('div'); wrapper.className = 'tree-node';
  wrapper.dataset.path = getNodePath(node); wrapper.dataset.tag = tag;
  let label = `<span class="tag" style="color:${tagColor(tag)}">&lt;${tag}</span>`;
  if (node.id) label += ` <span class="id-ref">#${escapeHtml(node.id)}</span>`;
  const classes = Array.from(node.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_'));
  if (classes.length) label += ` <span class="cls">.${classes.map(c=>escapeHtml(c)).join('.')}</span>`;
  ['href','src','data-src','title','alt','type','name'].forEach(a => {
    const v = node.getAttribute(a);
    if (v) label += ` <span class="attr-ref">${a}="${escapeHtml(v.length>50?v.slice(0,50)+'…':v)}"</span>`;
  });
  const txt = textPreview(node, 60);
  if (txt) label += ` <span class="text-snip">"${escapeHtml(txt)}"</span>`;
  const children = Array.from(node.childNodes).map(c => buildTree(c)).filter(Boolean);
  if (children.length) {
    const toggle = document.createElement('span'); toggle.className = 'toggle collapsed';
    wrapper.appendChild(toggle);
    const labelSpan = document.createElement('span');
    labelSpan.innerHTML = label + ` <span class="child-count">(${children.length})</span>`;
    wrapper.appendChild(labelSpan);
    const cont = document.createElement('div'); cont.className = 'tree-children';
    cont.style.display = 'none';
    children.forEach(c => cont.appendChild(c)); wrapper.appendChild(cont);
    toggle.onclick = (e) => { e.stopPropagation();
      toggle.classList.toggle('expanded'); toggle.classList.toggle('collapsed');
      cont.style.display = cont.style.display === 'none' ? '' : 'none';
    };
  } else {
    const toggle = document.createElement('span'); toggle.className = 'toggle leaf';
    wrapper.appendChild(toggle);
    const labelSpan = document.createElement('span'); labelSpan.innerHTML = label;
    wrapper.appendChild(labelSpan);
  }
  wrapper.onclick = (e) => {
    e.stopPropagation();
    if (e.target.classList.contains('toggle')) return;
    selectElement(node, wrapper);
  };
  return wrapper;
}

function getNodePath(node) {
  const parts = []; let cur = node;
  while (cur && cur.nodeType === 1) {
    let p = cur.tagName.toLowerCase();
    if (cur.id) p += '#' + cur.id;
    const parent = cur.parentElement;
    if (parent) {
      const same = Array.from(parent.children).filter(c => c.tagName === cur.tagName);
      if (same.length > 1) { const idx = Array.from(parent.children).indexOf(cur) + 1; p += `:nth-child(${idx})`; }
    }
    parts.unshift(p);
    cur = cur.parentElement;
    if (cur === document.body || cur?.tagName === 'HTML') break;
  }
  return parts.join(' > ');
}

function generateSelector(el) {
  if (el.id) return `#${CSS.escape(el.id)}`;
  let path = []; let cur = el;
  while (cur && cur !== state.doc.body && cur !== state.doc.documentElement) {
    const tag = cur.tagName.toLowerCase();
    let seg = tag;
    const cls = Array.from(cur.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_'));
    if (cls.length) seg += '.' + cls.map(c => CSS.escape(c)).join('.');
    const parent = cur.parentElement;
    if (parent) {
      const siblings = Array.from(parent.children).filter(c => c.tagName === cur.tagName);
      if (siblings.length > 1) { const idx = Array.from(parent.children).indexOf(cur) + 1; seg += `:nth-child(${idx})`; }
    }
    path.unshift(seg); cur = cur.parentElement;
  }
  return path.join(' > ');
}

function selectElement(el, wrapperEl) {
  $$('.tree-node.selected').forEach(n => n.classList.remove('selected'));
  if (wrapperEl) wrapperEl.classList.add('selected');
  state.selectedEl = el;
  updateElementDetail(el);
  updateAssignButtons();
}

function updateElementDetail(el) {
  const d = $('#elementDetail'); d.classList.remove('hidden');
  $('#detTag').textContent = `<${el.tagName.toLowerCase()}>`;
  $('#detId').textContent = el.id || '(none)';
  $('#detClasses').textContent = Array.from(el.classList).join(' ') || '(none)';
  const sel = generateSelector(el); state.selectedSelector = sel;
  $('#detSelector').textContent = sel;
  $('#detText').textContent = textPreview(el, 200);
  const attrs = Array.from(el.attributes).map(a => `${a.name}="${a.value}"`).join('\n');
  $('#detAttrs').textContent = attrs || '(none)';
  const outer = el.outerHTML;
  $('#detHtml').textContent = outer.length > 500 ? outer.slice(0, 500) + '…' : outer;

  // Visual preview
  try {
    const preview = $('#detVisualPreview');
    preview.innerHTML = '';
    const iframe = document.createElement('iframe');
    iframe.className = 'el-preview-iframe';
    preview.appendChild(iframe);
    const idoc = iframe.contentDocument || iframe.contentWindow.document;
    if (idoc) {
      idoc.open();
      idoc.write(`<base href="${state.baseUrl}"><meta name="viewport" content="width=device-width,initial-scale=1">${outer}`);
      idoc.close();
    } else {
      preview.innerHTML = '<div style="padding:8px;color:#8b949e;font-size:11px">Preview unavailable</div>';
    }
  } catch(e) {
    const preview = $('#detVisualPreview');
    preview.innerHTML = '<div style="padding:8px;color:#8b949e;font-size:11px">Preview error</div>';
  }
}

function updateCounts() {
  $('#elementCount').textContent = `${$$('.tree-node', $('#domTree')).length.toLocaleString()} elements`;
}

function updateAssignButtons() {
  $$('.assign-btn').forEach(b => b.disabled = !state.selectedEl);
}

// ── Extraction ──
function extractAttr(el, field) {
  if (!field || field === 'text') return el.textContent.replace(/\s+/g, ' ').trim();
  if (field === 'img') return el.getAttribute('data-src') || el.getAttribute('src') || el.getAttribute('data-original') || el.getAttribute('data-lazy-src') || el.getAttribute('content') || '';
  return el.getAttribute(field) || '';
}

function extractValues(selector, extractMode) {
  if (!selector || !state.doc) return [];
  try {
    const els = state.doc.querySelectorAll(selector);
    return Array.from(els).map((el, i) => {
      const val = extractAttr(el, extractMode);
      return { index: i + 1, value: val || '', text: textPreview(el, 100), tag: el.tagName.toLowerCase() };
    }).filter(v => v.value || v.text);
  } catch(e) { return []; }
}

function extractScopedValues(containerSelector, subSelector, extractMode) {
  if (!containerSelector || !subSelector || !state.doc) return [];
  try {
    const containers = state.doc.querySelectorAll(containerSelector);
    return Array.from(containers).map((container, i) => {
      const r = extractRelative(container, subSelector, extractMode);
      return { index: i + 1, value: r.value, text: r.text };
    }).filter(v => v.value || v.text);
  } catch(e) { return []; }
}

function extractRelative(containerEl, subSelector, extractMode) {
  if (subSelector) {
    try {
      const el = containerEl.querySelector(subSelector);
      if (el) {
        const val = extractAttr(el, extractMode);
        return { value: val || '', text: textPreview(el, 80) };
      }
    } catch(e) {}
    return { value: '', text: '' };
  }
  // Auto-detect when no selector
  if (extractMode === 'href') {
    const a = containerEl.querySelector('a');
    if (a) return { value: a.href || '', text: textPreview(a, 80) };
  }
  if (extractMode === 'img') {
    const img = containerEl.querySelector('img');
    if (img) return { value: extractAttr(img, 'img'), text: textPreview(img, 40) };
  }
  if (extractMode === 'text') {
    const heading = containerEl.querySelector('h1, h2, h3, h4, h5, h6, strong, b, .title, [class*="title"], [class*="judul"]');
    if (heading) return { value: heading.textContent.replace(/\s+/g, ' ').trim().slice(0, 80), text: textPreview(heading, 60) };
    return { value: containerEl.textContent.replace(/\s+/g, ' ').trim().slice(0, 80), text: textPreview(containerEl, 60) };
  }
  return { value: '', text: '' };
}

function getConfig() { return state.configs[state.currentStep] || {}; }
function getCurrentFields() {
  const base = STEP_FIELDS[state.currentStep] || [];
  const custom = state.customFields[state.currentStep] || [];
  return [...base, ...custom];
}

function extractMainPageItems() {
  const cfg = getConfig();
  const itemsSel = cfg.searchItems;
  if (!itemsSel || !state.doc) return [];
  try {
    const items = state.doc.querySelectorAll(itemsSel);
    const result = Array.from(items).map((item, i) => ({
      _idx: i + 1,
      title: extractRelative(item, cfg.searchTitle, 'text'),
      href: extractRelative(item, cfg.searchHref, 'href'),
      poster: extractRelative(item, cfg.searchPoster, 'img'),
      rating: extractRelative(item, cfg.searchRating, 'text'),
      epText: extractRelative(item, cfg.searchEpText, 'text'),
    }));
    state.extractedMainItems = result;
    const first = result.find(r => r.href.value);
    if (first) state.nextUrls.detail = first.href.value;
    return result;
  } catch(e) { return []; }
}

function extractStepData() {
  if (!state.doc) return {};
  const cfg = getConfig();
  const r = {};
  const fields = getCurrentFields();
  fields.forEach(f => {
    const sel = cfg[f.key];
    if (!sel) { r[f.key] = []; return; }
    r[f.key] = extractValues(sel, f.extract);
  });
  // Special handling for mainpage: build item table
  if (state.currentStep === 'mainpage') {
    r._items = extractMainPageItems();
  }
  // Special handling for loadlinks: build episode table
  if (state.currentStep === 'loadlinks') {
    const epSel = cfg.episodeItems;
    if (epSel) {
      try {
        const items = state.doc.querySelectorAll(epSel);
        const episodes = Array.from(items).map((item, i) => ({
          _idx: i + 1,
          href: extractRelative(item, cfg.episodeHref, 'href'),
          title: extractRelative(item, cfg.episodeTitle, 'text'),
          num: extractRelative(item, cfg.episodeNum, 'text'),
          desc: extractRelative(item, cfg.episodeDesc, 'text'),
        }));
        state.extractedEpisodes = episodes;
        const first = episodes.find(ep => ep.href.value);
        if (first) state.nextUrls.loadlinks = first.href.value;
        r._episodes = episodes;
      } catch(e) { r._episodes = []; }
    }
  }
  return r;
}

// ── Config Fields ──
function renderConfigFields() {
  const cfg = getConfig();
  const fields = getCurrentFields();

  // Load saved config for this step if available
  loadSavedConfig(state.selectedProvider, state.currentStep);
  const container = $('#configFields');
  container.innerHTML = '';
  fields.forEach((f, idx) => {
    const isCustom = idx >= (STEP_FIELDS[state.currentStep] || []).length;
    const val = cfg[f.key] || '';
    const fieldDiv = document.createElement('div'); fieldDiv.className = 'config-field' + (isCustom ? ' custom-field' : '');
    fieldDiv.innerHTML = `
      <label>${isCustom ? '✏️ ' : ''}${escapeHtml(f.label)}</label>
      <div style="display:flex;gap:4px;">
        <input type="text" class="selector-value" data-key="${f.key}" value="${escapeHtml(val)}" placeholder="CSS selector..." style="flex:1;">
        <button class="assign-btn" data-key="${f.key}" ${state.selectedEl ? '' : 'disabled'} title="Assign selected element">←</button>
        <button class="reset-btn" data-key="${f.key}" title="Clear field">✕</button>
        ${isCustom ? `<button class="delete-field-btn" data-key="${f.key}" title="Delete custom field">🗑</button>` : ''}
      </div>
      <div class="value-list" id="vl-${f.key}"></div>`;
    container.appendChild(fieldDiv);
    const inp = fieldDiv.querySelector('.selector-value');
    inp.addEventListener('input', () => {
      cfg[f.key] = inp.value;
      updateFieldValues(f.key, f.extract);
      updateDataPreview();
    });
    fieldDiv.querySelector('.assign-btn').addEventListener('click', () => {
      cfg[f.key] = state.selectedSelector;
      inp.value = state.selectedSelector;
      updateFieldValues(f.key, f.extract);
      updateDataPreview();
      toast(`Assigned → ${f.label}`, 'success');
    });
    fieldDiv.querySelector('.reset-btn').addEventListener('click', () => {
      if (cfg[f.key]) {
        if (!confirm('Clear "' + f.label + '" selector?')) return;
      }
      cfg[f.key] = '';
      inp.value = '';
      updateFieldValues(f.key, f.extract);
      updateDataPreview();
    });
    if (isCustom) {
      fieldDiv.querySelector('.delete-field-btn').addEventListener('click', () => {
        const idx = (state.customFields[state.currentStep] || []).findIndex(cf => cf.key === f.key);
        if (idx > -1) state.customFields[state.currentStep].splice(idx, 1);
        delete cfg[f.key];
        renderConfigFields();
        updateDataPreview();
        toast(`Deleted ${f.label}`, 'success');
      });
    }
    updateFieldValues(f.key, f.extract);
  });

  // Add custom field button
  const addBtn = document.createElement('button');
  addBtn.id = 'addCustomField';
  addBtn.textContent = '+ Add Custom Field';
  addBtn.className = 'btn-secondary';
  addBtn.addEventListener('click', () => {
    const key = 'custom_' + Date.now();
    const label = 'Custom ' + ((state.customFields[state.currentStep] || []).length + 1);
    if (!state.customFields[state.currentStep]) state.customFields[state.currentStep] = [];
    state.customFields[state.currentStep].push({ key, label, desc: '', extract: 'text' });
    cfg[key] = '';
    renderConfigFields();
  });
  container.appendChild(addBtn);

  updateAssignButtons();
  updateDataPreview();
}

function updateFieldValues(key, extractMode) {
  const vl = $(`#vl-${key}`);
  if (!vl) return;
  const cfg = getConfig();
  const sel = cfg[key] || '';
  if (!sel || !state.doc) { vl.innerHTML = ''; return; }
  let items;
  if (state.currentStep === 'mainpage' && key !== 'searchItems' && cfg.searchItems) {
    items = extractScopedValues(cfg.searchItems, sel, extractMode);
  } else {
    items = extractValues(sel, extractMode);
  }
  if (items.length === 0) { vl.innerHTML = '<div class="placeholder" style="padding:8px">No matches</div>'; return; }
  const hasText = extractMode === 'href' || extractMode === 'img';
  const valLabel = extractMode === 'href' ? 'URL' : extractMode === 'img' ? 'Image' : 'Value';
  let html = '<table class="data-table"><thead><tr><th class="th-idx">#</th><th>' + valLabel + '</th>' + (hasText ? '<th>Title</th>' : '') + '</tr></thead><tbody>';
  items.forEach(item => {
    const val = String(item.value || '').slice(0, 120);
    const text = String(item.text || '').slice(0, 80);
    const valClass = extractMode === 'href' || extractMode === 'img' ? 'td-img' : '';
    html += '<tr><td class="td-idx">' + item.index + '</td><td class="' + valClass + '">' + escapeHtml(val) + '</td>' + (hasText ? '<td>' + escapeHtml(text) + '</td>' : '') + '</tr>';
  });
  html += '</tbody></table>';
  vl.innerHTML = html;
}

function updateDataPreview() {
  const wrap = $('#dataPreview'); const table = $('#dataTable');
  if (!state.doc) { wrap.classList.add('hidden'); return; }
  wrap.classList.remove('hidden');
  const data = extractStepData();
  const cfg = getConfig();
  let columns, rows, onRowClick, rowTitle = '';
  switch (state.currentStep) {
    case 'mainpage': {
      const itemsSel = cfg.searchItems;
      if (!itemsSel) {
        table.innerHTML = '<div class="placeholder">Set a searchItems selector to see extracted data</div>';
        $('#dataCount').textContent = '—';
        return;
      }
      let containers;
      try { containers = state.doc.querySelectorAll(itemsSel); } catch(e) { containers = []; }
      if (containers.length === 0) {
        table.innerHTML = '<div class="placeholder">No elements match the searchItems selector</div>';
        $('#dataCount').textContent = '0 items';
        return;
      }
      const fields = getCurrentFields().filter(f => f.key !== 'searchItems');
      columns = ['#'].concat(fields.map(f => f.label));
      const colTypes = ['idx'].concat(fields.map(f => f.extract || 'text'));
      rows = Array.from(containers).map((el, i) => {
        return [String(i + 1)].concat(fields.map(f => {
          const r = extractRelative(el, cfg[f.key], f.extract);
          const val = f.extract === 'img' || f.extract === 'href' ? r.value : (r.value || r.text);
          return val || '';
        }));
      });
      rowTitle = 'Click to open detail';
      onRowClick = (idx) => {
        const item = state.extractedMainItems[idx];
        if (item && item.href.value) {
          state.nextUrls.detail = item.href.value;
          toast('→ Detail: ' + item.href.value, 'success');
          switchStep('detail');
        }
      };
      renderTable(table, columns, rows, onRowClick, rowTitle, colTypes);
      $('#dataCount').textContent = `${rows.length} items`;
      return;
    }
    case 'detail': {
      const hasData = STEP_FIELDS.detail.some(f => (data[f.key] || []).length > 0);
      if (!hasData) {
        table.innerHTML = '<div class="placeholder">Set field selectors to see extracted detail data</div>';
        $('#dataCount').textContent = '0 fields';
        return;
      }
      columns = ['Field', 'Selector', 'Extracted Values'];
      colTypes = ['text', 'text', 'text'];
      rows = [];
      STEP_FIELDS.detail.forEach(f => {
        const vals = data[f.key] || [];
        if (vals.length === 0) return;
        rows.push([f.label, cfg[f.key] || '', vals.map(v => formatVal(f.extract, v)).join(' | ')]);
      });
      $('#dataCount').textContent = `${rows.length} fields`;
      break;
    }
    case 'loadlinks': {
      const eps = data._episodes || [];
      const hasOther = ['linkOptions','downloadItems'].some(k => (data[k] || []).length > 0);
      if (eps.length > 0) {
        columns = ['#', 'Href', 'Title', 'Num', 'Desc'];
        colTypes = ['idx', 'href', 'text', 'text', 'text'];
        rows = eps.map(ep => [ep._idx, ep.href.value || '', ep.title.value || '', ep.num.value || '', ep.desc.value || '']);
        $('#dataCount').textContent = `${eps.length} episodes`;
      } else if (hasOther) {
        columns = ['Field', 'Selector', 'Values'];
        colTypes = ['text', 'text', 'text'];
        rows = [];
        ['linkOptions','downloadItems'].forEach(k => {
          const vals = data[k] || [];
          if (vals.length === 0) return;
          rows.push([k, cfg[k] || '', vals.map(v => formatVal('text', v)).join(' | ')]);
        });
        $('#dataCount').textContent = `${rows.length} fields`;
      } else {
        table.innerHTML = '<div class="placeholder">Set episode/field selectors to see extracted data</div>';
        $('#dataCount').textContent = '—';
        return;
      }
      break;
    }
    default: {
      table.innerHTML = '<div class="placeholder">No data available for this step</div>';
      $('#dataCount').textContent = '—';
      return;
    }
  }
  renderTable(table, columns, rows, onRowClick, rowTitle, colTypes);
}

function formatVal(extractMode, item) {
  if (!extractMode || extractMode === 'text') return escapeHtml(item.text.slice(0, 80));
  return `<span class="td-img">${escapeHtml(item.value)}</span>`;
}

function renderTable(container, columns, rows, onRowClick, rowTitle, colTypes) {
  if (rows.length === 0) { container.innerHTML = '<div class="placeholder">No data</div>'; return; }
  container.innerHTML = '';
  const table = document.createElement('table'); table.className = 'data-table' + (onRowClick ? ' clickable-rows' : '');
  let html = '<thead><tr>';
  columns.forEach((c, ci) => {
    const thCls = (colTypes && colTypes[ci] === 'idx') ? ' class="th-idx"' : '';
    html += `<th${thCls}>${escapeHtml(c)}</th>`;
  });
  html += '</tr></thead><tbody>';
  rows.forEach((row, ri) => {
    html += `<tr data-rowidx="${ri}"${rowTitle ? ` title="${escapeHtml(rowTitle)}"` : ''}>`;
    row.forEach((cell, ci) => {
      const t = colTypes && colTypes[ci];
      let cls = !cell ? 'td-empty' : '';
      if (t === 'idx') cls = (cls ? cls + ' ' : '') + 'td-idx';
      else if (t === 'href' || t === 'img') cls = (cls ? cls + ' ' : '') + 'td-img';
      html += `<td class="${cls}">${escapeHtml(cell || '—')}</td>`;
    });
    html += '</tr>';
  });
  html += '</tbody></table>';
  table.innerHTML = html;
  container.appendChild(table);

  if (onRowClick) {
    table.addEventListener('click', (e) => {
      const tr = e.target.closest('tr');
      if (tr && tr.dataset.rowidx !== undefined) {
        onRowClick(parseInt(tr.dataset.rowidx));
      }
    });
  }
}

function updateExport() {
  const out = $('#exportOutput');
  const cfg = getConfig();
  const fields = getCurrentFields();
  const hasAny = fields.some(f => cfg[f.key]);
  if (!hasAny) { out.classList.add('hidden'); return; }
  out.classList.remove('hidden');
  const exportCfg = {};
  fields.forEach(f => { if (cfg[f.key]) exportCfg[f.key] = cfg[f.key]; });
  const provName = state.selectedProvider ? state.selectedProvider.id : '?';
  let code = `// Provider: ${provName}\n// URL: ${state.baseUrl}\n// Step: ${state.currentStep}\n// Generated: ${new Date().toISOString()}\n\n`;
  code += JSON.stringify(exportCfg, null, 2);
  $('#exportCode').textContent = code;
}

function updateAllFields() {
  const fields = getCurrentFields();
  fields.forEach(f => updateFieldValues(f.key, f.extract));
  updateDataPreview();
}

// ── Step Switching ──
function switchStep(step) {
  state.currentStep = step;
  saveUiState();
  $$('.step-tab').forEach(t => t.classList.toggle('active', t.dataset.step === step));
  
  $('#stepTitle').textContent = step.charAt(0).toUpperCase() + step.slice(1);
  const hasChained = !!state.nextUrls[step];
  if (hasChained) {
    $('#urlInput').value = state.nextUrls[step];
    state.nextUrls[step] = '';
  } else {
    updateUrlForStep();
  }
  // Load saved config FIRST so both manual and AI use fresh state
  loadSavedConfig(state.selectedProvider, state.currentStep);
  // Then apply mode
  const mode = state.configMode[step] || 'manual';
  switchConfigMode(mode);
  if (mode === 'manual') renderConfigFields();
  const url = $('#urlInput').value.trim();
  if (url && hasChained) fetchUrl(url);
  else if (!hasChained && step !== 'mainpage')
    toast('Click an item from MainPage or paste a URL', '');
}

function switchConfigMode(mode) {
  state.configMode[state.currentStep] = mode;
  saveUiState();
  $$('.config-sub-tab').forEach(t => t.classList.toggle('active', t.dataset.configtab === mode));
  const cfg = $('#configFields');
  const ai = $('#aiPanel');
  const dp = $('#dataPreview');
  const exp = $('#exportOutput');
  if (mode === 'ai') {
    cfg.classList.add('hidden');
    exp.classList.add('hidden');
    ai.classList.remove('hidden');
    dp.classList.remove('hidden');
    renderAiPanel();
    updateDataPreview();
  } else {
    ai.classList.add('hidden');
    cfg.classList.remove('hidden');
    dp.classList.remove('hidden');
    renderConfigFields();
    updateDataPreview();
  }
}

// ── Init ──
document.addEventListener('DOMContentLoaded', () => {
  loadProviders().then(restoreUiState);

  $('#providerSelect').addEventListener('change', (e) => onProviderSelect(e.target.value));
  $('#pageSelect').addEventListener('change', (e) => {
    saveUiState();
    if (e.target.value) {
      $('#urlInput').value = e.target.value;
    }
  });

  $('#fetchBtn').addEventListener('click', () => {
    const url = $('#urlInput').value.trim();
    if (url) { saveUiState(); fetchUrl(url); }
  });
  $('#urlInput').addEventListener('keydown', e => {
    if (e.key === 'Enter') { saveUiState(); $('#fetchBtn').click(); }
  });
  $('#urlInput').addEventListener('blur', saveUiState);
  $('#proxyToggle').addEventListener('change', (e) => {
    $('#proxyUrl').classList.toggle('hidden', !e.target.checked);
    if (e.target.checked && !$('#proxyUrl').value.trim()) {
      $('#proxyUrl').value = 'socks5://127.0.0.1:9050';
    }
  });
  $('#toggleDomBtn').addEventListener('click', () => {
    $('.dom-panel').classList.toggle('dom-panel-collapsed');
    $('#toggleDomBtn').textContent = $('.dom-panel').classList.contains('dom-panel-collapsed') ? '+' : '─';
  });
  $('#toggleToolbarBtn').addEventListener('click', () => {
    $('.toolbar').classList.toggle('toolbar-collapsed');
    $('#toggleToolbarBtn').textContent = $('.toolbar').classList.contains('toolbar-collapsed') ? '+' : '─';
  });
  $$('.step-tab').forEach(t => t.addEventListener('click', () => switchStep(t.dataset.step)));
  $$('.sub-tab').forEach(t => t.addEventListener('click', () => switchDomTab(t.dataset.domtab)));
  $$('.config-sub-tab').forEach(t => t.addEventListener('click', () => switchConfigMode(t.dataset.configtab)));

  $('#expandAllBtn').addEventListener('click', () => {
    $$('.tree-children').forEach(c => c.style.display = '');
    $$('.toggle:not(.leaf)').forEach(t => { t.classList.add('expanded'); t.classList.remove('collapsed'); });
  });
  $('#collapseAllBtn').addEventListener('click', () => {
    $$('.tree-children').forEach(c => c.style.display = 'none');
    $$('.toggle:not(.leaf)').forEach(t => { t.classList.add('collapsed'); t.classList.remove('expanded'); });
  });
  $('#clearSelection').addEventListener('click', () => {
    state.selectedEl = null; $('#elementDetail').classList.add('hidden');
    $$('.tree-node.selected').forEach(n => n.classList.remove('selected'));
    updateAssignButtons();
  });
  $('#exportBtn').addEventListener('click', () => {
    $('#exportOutput').classList.toggle('hidden');
    if (!$('#exportOutput').classList.contains('hidden')) {
      $('#exportOutput').scrollIntoView({ behavior: 'smooth' });
    }
  });
  $('#saveConfigBtn').addEventListener('click', () => {
    const prov = state.selectedProvider;
    if (!prov) { toast('No provider selected', 'error'); return; }
    if (!confirm('Save config untuk provider ' + prov.id + '?')) return;
    const step = state.currentStep;
    const cfg = state.configs[step];
    const custom = state.customFields[step] || [];
    const key = 'oce_cfg_' + prov.id + '_' + step;
    try {
      localStorage.setItem(key, JSON.stringify({ config: cfg, customFields: custom }));
      toast('Saved: ' + prov.id + ' / ' + step, 'success');
    } catch(e) {
      toast('Save failed: ' + e.message, 'error');
    }
  });
  $('#copyBtn').addEventListener('click', () => {
    navigator.clipboard.writeText($('#exportCode').textContent).then(() => toast('Copied!', 'success'));
  });
  $('#commitBtn').addEventListener('click', async () => {
    const prov = state.selectedProvider;
    if (!prov) { toast('No provider selected', 'error'); return; }
    if (!confirm('Commit config untuk ' + prov.id + ' ke git?')) return;
    const step = state.currentStep;
    const cfg = state.configs[step];
    const nonEmpty = Object.fromEntries(Object.entries(cfg).filter(([,v]) => v));
    if (Object.keys(nonEmpty).length === 0) { toast('No selectors to commit', 'error'); return; }

    $('#commitBtn').disabled = true;
    $('#commitBtn').textContent = 'Committing…';
    try {
      const r = await fetch('/api/commit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ providerId: prov.id, config: nonEmpty, step }),
      });
      const res = await r.json();
      if (res.success) {
        toast('✅ Commit: ' + res.message.substring(0, 80), 'success');
      } else {
        toast('⚠️ ' + (res.message || 'No changes'), '');
      }
    } catch(e) {
      toast('Commit failed: ' + e.message, 'error');
    } finally {
      $('#commitBtn').disabled = false;
      $('#commitBtn').textContent = 'Commit';
    }
  });
  $('#pushBtn').addEventListener('click', async () => {
    if (!confirm('Push semua commit ke GitHub?')) return;
    $('#pushBtn').disabled = true;
    $('#pushBtn').textContent = 'Pushing…';
    try {
      const r = await fetch('/api/push', { method: 'POST' });
      const res = await r.json();
      if (res.success) {
        toast('✅ Push berhasil', 'success');
      } else {
        toast('Push failed: ' + (res.message || 'unknown error'), 'error');
      }
    } catch(e) {
      toast('Push failed: ' + e.message, 'error');
    } finally {
      $('#pushBtn').disabled = false;
      $('#pushBtn').textContent = 'Push';
    }
  });
  $('#domFindInput').addEventListener('input', (e) => {
    filterDomTree(e.target.value);
  });
  $('#aiRefreshBtn').addEventListener('click', () => {
    if (state.doc) { renderAiPanel(); updateDataPreview(); }
    else toast('Fetch a URL first', 'error');
  });

  switchStep('mainpage');
});

function loadSavedConfig(prov, step) {
  if (!prov) return;
  const key = 'oce_cfg_' + prov.id + '_' + step;
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return;
    const data = JSON.parse(raw);
    if (data.config && state.configs[step]) Object.assign(state.configs[step], data.config);
    if (data.customFields) state.customFields[step] = data.customFields;
  } catch(e) { /* ignore corrupt data */ }
}

// ── UI State Persistence ──
const UI_STATE_KEY = 'oce_ui_state';

function saveUiState() {
  if (!state.selectedProvider) return;
  try {
    const data = {
      providerId: state.selectedProvider.id,
      currentStep: state.currentStep,
      configMode: state.configMode,
      expandedSuggestions: Array.from(_expandedSuggestions),
      pageUrl: $('#pageSelect')?.value || '',
      urlInput: $('#urlInput')?.value || '',
    };
    localStorage.setItem(UI_STATE_KEY, JSON.stringify(data));
  } catch(e) { /* ignore */ }
}

function loadUiState() {
  try {
    const raw = localStorage.getItem(UI_STATE_KEY);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch(e) { return null; }
}

function restoreUiState() {
  const saved = loadUiState();
  if (!saved) return;
  if (saved.configMode) Object.assign(state.configMode, saved.configMode);
  if (saved.expandedSuggestions) {
    saved.expandedSuggestions.forEach(k => _expandedSuggestions.add(k));
  }
  if (saved.providerId && state.providers.some(p => p.id === saved.providerId)) {
    $('#providerSelect').value = saved.providerId;
    onProviderSelect(saved.providerId);
    // Restore page select dan URL setelah populatePageSelect selesai
    if (saved.pageUrl) {
      const sel = $('#pageSelect');
      if (sel) { sel.value = saved.pageUrl; }
    }
    if (saved.urlInput) {
      $('#urlInput').value = saved.urlInput;
    }
    if (saved.currentStep && saved.currentStep !== 'mainpage') {
      switchStep(saved.currentStep);
    }
  }
}

// ═══════════════════════════════════════════════════════════════
// AI SELECTOR SUGGESTION ENGINE — KNOWLEDGE-BASED
// ═══════════════════════════════════════════════════════════════

// ── SELECTOR KNOWLEDGE BASE ──
const SELECTOR_KNOWLEDGE = {
  // ──── CONTAINER FIELDS ────
  searchItems: {
    goodPatterns: ['.bsx', '.item', '.post', '.movie', '.anime', '.series', '.card',
      '.col-', '.grid-item', '.entry', '.loop', 'li', 'article', 'tr', '.list-item',
      '.video', '.content-item', '.episode-item', '.show-item', '.media-item'],
    badPatterns: ['#', 'nth-child', ':first', ':last', ':eq', 'body '],
    minCount: 3,
    requiresChildTags: ['a', 'img', 'h2', 'h3', 'time', '.title'],
    requiresChildCount: 1,
    avoidChildPatterns: ['.ad-', '.sponsor', '.promo', '.banner', '.google'],
    description: 'Container untuk setiap item dalam list',
    weight: { pattern: 30, count: 25, structure: 25, specificity: 20 }
  },
  episodeItems: {
    goodPatterns: ['li', 'tr', '.episode', '.eplist', '.ep-item', '.eps-item',
      '.episode-item', '.series-item', 'tbody tr', '.list-episode', '.eplister',
      '.episodelist', '.episode-list', '#episode', '.accordion'],
    badPatterns: ['#', 'nth-child', ':first', ':last', ':eq'],
    minCount: 2,
    requiresChildTags: ['a'],
    requiresChildCount: 1,
    avoidChildPatterns: ['.ad-', '.sponsor'],
    description: 'Container setiap episode dalam list',
    weight: { pattern: 30, count: 25, structure: 25, specificity: 20 }
  },
  linkOptions: {
    goodPatterns: ['.server', '.quality', '.link', '.mirror', '.button', 'select',
      '[class*="server"]', '[class*="quality"]', '[class*="link"]', '#server', '#quality'],
    badPatterns: ['#', 'nth-child'],
    description: 'Container opsi server/quality',
    weight: { pattern: 35, count: 25, structure: 20, specificity: 20 }
  },
  downloadItems: {
    goodPatterns: ['.download', '.dl', '.dllist', '.download-item', '[class*="download"]',
      '.server-list', '.mirror', '.link-list'],
    badPatterns: ['#', 'nth-child'],
    description: 'Container link download',
    weight: { pattern: 35, count: 25, structure: 20, specificity: 20 }
  },

  // ──── TEXT FIELDS ────
  searchTitle: {
    goodPatterns: ['h2', 'h3', 'h4', '.title', 'a', 'strong', 'b', '.name', '.judul',
      '[class*="title"]', '[class*="judul"]', '[class*="name"]'],
    badPatterns: ['nth-child', 'script', 'span', 'p:', '[class*="ad-"]', '[class*="sponsor"]'],
    minMatch: 2,
    contentRules: {
      minLength: 2, maxLength: 150,
      rejectIf: ['Read More', 'Read more', 'read more', 'click here', 'Click Here',
        'Download', 'download', 'Watch Now', 'Watch', 'watch', 'view details',
        'more info', 'show more', '>>', '...', '[]', 'Baca Juga', 'baca juga',
        'Lihat Juga', 'lihat juga', 'Selengkapnya', 'selengkapnya']
    },
    description: 'Judul anime/film di setiap item',
    weight: { pattern: 30, content: 35, specificity: 20, stability: 15 }
  },
  loadTitle: {
    goodPatterns: ['h1', 'h2', '.entry-title', '.post-title', '.page-title',
      '.series-title', '.movie-title', '.title', 'h1.entry-title', 'h1.post-title',
      'h1.title', '[class*="entry-title"]', '[class*="post-title"]'],
    badPatterns: ['nth-child', 'li', 'td', 'span', 'a', 'small', 'strong'],
    contentRules: {
      minLength: 3, maxLength: 300,
      rejectIf: ['Untitled', 'No Title', '404', 'Page not found', 'Error']
    },
    description: 'Judul halaman detail',
    weight: { pattern: 35, content: 30, specificity: 25, stability: 10 }
  },
  episodeTitle: {
    goodPatterns: ['h2', 'h3', 'h4', '.title', '.ep-title', '.episode-title',
      'a', 'strong', '[class*="title"]', '[class*="judul"]', '.name'],
    badPatterns: ['nth-child', 'script', 'span', 'p:'],
    contentRules: {
      minLength: 2, maxLength: 200,
      rejectIf: ['Download', 'download', 'Watch', 'watch']
    },
    description: 'Judul tiap episode',
    weight: { pattern: 30, content: 35, specificity: 20, stability: 15 }
  },
  episodeNum: {
    goodPatterns: ['[class*="ep"]', '[class*="eps"]', '.num', '.number',
      '.episode-no', '[class*="episode"]', 'span', 'strong', 'small', '.badge'],
    contentRules: {
      minLength: 1, maxLength: 15,
      mustMatch: /\d/,
      rejectIf: ['Episode', 'episode', 'EP', 'Total', 'total']
    },
    description: 'Nomor episode',
    weight: { pattern: 30, content: 40, specificity: 15, stability: 15 }
  },
  searchRating: {
    goodPatterns: ['[class*="rating"]', '[class*="score"]', '[class*="rate"]',
      '[class*="star"]', '[class*="vote"]', '[class*="imdb"]', 'span.rating',
      '.rate', '.star', '.score'],
    contentRules: {
      minLength: 1, maxLength: 10,
      mustMatch: /[\d.]/
    },
    description: 'Rating/score item',
    weight: { pattern: 35, content: 35, specificity: 15, stability: 15 }
  },
  loadRating: {
    goodPatterns: ['[class*="rating"]', '[class*="score"]', '[class*="rate"]',
      '[class*="star"]', '[class*="vote"]', '[class*="imdb"]', 'span.rating', '.rate'],
    contentRules: {
      minLength: 1, maxLength: 10,
      mustMatch: /[\d.]/
    },
    description: 'Rating halaman detail',
    weight: { pattern: 35, content: 35, specificity: 15, stability: 15 }
  },
  loadDesc: {
    goodPatterns: ['.desc', '.sinopsis', '.synopsis', '.description', '.entry-content',
      '.storyline', '.plot', '.overview', '.summary', '[class*="desc"]',
      '[class*="sinopsis"]', '[class*="synopsis"]', '[class*="story"]',
      'p', 'article', '.content', '.text'],
    badPatterns: ['nth-child', 'li', 'a', 'small', 'strong'],
    contentRules: {
      minLength: 20, maxLength: 5000,
      mustMatch: /\s{2,}|[.]{2,}|[!?]/
    },
    description: 'Sinopsis/deskripsi',
    weight: { pattern: 30, content: 40, specificity: 20, stability: 10 }
  },
  loadInfoBox: {
    goodPatterns: ['.info', '.metadata', '.detail', '.sinopsis', '.informasi',
      '[class*="info"]', '[class*="meta"]', '[class*="detail"]', '.film-info',
      '.series-info', '.entry-meta', '.post-meta'],
    contentRules: {
      minLength: 10, maxLength: 2000,
    },
    description: 'Container metadata (tahun, genre, dll)',
    weight: { pattern: 30, content: 30, specificity: 25, stability: 15 }
  },
  loadTags: {
    goodPatterns: ['.genre', '.tag', '.tags', '.genres', '[class*="genre"]',
      '[class*="tag"]', '.category', '.categories', '.kategori'],
    contentRules: {
      minLength: 3,
    },
    description: 'Container genre/tag',
    weight: { pattern: 35, content: 25, specificity: 25, stability: 15 }
  },
  loadStatus: {
    goodPatterns: ['[class*="status"]', '[class*="ongoing"]', '[class*="completed"]',
      '[class*="airing"]', '[class*="released"]', '.status', '.type'],
    contentRules: {
      minLength: 2, maxLength: 50,
    },
    description: 'Status rilis (Ongoing/Completed)',
    weight: { pattern: 35, content: 30, specificity: 20, stability: 15 }
  },
  loadTrailer: {
    goodPatterns: ['iframe', '.trailer iframe', '[class*="trailer"] iframe',
      '[class*="trailer"]', '.video iframe', '.embed iframe'],
    contentRules: {
      minLength: 10,
    },
    description: 'Trailer/embed video',
    weight: { pattern: 35, content: 25, specificity: 25, stability: 15 }
  },
  searchEpText: {
    goodPatterns: ['[class*="ep"]', '[class*="eps"]', '.ep', '.eps', '.episode',
      'span', 'small', '.badge', '.status', '[class*="type"]'],
    contentRules: {
      minLength: 1, maxLength: 30,
      mustMatch: /\d/,
    },
    description: 'Teks indikator episode',
    weight: { pattern: 30, content: 35, specificity: 20, stability: 15 }
  },
  loadQuality: {
    goodPatterns: ['[class*="quality"]', '[class*="resolusi"]', '[class*="kualitas"]',
      '.quality', '.hd', '.fhd', '.sd', '.resolution', '.res'],
    contentRules: {
      minLength: 1, maxLength: 20,
    },
    description: 'Indikator kualitas video',
    weight: { pattern: 35, content: 30, specificity: 20, stability: 15 }
  },

  // ──── LINK / IMAGE FIELDS ────
  searchHref: {
    goodPatterns: ['a', 'a[href]', 'a > img', 'h2 a', 'h3 a', '.title a',
      'a[class*="title"]', 'a[class*="thumb"]', '[class*="title"] a',
      'a:first-child'],
    badPatterns: ['nth-child', '[href*="javascript"]', '[href="#"]', 'a:last-child'],
    contentRules: {
      minLength: 10,
      mustMatch: /^https?:\/\//,
      rejectIf: ['#', 'javascript:', 'void(', 'about:']
    },
    description: 'Link ke halaman detail',
    weight: { pattern: 30, content: 40, specificity: 15, stability: 15 }
  },
  episodeHref: {
    goodPatterns: ['a', 'a[href]', 'a:first-child', '.episode a', '.ep a',
      '[class*="ep"] a', 'td a'],
    contentRules: {
      minLength: 10,
      mustMatch: /^https?:\/\//,
      rejectIf: ['#', 'javascript:', 'void(']
    },
    description: 'Link ke halaman episode',
    weight: { pattern: 30, content: 40, specificity: 15, stability: 15 }
  },
  searchPoster: {
    goodPatterns: ['img', 'img[src]', '.thumb img', '.poster img', '[class*="thumb"] img',
      '[class*="poster"] img', 'img[class*="thumb"]', 'img[class*="poster"]',
      'img:first-child', 'a img'],
    badPatterns: ['nth-child', '[src*="icon"]', '[src*="logo"]', '[src*="avatar"]'],
    contentRules: {
      minLength: 15,
      mustMatch: /^https?:\/\//,
      rejectIf: ['.svg', '.ico', 'icon', 'logo', 'avatar', 'data:image/gif']
    },
    description: 'URL poster/thumbnail',
    weight: { pattern: 30, content: 40, specificity: 15, stability: 15 }
  },
  loadPoster: {
    goodPatterns: ['.poster img', '.thumb img', '.entry-thumb img', '.featured img',
      'img.poster', 'img.thumb', '[class*="poster"] img', '[class*="thumb"] img',
      '.cover img', 'img.cover', '.photo img', '.foto img'],
    badPatterns: ['a img', '[src*="icon"]', '[src*="logo"]'],
    contentRules: {
      minLength: 20,
      mustMatch: /^https?:\/\//,
      rejectIf: ['.svg', '.ico', 'icon', 'logo', 'avatar']
    },
    description: 'Poster utama halaman detail',
    weight: { pattern: 35, content: 35, specificity: 20, stability: 10 }
  },
  loadBanner: {
    goodPatterns: ['.banner img', '[class*="banner"] img', '.background img',
      '[class*="background"] img', '.cover img', '.backdrop img',
      '.header img', '.hero img', 'img.banner', 'img.cover'],
    contentRules: {
      minLength: 20,
      mustMatch: /^https?:\/\//
    },
    description: 'Background/banner image',
    weight: { pattern: 35, content: 30, specificity: 25, stability: 10 }
  },

  // ──── EPISODE META ────
  episodeDesc: {
    goodPatterns: ['.desc', '.description', '.sinopsis', '.synopsis', '[class*="desc"]',
      'p', 'small', '.text'],
    contentRules: {
      minLength: 5, maxLength: 500,
    },
    description: 'Deskripsi episode',
    weight: { pattern: 30, content: 40, specificity: 20, stability: 10 }
  },
  episodeTime: {
    goodPatterns: ['[class*="date"]', '[class*="time"]', '[class*="tgl"]', '[class*="duration"]',
      'time', '.date', '.duration', '[datetime]', 'small', 'span'],
    contentRules: {
      minLength: 2, maxLength: 30,
    },
    description: 'Tanggal/durasi episode',
    weight: { pattern: 30, content: 35, specificity: 20, stability: 15 }
  },
  loadRecommend: {
    goodPatterns: ['.recommend', '.related', '.similar', '.suggest', '[class*="recommend"]',
      '[class*="related"]', '[class*="similar"]', '.series-list', '.movie-list',
      '.list-item', '.post-list'],
    description: 'Container rekomendasi',
    weight: { pattern: 30, count: 25, structure: 25, specificity: 20 }
  },

  // ──── EXTERNAL ID ────
  imdbExternal: {
    goodPatterns: ['a[href*="imdb"]', '[class*="imdb"] a', 'a[href*="imdb.com"]', 'a.imdb'],
    contentRules: {
      minLength: 15,
      mustMatch: /imdb/
    },
    description: 'Link IMDB external',
    weight: { pattern: 40, content: 30, specificity: 30 }
  },
  tmdbExternal: {
    goodPatterns: ['a[href*="tmdb"]', '[class*="tmdb"] a', 'a[href*="themoviedb"]'],
    contentRules: {
      minLength: 15,
      mustMatch: /tmdb|themoviedb/
    },
    description: 'Link TMDb external',
    weight: { pattern: 40, content: 30, specificity: 30 }
  },
  malExternal: {
    goodPatterns: ['a[href*="myanimelist"]', '[class*="mal"] a', 'a[href*="myanimelist.net"]'],
    contentRules: {
      minLength: 15,
      mustMatch: /myanimelist/
    },
    description: 'Link MyAnimeList external',
    weight: { pattern: 40, content: 30, specificity: 30 }
  },
};

// ── DEFAULT KNOWLEDGE for fields not in the DB ──
const DEFAULT_FIELD_KNOWLEDGE = {
  goodPatterns: [],
  badPatterns: ['nth-child', '#', ':first', ':last', ':eq', 'body '],
  contentRules: { minLength: 1 },
  description: '',
  weight: { pattern: 15, content: 30, structure: 25, stability: 30 }
};

// ── AUTO-LEARNING FROM MEMORY ──
// Menganalisis memory untuk discover pattern baru secara otomatis
const LEARNED_THRESHOLD = 2; // min providers untuk dianggap learned
const LEARNED_MIN_ACCEPTS = 2; // min accept count untuk good pattern
const LEARNED_MIN_REJECTS = 2; // min reject count untuk bad pattern

function computeLearnedPatterns() {
  const mem = getAiMemory();
  if (mem.length === 0) return { goodPatterns: {}, badPatterns: {}, rejectValues: {} };

  const goodCounts = {};  // { fieldKey: { selector: { accept, providers[] } } }
  const badCounts = {};
  const rejectedValues = {}; // { fieldKey: Set<string> }

  mem.forEach(m => {
    const fk = m.fieldKey;
    if (!goodCounts[fk]) { goodCounts[fk] = {}; badCounts[fk] = {}; rejectedValues[fk] = new Set(); }
    if (m.accepted) {
      if (!goodCounts[fk][m.selector]) goodCounts[fk][m.selector] = { count: 0, providers: new Set() };
      goodCounts[fk][m.selector].count++;
      goodCounts[fk][m.selector].providers.add(m.providerId);
    } else {
      if (!badCounts[fk][m.selector]) badCounts[fk][m.selector] = { count: 0, providers: new Set() };
      badCounts[fk][m.selector].count++;
      badCounts[fk][m.selector].providers.add(m.providerId);
      // Extract rejected value patterns (short snippets)
      if (m.preview && m.preview.length > 1) {
        const clean = m.preview.replace(/\s+/g, ' ').trim().slice(0, 30);
        if (clean.length >= 3) rejectedValues[fk].add(clean.toLowerCase());
      }
    }
  });

  const goodPatterns = {};
  const badPatterns = {};
  const rejectValues = {};

  Object.keys(goodCounts).forEach(fk => {
    const learned = [];
    Object.entries(goodCounts[fk]).forEach(([sel, data]) => {
      if (data.count >= LEARNED_MIN_ACCEPTS && data.providers.size >= LEARNED_THRESHOLD) {
        // Extract the core pattern (remove tag prefix if class-based)
        const core = sel.includes('.') ? sel.replace(/^[a-z]+\./, '.') : sel;
        learned.push(core);
      }
    });
    if (learned.length > 0) goodPatterns[fk] = learned;

    // Also check bad patterns
    const learnedBad = [];
    Object.entries(badCounts[fk]).forEach(([sel, data]) => {
      if (data.count >= LEARNED_MIN_REJECTS && data.providers.size >= LEARNED_THRESHOLD) {
        learnedBad.push(sel);
      }
    });
    if (learnedBad.length > 0) badPatterns[fk] = learnedBad;

    // Convert rejected values to patterns
    if (rejectedValues[fk] && rejectedValues[fk].size > 0) {
      const patterns = [];
      rejectedValues[fk].forEach(v => {
        if (v.length >= 3) patterns.push(v);
      });
      if (patterns.length > 0) rejectValues[fk] = patterns;
    }
  });

  return { goodPatterns, badPatterns, rejectValues };
}

// Cache learned patterns, recompute when memory changes
let _learnedCache = null;

// Track expanded suggestion selectors across re-renders
let _expandedSuggestions = new Set();
function getLearnedPatterns(fieldKey) {
  if (!_learnedCache) _learnedCache = computeLearnedPatterns();
  return {
    good: (_learnedCache.goodPatterns[fieldKey] || []),
    bad: (_learnedCache.badPatterns[fieldKey] || []),
    rejectValues: (_learnedCache.rejectValues[fieldKey] || [])
  };
}

function invalidateLearnedCache() {
  _learnedCache = null;
}

// ── MODIFIED: getKnowledge with auto-learned patterns ──
function getKnowledge(fieldKey) {
  const base = SELECTOR_KNOWLEDGE[fieldKey] || DEFAULT_FIELD_KNOWLEDGE;
  const learned = getLearnedPatterns(fieldKey);

  // Merge learned into a copy
  return {
    ...base,
    goodPatterns: [...(base.goodPatterns || []), ...learned.good],
    badPatterns: [...(base.badPatterns || []), ...learned.bad],
    // Learned rejectIf overrides static rules for values seen repeatedly
    contentRules: {
      ...(base.contentRules || {}),
      rejectIf: [...((base.contentRules && base.contentRules.rejectIf) || []), ...learned.rejectValues]
    }
  };
}

// ── SELECTOR QUALITY ANALYZER ──
// Menilai kualitas structural selector berdasarkan best practices
function analyzeSelectorQuality(selector, fieldKey) {
  const knowledge = getKnowledge(fieldKey);
  const issues = [];
  const strengths = [];
  let stabilityScore = 100;

  // ── Bad patterns check ──
  if (/#/.test(selector)) {
    issues.push('ID-based — hanya cocok untuk 1 elemen, tidak reusable');
    stabilityScore -= 25;
  }
  if (/nth-child/.test(selector)) {
    issues.push('nth-child — rapuh terhadap perubahan struktur halaman');
    stabilityScore -= 30;
  }
  if (/:(first|last|eq|even|odd|first-of-type|last-of-type)\b/.test(selector)) {
    issues.push('Pseudo-class positional — tidak cocok untuk dynamic list');
    stabilityScore -= 20;
  }
  if (/^body /i.test(selector)) {
    issues.push('Path dari body — terlalu panjang dan rapuh');
    stabilityScore -= 15;
  }
  if (selector.split(' > ').length > 4) {
    issues.push('Selector terlalu dalam (>4 level) — perlu disederhanakan');
    stabilityScore -= 15;
  }
  if (/\*=/i.test(selector)) {
    issues.push('Attribute substring selector (*=) — bisa match unintended elements');
    stabilityScore -= 10;
  }

  // ── Tag-only / generic selector penalties ──
  const parts = selector.split(/[\s>+]+/);
  const allTags = parts.every(p => /^[a-z][a-z0-9]*$/.test(p));
  const allSingleChars = parts.every(p => /^[a-z0-9-]+$/.test(p) && !p.includes('.') && !p.includes('#') && !p.includes('['));
  const hasClass = parts.some(p => p.includes('.'));
  const hasAttr = parts.some(p => p.includes('['));
  const classCount = parts.reduce((sum, p) => sum + (p.match(/\./g) || []).length, 0);

  // Tag-only selector — pure tag name(s) like "a", "h2", "a strong", "div span"
  if (allSingleChars && !hasClass && !hasAttr && !selector.includes('#')) {
    if (parts.length === 1) {
      issues.push('Tag-only — terlalu generic, tambahkan class/attribute');
      stabilityScore -= 30;
    } else {
      issues.push('Hanya kombinasi tag — sangat generic, tambahkan class/attribute');
      stabilityScore -= 25;
    }
  }

  // Class-only without tag — like ".title", ".name", ".server"
  if (hasClass && classCount >= 1 && parts.every(p => p.startsWith('.')) && !hasAttr && !selector.includes('#')) {
    issues.push('Class-only — tambahkan tag untuk specificity (contoh: a.title)');
    stabilityScore -= 12;
  }

  // ── Good patterns check ──
  // Tag+class combination — ideal
  if (hasClass && parts.every(p => /^[a-z][a-z0-9]*(\.[\w-]+)+$/.test(p) || /^\./.test(p) || p === '')) {
    // Specific enough
  }
  if (/^[a-z]+\.[\w-]+(\.[\w-]+)*\s+[a-z]/.test(selector) && hasClass) {
    strengths.push('Specific descendant selector — baik untuk struktur');
    stabilityScore += 15;
  }
  if (hasClass && !selector.includes('#')) {
    if (classCount >= 2) {
      strengths.push('Multi-class selector — spesifik dan stabil');
      stabilityScore += 18;
    } else {
      strengths.push('Menggunakan class selector — stabil');
      stabilityScore += 10;
    }
  }
  if (knowledge.goodPatterns && knowledge.goodPatterns.some(p => selector.includes(p))) {
    strengths.push('Cocok dengan pola yang sudah terbukti');
    stabilityScore += 20;
  }
  if (/^h[1-6]\.[\w-]+/.test(selector)) {
    strengths.push('Heading dengan class — semantic dan spesifik');
    stabilityScore += 20;
  } else if (/^h[1-6]$/.test(selector)) {
    strengths.push('Heading tag — semantic');
    stabilityScore += 8;
  }
  if (/^img\./.test(selector)) {
    strengths.push('Image dengan class — semantic dan spesifik');
    stabilityScore += 15;
  } else if (selector === 'img') {
    strengths.push('Image tag — semantic');
    stabilityScore += 5;
  }
  if (/^a\./.test(selector)) {
    strengths.push('Anchor dengan class — spesifik untuk link');
    stabilityScore += 15;
  } else if (selector === 'a') {
    strengths.push('Anchor tag — standard untuk link');
    stabilityScore += 5;
  }
  if (hasAttr && !/\*=/i.test(selector)) {
    strengths.push('Attribute selector — selektif');
    stabilityScore += 8;
  }
  if (knowledge.badPatterns) {
    const foundBads = knowledge.badPatterns.filter(p => selector.includes(p));
    foundBads.forEach(p => {
      issues.push('Mengandung pattern yang tidak disarankan untuk field ini');
      stabilityScore -= 15;
    });
  }

  // ── Specificity scoring ──
  const hasId = parts.some(p => p.includes('#'));
  let specificity;
  if (hasId) specificity = 'High (ID-based)';
  else if (classCount >= 2 || (classCount >= 1 && hasAttr)) specificity = 'High';
  else if (classCount === 1 || hasAttr) specificity = 'Medium';
  else if (parts.length === 1) specificity = 'Low (tag-only)';
  else specificity = 'Low (tag-combo)';

  // ── Stability rating ──
  let stability;
  if (stabilityScore >= 90) stability = 'High';
  else if (stabilityScore >= 60) stability = 'Medium';
  else stability = 'Low';

  return { issues, strengths, stability, stabilityScore, specificity };
}

// ── CONTENT VALIDATOR ──
// Memeriksa apakah nilai yang diekstrak sesuai untuk field type
function validateContent(values, fieldKey) {
  const knowledge = getKnowledge(fieldKey);
  const rules = knowledge.contentRules || {};
  const validValues = [];
  const invalidValues = [];
  const issues = [];

  if (!values || values.length === 0) {
    return { validCount: 0, invalidCount: 0, validRatio: 0, issues: ['No values extracted'] };
  }

  values.forEach(v => {
    if (!v || !v.trim()) { invalidValues.push(v); return; }
    const trimmed = v.trim();
    let valid = true;

    if (rules.minLength && trimmed.length < rules.minLength) valid = false;
    if (rules.maxLength && trimmed.length > rules.maxLength) valid = false;
    if (rules.mustMatch && !rules.mustMatch.test(trimmed)) valid = false;
    if (rules.rejectIf && rules.rejectIf.some(r => trimmed.includes(r))) valid = false;

    if (valid) validValues.push(trimmed);
    else invalidValues.push(trimmed);
  });

  const validRatio = values.length > 0 ? validValues.length / values.length : 0;

  if (validRatio < 0.3) issues.push('Mayoritas nilai tidak sesuai untuk field ini');
  else if (validRatio < 0.7) issues.push('Beberapa nilai meragukan');
  if (validValues.length === 0 && values.length > 0) issues.push('Semua nilai ditolak oleh aturan validasi');
  if (rules.mustMatch && validRatio < 0.5) issues.push('Format nilai tidak sesuai (regex: ' + rules.mustMatch + ')');

  return {
    validCount: validValues.length,
    invalidCount: invalidValues.length,
    validRatio,
    issues,
    samples: validValues.slice(0, 3),
    invalidSamples: invalidValues.slice(0, 2)
  };
}

// ── KNOWLEDGE-BASED SCORING ──
function knowledgeScore(selector, fieldKey, matchCount, previews, fromMemory, memoryAccepted) {
  const knowledge = getKnowledge(fieldKey);
  const quality = analyzeSelectorQuality(selector, fieldKey);
  const content = validateContent(previews, fieldKey);
  const w = knowledge.weight;

  // Component scores (0-100 each)
  const patternScore = (() => {
    let s = 50;
    if (knowledge.goodPatterns && knowledge.goodPatterns.some(p => selector.includes(p))) s += 40;
    if (knowledge.badPatterns && knowledge.badPatterns.some(p => selector.includes(p))) s -= 30;
    if (quality.strengths.length > 0) s += quality.strengths.length * 5;
    if (quality.issues.length > 0) s -= quality.issues.length * 10;
    return Math.max(0, Math.min(100, s));
  })();

  const countScore = Math.min(100, matchCount * 8);

  const contentScore = (() => {
    if (previews.length === 0) return 0;
    return Math.round(content.validRatio * 100);
  })();

  const structureScore = (() => {
    let s = 50;
    if (quality.stability === 'High') s += 30;
    else if (quality.stability === 'Medium') s += 10;
    else s -= 20;
    if (quality.specificity === 'High' && !quality.issues.some(i => i.includes('ID-based'))) s += 10;
    else if (quality.specificity === 'Low (tag-only)') s -= 10;
    return Math.max(0, Math.min(100, s));
  })();

  const stabilityScore = (() => {
    let s = quality.stabilityScore;
    if (fromMemory && memoryAccepted) s += 20;
    if (fromMemory && !memoryAccepted) s -= 30;
    return Math.max(0, Math.min(100, s));
  })();

  // Weighted total — struktur dan stabilitas lebih berbobot
  const total = (
    (patternScore * (w.pattern || 20)) +
    (countScore * (w.count || 15)) +
    (contentScore * (w.content || 25)) +
    (structureScore * (w.structure || 20)) +
    (stabilityScore * (w.stability || 20))
  ) / ((w.pattern || 20) + (w.count || 15) + (w.content || 25) + (w.structure || 20) + (w.stability || 20));

  return {
    total: Math.round(total),
    components: { pattern: patternScore, count: countScore, content: contentScore, structure: structureScore, stability: stabilityScore },
    quality,
    content
  };
}

// ── MEMORY FUNCTIONS ──
const AI_MEMORY_KEY = 'oce_ai_memory';

function getAiMemory() {
  try {
    const raw = localStorage.getItem(AI_MEMORY_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch(e) { return []; }
}

function saveAiMemory(mem) {
  try { localStorage.setItem(AI_MEMORY_KEY, JSON.stringify(mem)); } catch(e) {}
}

function addAiMemory(providerId, step, fieldKey, selector, accepted, dataType, preview) {
  const mem = getAiMemory();
  const idx = mem.findIndex(m => m.providerId === providerId && m.fieldKey === fieldKey && m.selector === selector);
  if (idx > -1) mem.splice(idx, 1);
  mem.push({
    providerId, step, fieldKey, selector, accepted,
    dataType: dataType || 'text',
    preview: (preview || '').slice(0, 120),
    timestamp: Date.now()
  });
  if (mem.length > 500) mem.splice(0, mem.length - 500);
  saveAiMemory(mem);
  invalidateLearnedCache(); // auto-learning perlu di-recompute
}

function getMemoryForField(providerId, fieldKey) {
  return getAiMemory().filter(m => m.providerId === providerId && m.fieldKey === fieldKey);
}

function getGlobalPatternStats(fieldKey) {
  const all = getAiMemory().filter(m => m.fieldKey === fieldKey);
  const stats = {};
  all.forEach(m => {
    if (!stats[m.selector]) stats[m.selector] = { acceptCount: 0, rejectCount: 0, providers: new Set() };
    if (m.accepted) stats[m.selector].acceptCount++;
    else stats[m.selector].rejectCount++;
    stats[m.selector].providers.add(m.providerId);
  });
  Object.values(stats).forEach(s => s.providers = s.providers.size);
  return stats;
}

// ── Engine: Find repeated container patterns ──
function elementSignature(el) {
  const tag = el.tagName.toLowerCase();
  const cls = Array.from(el.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_')).sort().join('.');
  return tag + (cls ? '.' + cls : '');
}

function aiFindContainers(doc) {
  if (!doc) return [];
  const freq = {};
  const all = doc.querySelectorAll('body *');
  all.forEach(el => {
    const sig = elementSignature(el);
    if (!freq[sig]) freq[sig] = [];
    freq[sig].push(el);
  });

  const candidates = [];
  Object.entries(freq).forEach(([sig, els]) => {
    if (els.length < 3) return;
    const parents = new Set();
    els.forEach(el => parents.add(el.parentElement));
    const shareParent = parents.size <= Math.ceil(els.length / 2);

    const el = els[0];
    const tag = el.tagName.toLowerCase();

    let selector;
    if (el.className && typeof el.className === 'string' && el.className.trim()) {
      const cls = Array.from(el.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_'));
      selector = tag + '.' + cls.map(c => CSS.escape(c)).join('.');
    } else {
      selector = tag;
    }

    let score = els.length * 10;
    if (shareParent) score += 20;
    if (tag === 'li' || tag === 'tr') score += 10;
    if (el.className && el.className.trim()) score += 5;

    let parentSel = '';
    if (shareParent) {
      const p = el.parentElement;
      if (p && p !== doc.body) {
        const pSig = elementSignature(p);
        if (pSig.split('.').length > 1) {
          const pCls = Array.from(p.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_'));
          parentSel = p.tagName.toLowerCase() + '.' + pCls.map(c => CSS.escape(c)).join('.');
        }
      }
    }

    let hasTitleChild = false, hasLinkChild = false, hasImgChild = false;
    el.querySelectorAll('h1,h2,h3,h4,h5,h6,.title,[class*="title"],[class*="judul"]').forEach(c => {
      if (c.textContent.trim()) hasTitleChild = true;
    });
    if (el.querySelector('a[href]')) hasLinkChild = true;
    if (el.querySelector('img')) hasImgChild = true;

    // Filter container knowledge
    const containerKnowledge = ['searchItems', 'episodeItems'].some(k => {
      const kn = SELECTOR_KNOWLEDGE[k];
      return kn && kn.requiresChildTags && kn.requiresChildTags.some(t => {
        if (t === 'a') return hasLinkChild;
        if (t === 'img') return hasImgChild;
        if (['h2', 'h3', '.title'].includes(t)) return hasTitleChild;
        return false;
      });
    });

    candidates.push({
      selector, parentSelector: parentSel, count: els.length, score,
      shareParent, hasTitleChild, hasLinkChild, hasImgChild,
      hasRequiredChildren: containerKnowledge,
      sample: textPreview(els[0], 60)
    });
  });

  candidates.sort((a, b) => b.score - a.score);
  return candidates.slice(0, 8);
}

// ── Engine: Sub-selector suggestions (enhanced with knowledge) ──
function aiSuggestSubSelectors(containerEl, dataType, fieldKey) {
  if (!containerEl) return [];
  const suggestions = [];
  const seen = new Set();

  function add(sel, previews, score) {
    if (seen.has(sel)) return;
    seen.add(sel);
    suggestions.push({ selector: sel, previews: previews.slice(0, 5), matchCount: previews.length, score });
  }

  // Helper: generate all tag+class combos from an element list
  function addClassVariants(elements, tag, baseScore, extractFn) {
    extractFn = extractFn || (el => el.textContent.replace(/\s+/g, ' ').trim());
    elements.forEach(el => {
      const val = extractFn(el);
      if (!val || String(val).trim().length < 2) return;
      const cls = Array.from(el.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_'));
      if (cls.length === 0) return;
      const sel = tag + '.' + cls.map(c => CSS.escape(c)).join('.');
      if (seen.has(sel)) return;
      const all = containerEl.querySelectorAll(sel);
      const vals = Array.from(all).map(extractFn).filter(Boolean);
      if (vals.length >= 2) add(sel, vals, vals.length * 8 + baseScore);
    });
  }

  function addAttrVariants(patterns, extractor, baseScore) {
    patterns.forEach(sel => {
      try {
        const els = containerEl.querySelectorAll(sel);
        const vals = Array.from(els).map(extractor).filter(Boolean);
        if (vals.length > 0) add(sel, vals, vals.length * baseScore + 5);
      } catch(e) {}
    });
  }

  const knowledge = getKnowledge(fieldKey || '');

  if (dataType === 'text' || dataType === 'title') {
    // Tag-only headings — low score, only if they match enough items
    ['h1', 'h2', 'h3', 'h4'].forEach(tag => {
      const els = containerEl.querySelectorAll(tag);
      const texts = Array.from(els).map(e => e.textContent.replace(/\s+/g, ' ').trim()).filter(Boolean);
      if (texts.length >= 3) add(tag, texts, texts.length * 5); // low score
    });

    // Tag+class variants for headings
    ['h1', 'h2', 'h3', 'h4'].forEach(tag => {
      const els = containerEl.querySelectorAll(tag);
      addClassVariants(els, tag, 5);
    });

    // Known semantic selectors — medium-high score
    addAttrVariants(
      ['.title', '[class*="title"]', '[class*="judul"]', '.name', '[class*="name"]'],
      el => el.textContent.replace(/\s+/g, ' ').trim(),
      10
    );

    // Tag-only generic link/text — low score
    addAttrVariants(['a'], el => el.textContent.replace(/\s+/g, ' ').trim(), 4);
    addAttrVariants(['strong', 'b'], el => el.textContent.replace(/\s+/g, ' ').trim(), 3);

    // Tag+class from common inline elements
    ['a', 'span', 'div', 'strong'].forEach(tag => {
      const els = containerEl.querySelectorAll(tag);
      addClassVariants(els, tag, 5);
    });
  }

  if (dataType === 'href') {
    // Tag-only a — low score
    const allLinks = Array.from(containerEl.querySelectorAll('a')).map(a => a.getAttribute('href') || '').filter(Boolean);
    if (allLinks.length >= 3) add('a', allLinks, 4);

    // Tag+class for links
    const aEls = containerEl.querySelectorAll('a');
    addClassVariants(aEls, 'a', 8, el => el.getAttribute('href') || '');

    // Semantic link selectors
    addAttrVariants(
      ['a[href*="series"]', 'a[href*="movie"]', 'a[href*="episode"]', 'a[href*="detail"]',
       'a[class*="title"]', 'a[class*="link"]', 'a[class*="item"]', '.title a', '.name a'],
      el => el.getAttribute('href') || '',
      12
    );
  }

  if (dataType === 'img') {
    // Tag-only img — low score
    const allImgs = Array.from(containerEl.querySelectorAll('img')).map(i => i.getAttribute('src') || i.getAttribute('data-src') || '').filter(Boolean);
    if (allImgs.length >= 3) add('img', allImgs, 4);

    // Tag+class for images
    const imgEls = containerEl.querySelectorAll('img');
    addClassVariants(imgEls, 'img', 8, el => el.getAttribute('src') || el.getAttribute('data-src') || '');

    // Semantic image selectors
    addAttrVariants(
      ['.thumb img', '.poster img', '[class*="thumb"] img', '[class*="poster"] img',
       'img[class*="thumb"]', 'img[class*="poster"]', 'img[class*="cover"]'],
      el => el.getAttribute('src') || el.getAttribute('data-src') || '',
      10
    );
  }

  if (dataType === 'text') {
    // Tag+class for text containers
    ['span', 'p', 'div', 'small', 'td'].forEach(tag => {
      const els = containerEl.querySelectorAll(tag);
      addClassVariants(els, tag, 4);
    });

    // Semantic text selectors — medium score
    addAttrVariants(
      ['[class*="rating"]', '[class*="score"]', '[class*="rate"]', '[class*="star"]'],
      el => el.textContent.replace(/\s+/g, ' ').trim(),
      10
    );
    addAttrVariants(
      ['[class*="year"]', '[class*="date"]', '[class*="time"]', '[class*="meta"]',
       '[class*="genre"]', '[class*="type"]', '[class*="eps"]', '[class*="episode"]'],
      el => el.textContent.replace(/\s+/g, ' ').trim(),
      9
    );
    addAttrVariants(
      ['[class*="desc"]', '[class*="sinopsis"]', '[class*="synopsis"]', '[class*="plot"]'],
      el => el.textContent.replace(/\s+/g, ' ').trim(),
      10
    );
  }

  suggestions.sort((a, b) => b.score - a.score);
  return suggestions.slice(0, 3);
}

// ── Engine: Generate all suggestions for a field ──
function aiSuggestField(fieldKey, fieldConfig, containerSelector, doc) {
  const suggestions = [];
  const seenSelectors = new Set();
  const dataType = fieldConfig.extract || 'text';
  const providerId = state.selectedProvider?.id || '';
  const step = state.currentStep;

  const memories = getMemoryForField(providerId, fieldKey);
  const acceptedMemories = memories.filter(m => m.accepted);
  const rejectedMemories = memories.filter(m => !m.accepted);
  const patterns = getGlobalPatternStats(fieldKey);
  const knowledge = getKnowledge(fieldKey);

  function addSuggestion(selector, previews, fromMemory, memoryAccepted, scoreBoost) {
    if (!selector || seenSelectors.has(selector)) return;
    seenSelectors.add(selector);

    let matchCount = 0;
    let previewValues = [];

    if (containerSelector && fieldKey !== 'searchItems' && fieldKey !== 'episodeItems') {
      try {
        const containers = doc.querySelectorAll(containerSelector);
        previewValues = Array.from(containers).map(container => {
          try {
            const el = container.querySelector(selector);
            if (!el) return '';
            const val = extractAttr(el, dataType);
            return val || el.textContent.replace(/\s+/g, ' ').trim().slice(0, 60);
          } catch(e) { return ''; }
        }).filter(Boolean);
        matchCount = previewValues.length;
      } catch(e) {}
    } else {
      try {
        const els = doc.querySelectorAll(selector);
        matchCount = els.length;
        previewValues = Array.from(els).slice(0, 5).map(el => {
          const val = extractAttr(el, dataType);
          return val || el.textContent.replace(/\s+/g, ' ').trim().slice(0, 60);
        }).filter(Boolean);
      } catch(e) {}
    }

    // Compute knowledge-based score
    const ks = knowledgeScore(selector, fieldKey, matchCount, previewValues, fromMemory, memoryAccepted);

    suggestions.push({
      selector,
      matchCount,
      previews: previewValues,
      knowledgeScore: ks,
      score: ks.total + (scoreBoost || 0),
      fromMemory: !!fromMemory,
      memoryAccepted: memoryAccepted,
      rejected: !!fromMemory && !memoryAccepted,
    });
  }

  // From accepted memory (highest priority)
  acceptedMemories.forEach(m => {
    addSuggestion(m.selector, [], true, true, 30);
  });

  // From global accepted patterns (medium-high)
  Object.entries(patterns).forEach(([selector, stat]) => {
    if (seenSelectors.has(selector)) return;
    if (stat.acceptCount > 0 && stat.rejectCount === 0) {
      addSuggestion(selector, [], true, true, 15);
    }
  });

  // Block rejected selectors — jangan tampilkan, cari alternatif
  rejectedMemories.forEach(m => {
    seenSelectors.add(m.selector);

    // Untuk tag-only selector yang di-reject, cari tag+class variants sebagai alternatif
    const tagMatch = m.selector.match(/^([a-z][a-z0-9]*)$/);
    if (!tagMatch || !doc) return;
    try {
      const tag = tagMatch[1];
      const els = doc.querySelectorAll(tag);
      const foundVariants = new Set();
      els.forEach(el => {
        const cls = Array.from(el.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_'));
        if (cls.length === 0) return;
        const sel = tag + '.' + cls.map(c => CSS.escape(c)).join('.');
        if (seenSelectors.has(sel) || foundVariants.has(sel)) return;
        foundVariants.add(sel);
        const all = doc.querySelectorAll(sel);
        if (all.length >= 2) {
          addSuggestion(sel, [], false, false, all.length * 8 + 10);
        }
      });
    } catch(e) {}
  });

  // Generate heuristic suggestions
  if (fieldKey === 'searchItems' || fieldKey === 'episodeItems') {
    const containers = aiFindContainers(doc);
    containers.forEach(c => {
      if (c.parentSelector) addSuggestion(c.parentSelector + ' > ' + c.selector, [], false, false, c.score);
      addSuggestion(c.selector, [], false, false, c.score);
    });
  } else if (fieldKey === 'linkOptions' || fieldKey === 'downloadItems') {
    const containers = aiFindContainers(doc);
    containers.forEach(c => addSuggestion(c.selector, [], false, false, c.score));
    const commonSels = ['.server', '.quality', '[class*="server"]', '[class*="quality"]',
      '.link', '[class*="link"]', '.download', '[class*="mirror"]',
      'a.server', 'a.quality', 'a[class*="server"]', 'a[class*="quality"]'];
    commonSels.forEach(sel => {
      try {
        const els = doc.querySelectorAll(sel);
        if (els.length > 0) addSuggestion(sel, [], false, false, els.length * 5 + 10);
      } catch(e) {}
    });
  } else if (containerSelector) {
    try {
      const firstContainer = doc.querySelector(containerSelector);
      if (firstContainer) {
        const subSugs = aiSuggestSubSelectors(firstContainer, dataType, fieldKey);
        subSugs.forEach(s => addSuggestion(s.selector, s.previews, false, false, s.score));
      }
    } catch(e) {}
  } else {
    const allEls = doc.querySelectorAll('*');
    const freq = {};
    allEls.forEach(el => {
      if (el.children.length > 0 || !el.textContent.trim()) return;
      const sig = elementSignature(el);
      if (!freq[sig]) freq[sig] = { count: 0, els: [] };
      freq[sig].count++;
      if (freq[sig].els.length < 3) freq[sig].els.push(el);
    });
    Object.entries(freq).filter(([, v]) => v.count >= 2).forEach(([sig, v]) => {
      addSuggestion(sig, v.els.map(e => textPreview(e, 40)), false, false, v.count * 3);
    });
  }

  // ── Guarantee minimum 3 suggestions ──
  if (suggestions.length < 3 && fieldKey !== 'searchItems' && fieldKey !== 'episodeItems'
      && fieldKey !== 'linkOptions' && fieldKey !== 'downloadItems') {
    // Use scoped check: fallback selectors diekstrak dalam container (jika ada)
    const fallbacks = dataType === 'href'
      ? ['a', '[href]', 'a[href]', '[class*="link"] a', '.title a', 'a[class*="title"]']
      : dataType === 'img'
        ? ['img', '[src]', 'img[src]', '.thumb img', '[class*="thumb"] img', '[class*="poster"] img']
        : ['a', 'h2', 'h3', 'h4', '.title', '[class*="title"]', 'strong', '.name', 'span',
           '[class*="type"]', '[class*="ep"]', '[class*="rating"]', '[class*="score"]'];
    fallbacks.forEach(sel => {
      if (suggestions.length >= 3) return;
      try {
        // Quick pre-check: if container, count within containers; else global
        let ok = false;
        if (containerSelector && fieldKey !== 'searchItems' && fieldKey !== 'episodeItems') {
          const containers = doc.querySelectorAll(containerSelector);
          let count = 0;
          containers.forEach(c => { if (c.querySelector(sel)) count++; });
          ok = count >= 2;
        } else {
          ok = doc.querySelectorAll(sel).length >= 2;
        }
        if (ok) addSuggestion(sel, [], false, false, 5);
      } catch(e) {}
    });
  }

  // Sort: accepted memory first, then by knowledge score desc
  suggestions.sort((a, b) => {
    if (a.fromMemory && a.memoryAccepted) return -1;
    if (b.fromMemory && b.memoryAccepted) return 1;
    return (b.knowledgeScore?.total || 0) - (a.knowledgeScore?.total || 0);
  });

  // Filter out suggestions with 0 matches — irrelevant/no-match
  return suggestions.filter(s => s.matchCount > 0).slice(0, 3);
}

// ── AI Panel Renderer (enhanced with knowledge display) ──
// ── Find selector by example ──
function findSelectorsByExample(fieldKey, exampleValue, containerSelector, dataType) {
  if (!state.doc || !exampleValue) return [];
  const results = [];
  const seen = new Set();
  const searchText = exampleValue.trim().toLowerCase();

  const extractors = {
    text: el => el.textContent.replace(/\s+/g, ' ').trim(),
    href: el => el.getAttribute('href') || '',
    img: el => el.getAttribute('src') || el.getAttribute('data-src') || '',
    title: el => el.textContent.replace(/\s+/g, ' ').trim(),
  };
  const extract = extractors[dataType] || extractors.text;

  function addResult(sel) {
    if (seen.has(sel)) return;
    seen.add(sel);
    try {
      let matchCount = 0;
      if (containerSelector && fieldKey !== 'searchItems' && fieldKey !== 'episodeItems') {
        const containers = state.doc.querySelectorAll(containerSelector);
        containers.forEach(c => { if (c.querySelector(sel)) matchCount++; });
      } else {
        const els = state.doc.querySelectorAll(sel);
        matchCount = els.length;
      }
      const ks = knowledgeScore(sel, fieldKey, matchCount, [], false, false);
      results.push({ selector: sel, matchCount, previews: [], knowledgeScore: ks, score: ks.total + 20, fromMemory: false, memoryAccepted: false, rejected: false });
    } catch(e) {}
  }

  // Search scope
  let scope = [];
  if (containerSelector && fieldKey !== 'searchItems' && fieldKey !== 'episodeItems') {
    const containers = state.doc.querySelectorAll(containerSelector);
    containers.forEach(c => Array.from(c.children).forEach(child => { if (!scope.includes(child)) scope.push(child); }));
  }
  if (scope.length === 0) scope = [state.doc.body];

  scope.forEach(root => {
    const all = root.querySelectorAll('*');
    all.forEach(el => {
      if (el.children.length > 0 && !el.matches('a,img,span,strong,div,p,td,th,li')) return;
      const val = extract(el).toLowerCase();
      if (!val) return;
      if (val.includes(searchText) || searchText.includes(val)) {
        // Tag+class
        const cls = Array.from(el.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_'));
        if (cls.length > 0) {
          const sel = el.tagName.toLowerCase() + '.' + cls.map(c => CSS.escape(c)).join('.');
          addResult(sel);
        }
        // Tag only
        if (el.parentElement) {
          const parentTag = el.parentElement.tagName.toLowerCase();
          const parentCls = Array.from(el.parentElement.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_'));
          if (parentCls.length > 0) {
            const sel = parentTag + '.' + parentCls.map(c => CSS.escape(c)).join('.') + ' ' + el.tagName.toLowerCase();
            addResult(sel);
          }
        }
        // Attribute selectors
        addResult('[' + (dataType === 'href' ? 'href*' : dataType === 'img' ? 'src*' : 'class*') + '="' + searchText.slice(0, 20) + '"]');
      }
    });
  });

  results.sort((a, b) => b.score - a.score);
  return results.slice(0, 3);
}

// ── Generate more specific selector variants ──
// Mempertahankan struktur asli, hanya nambah specificity di bagian target element
function generateSpecificSelectors(selector, fieldKey, containerSelector, dataType) {
  if (!state.doc || !selector) return [];
  const results = [];
  const seen = new Set();

  // Helper: cek apakah selector punya match di dokumen
  function hasAnyMatch(sel) {
    try {
      if (containerSelector && fieldKey !== 'searchItems' && fieldKey !== 'episodeItems') {
        const containers = state.doc.querySelectorAll(containerSelector);
        return Array.from(containers).some(c => c.querySelector(sel));
      }
      return state.doc.querySelector(sel) !== null;
    } catch(e) { return false; }
  }

  // Parse selector: pisah bagian prefix (parent chain) dan target (element terakhir)
  const parts = selector.split(/\s*>\s*/);
  const targetPart = parts.pop();
  const prefix = parts.length > 0 ? parts.join(' > ') + ' > ' : '';
  const targetTag = targetPart.replace(/[.#\[].*$/, '');

  // Cari elemen yang match dengan selector asli
  let elements = [];
  try {
    if (containerSelector && fieldKey !== 'searchItems' && fieldKey !== 'episodeItems') {
      const containers = state.doc.querySelectorAll(containerSelector);
      containers.forEach(c => {
        const el = c.querySelector(selector);
        if (el) elements.push(el);
      });
    } else {
      elements = Array.from(state.doc.querySelectorAll(selector));
    }
  } catch(e) { return []; }

  if (elements.length < 2) return [];

  const total = elements.length;
  const threshold = Math.max(2, Math.floor(total * 0.6));

  // 1. Kumpulkan class dari target element
  const classCounts = {};
  elements.forEach(el => {
    Array.from(el.classList).forEach(c => {
      if (!c.startsWith('ng-') && !c.startsWith('_')) classCounts[c] = (classCounts[c] || 0) + 1;
    });
  });
  const commonClasses = Object.entries(classCounts)
    .filter(([, count]) => count >= threshold)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3);

  // 2. Variant: tambah class ke target (paling penting)
  commonClasses.forEach(([cls]) => {
    if (targetPart.includes('.' + cls) || targetPart.includes('.' + CSS.escape(cls))) return;
    const newTarget = targetPart.includes('.') || targetPart.includes('[')
      ? targetPart + '.' + CSS.escape(cls)
      : targetTag + '.' + CSS.escape(cls);
    const sel = prefix + newTarget;
    if (sel !== selector && !seen.has(sel) && hasAnyMatch(sel)) { seen.add(sel); results.push(sel); }
  });

  // 3. Variant: tambah attribute selector ke target (hanya jika belum ada class)
  if (commonClasses.length === 0 && !targetPart.includes('[') && !targetPart.includes('.')) {
    const attrCounts = {};
    elements.forEach(el => {
      ['href', 'src', 'data-src', 'title'].forEach(attr => {
        const val = el.getAttribute(attr);
        if (val && val.length > 3 && val.length < 30) attrCounts[attr + '*=' + val.slice(0, 10)] = (attrCounts[attr + '*=' + val.slice(0, 10)] || 0) + 1;
      });
    });
    Object.entries(attrCounts)
      .filter(([, count]) => count >= threshold)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 2)
      .forEach(([attr]) => {
        const sel = prefix + targetTag + '[' + attr + ']';
        if (!seen.has(sel) && hasAnyMatch(sel)) { seen.add(sel); results.push(sel); }
      });
  }

  // 4. Variant: tambah parent context jika selector saat ini tidak punya prefix
  if (!prefix && elements[0].parentElement) {
    const parent = elements[0].parentElement;
    const parentTag = parent.tagName.toLowerCase();
    const parentCls = Array.from(parent.classList).filter(c => !c.startsWith('ng-') && !c.startsWith('_'));
    if (parentCls.length > 0) {
      const parentSel = parentTag + '.' + parentCls.map(c => CSS.escape(c)).join('.');
      const childSel = commonClasses.length > 0
        ? targetTag + '.' + commonClasses.map(([c]) => CSS.escape(c)).join('.')
        : targetPart;
      const sel = parentSel + ' > ' + childSel;
      if (sel !== selector && !seen.has(sel) && hasAnyMatch(sel)) { seen.add(sel); results.push(sel); }
    }
  }

  return results.slice(0, 3);
}

function renderAiPanel() {
  const container = $('#aiSuggestions');
  if (!container) return;

  const prov = state.selectedProvider;
  if (!prov || !state.doc) {
    container.innerHTML = '<div class="ai-no-suggestions">Select a provider and fetch a URL first.</div>';
    $('#aiMemoryCount').textContent = '0 patterns';
    $('#aiProviderLabel').textContent = '';
    return;
  }

  const mem = getAiMemory();
  const providerMemCount = mem.filter(m => m.providerId === prov.id).length;
  $('#aiMemoryCount').textContent = providerMemCount + ' patterns';
  $('#aiProviderLabel').textContent = prov.id + ' · ' + state.currentStep;

  const fields = getCurrentFields();
  const cfg = getConfig();
  const containerKey = state.currentStep === 'mainpage' ? 'searchItems' :
                       state.currentStep === 'detail' ? 'loadTitle' :
                       state.currentStep === 'loadlinks' ? 'episodeItems' : '';
  const containerSelector = cfg[containerKey] || '';

  let html = '<div class="ai-toolbar"><button class="ai-expand-all">Expand All</button><button class="ai-collapse-all">Collapse All</button></div>';

  fields.forEach(f => {
    const currentVal = cfg[f.key] || '';
    const isContainer = f.key === 'searchItems' || f.key === 'episodeItems' ||
                        f.key === 'linkOptions' || f.key === 'downloadItems';
    const effectiveContainer = isContainer ? '' : containerSelector;
    const knowledge = getKnowledge(f.key);

    const suggestions = aiSuggestField(f.key, f, effectiveContainer, state.doc);

    html += '<div class="ai-field-section">';
    html += '<div class="ai-field-header">';
    html += '<span class="ai-field-name">' + escapeHtml(f.key) + '</span>';
    html += '<span class="ai-field-desc">' + escapeHtml(knowledge.description || f.desc || '') + '</span>';

    // Confidence badge based on knowledge score
    const bestScore = suggestions.length > 0 ? suggestions[0].knowledgeScore.total : 0;
    let confClass, confLabel;
    if (bestScore >= 75) { confClass = 'ai-confidence-high'; confLabel = 'High'; }
    else if (bestScore >= 50) { confClass = 'ai-confidence-medium'; confLabel = 'Medium'; }
    else { confClass = 'ai-confidence-low'; confLabel = 'Low'; }
    html += '<span class="ai-confidence ' + confClass + '">' + confLabel + '</span>';

    if (currentVal) {
      html += '<span class="ai-field-current">' + escapeHtml(currentVal) + '</span>';
    } else {
      html += '<span class="ai-field-current empty">not set</span>';
    }
    html += '</div>';

    // Find by Example input
    html += '<div class="ai-field-find">';
    html += '<input type="text" class="ai-find-input" placeholder="Contoh: Renegade Immortal" data-field="' + escapeHtml(f.key) + '">';
    html += '<button class="ai-find-btn" data-field="' + escapeHtml(f.key) + '">🔍</button>';
    html += '</div>';

    // Filter out rejected-only: jangan tampilkan selector yg sudah direject user di field ini
    const rejectedSelectors = new Set(
      getAiMemory()
        .filter(m => m.providerId === prov.id && m.fieldKey === f.key && !m.accepted)
        .map(m => m.selector)
    );

    if (suggestions.length === 0) {
      html += '<div class="ai-no-suggestions">No suggestions — fetch a URL first</div>';
    } else {
      suggestions.forEach(s => {
        // Lewati selector yang sudah di-reject user
        if (rejectedSelectors.has(s.selector)) return;

        const accepted = s.fromMemory && s.memoryAccepted;
        const rejected = s.fromMemory && !s.memoryAccepted;
        const ks = s.knowledgeScore || { total: 0, quality: { issues: [], strengths: [] } };
        const scoreColor = ks.total >= 75 ? 'var(--green)' : ks.total >= 50 ? 'var(--orange)' : 'var(--red)';

        // Compute full value list for this suggestion
        const isContainerField = f.key === 'searchItems' || f.key === 'episodeItems' || f.key === 'linkOptions' || f.key === 'downloadItems';
        let allValues = [];
        if (state.doc) {
          if (effectiveContainer && !isContainerField) {
            allValues = extractScopedValues(effectiveContainer, s.selector, f.extract);
          } else {
            allValues = extractValues(s.selector, f.extract);
          }
        }

        html += '<div class="ai-suggestion" data-field="' + escapeHtml(f.key) + '" data-selector="' + escapeHtml(s.selector) + '">';
        html += '<span class="ai-sugg-expand" title="Toggle value list">▶</span>';
        html += '<span class="ai-sugg-selector" title="Click to preview">' + escapeHtml(s.selector) + '</span>';

        // Knowledge score badge
        html += '<span class="sugg-score" style="color:' + scoreColor + '">' + ks.total + '</span>';

        html += '<span class="ai-sugg-preview">';
        if (s.matchCount > 0) {
          html += '<span class="sugg-count">' + s.matchCount + '×</span>';
          s.previews.slice(0, 3).forEach(p => {
            html += '<span class="sugg-text">' + escapeHtml(p.slice(0, 50)) + '</span>';
          });
        } else {
          html += '<span class="sugg-text" style="color:var(--red)">no matches</span>';
        }
        html += '</span>';

        // Quality indicators
        if (ks.quality && (ks.quality.strengths.length > 0 || ks.quality.issues.length > 0)) {
          html += '<span class="sugg-quality" title="';
          if (ks.quality.strengths.length > 0) html += 'Strengths: ' + ks.quality.strengths.join('; ');
          if (ks.quality.issues.length > 0) html += (ks.quality.strengths.length > 0 ? '\n' : '') + 'Issues: ' + ks.quality.issues.join('; ');
          html += '">';
          if (ks.quality.strengths.length > 0) html += '<span style="color:var(--green);font-size:10px;">&#9650;</span>';
          if (ks.quality.issues.length > 0) html += '<span style="color:var(--red);font-size:10px;margin-left:2px;">&#9660;</span>';
          html += '</span>';
        }

        html += '<span class="ai-sugg-actions">';
        html += '<button class="ai-accept' + (accepted ? ' accepted' : '') + '" title="Accept — simpan sebagai benar">✓</button>';
        html += '<button class="ai-reject' + (rejected ? ' rejected' : '') + '" title="Reject — tandai sebagai salah">✗</button>';
        html += '<button class="ai-specific" title="Generate variant lebih spesifik">⊕</button>';
        html += '<button class="ai-apply" title="Apply ke field config">↩</button>';
        html += '</span>';

        html += '</div>';

        // Hidden value list (shown on expand) — tabel adaptif sesuai extract type
        html += '<div class="ai-sugg-values hidden">';
        if (allValues.length > 0) {
          const hasText = f.extract === 'href' || f.extract === 'img';
          const valLabel = f.extract === 'href' ? 'URL' : f.extract === 'img' ? 'Image' : 'Value';
          html += '<table class="data-table"><thead><tr><th class="th-idx">#</th><th>' + valLabel + '</th>' + (hasText ? '<th>Title</th>' : '') + '</tr></thead><tbody>';
          allValues.forEach(v => {
            const val = String(v.value || '').slice(0, 120);
            const text = String(v.text || '').slice(0, 80);
            const valClass = f.extract === 'href' || f.extract === 'img' ? 'td-img' : '';
            html += '<tr><td class="td-idx">' + v.index + '</td><td class="' + valClass + '">' + escapeHtml(val) + '</td>' + (hasText ? '<td>' + escapeHtml(text) + '</td>' : '') + '</tr>';
          });
          html += '</tbody></table>';
        } else {
          html += '<div class="value-item"><span class="vi-empty">No matches</span></div>';
        }
        html += '</div>';
      });
    }

    html += '</div>';
  });

  container.innerHTML = html;

  // Restore expanded state
  if (_expandedSuggestions.size > 0) {
    container.querySelectorAll('.ai-suggestion').forEach(sugg => {
      const key = sugg.dataset.field + '::' + sugg.dataset.selector;
      if (_expandedSuggestions.has(key)) {
        const expand = sugg.querySelector('.ai-sugg-expand');
        const values = sugg.nextElementSibling;
        if (expand && values && values.classList.contains('ai-sugg-values')) {
          values.classList.remove('hidden');
          expand.textContent = '▼';
        }
      }
    });
  }

  // Event listeners
  container.querySelectorAll('.ai-accept').forEach(btn => {
    btn.addEventListener('click', function(e) {
      e.stopPropagation();
      const sugg = this.closest('.ai-suggestion');
      const fieldKey = sugg.dataset.field;
      const selector = sugg.dataset.selector;
      const field = fields.find(f => f.key === fieldKey);

      if (!confirm('Accept selector "' + selector + '" untuk field ' + fieldKey + '?')) return;

      addAiMemory(prov.id, state.currentStep, fieldKey, selector, true, field?.extract || 'text', selector);
      toast('✓ Accepted: ' + selector, 'success');

      const section = sugg.closest('.ai-field-section');
      section.querySelectorAll('.ai-accept').forEach(b => b.classList.toggle('accepted', b === btn));
      section.querySelectorAll('.ai-reject').forEach(b => b.classList.remove('rejected'));
      updateAiMemoryCount();
      // Re-render to update knowledge scores
      renderAiPanel();
    });
  });

  container.querySelectorAll('.ai-reject').forEach(btn => {
    btn.addEventListener('click', function(e) {
      e.stopPropagation();
      const sugg = this.closest('.ai-suggestion');
      const fieldKey = sugg.dataset.field;
      const selector = sugg.dataset.selector;
      const field = fields.find(f => f.key === fieldKey);

      if (!confirm('Reject selector "' + selector + '" untuk field ' + fieldKey + '?')) return;

      addAiMemory(prov.id, state.currentStep, fieldKey, selector, false, field?.extract || 'text', selector);
      toast('✗ Rejected: ' + selector, '');

      const section = sugg.closest('.ai-field-section');
      section.querySelectorAll('.ai-reject').forEach(b => b.classList.toggle('rejected', b === btn));
      section.querySelectorAll('.ai-accept').forEach(b => b.classList.remove('accepted'));
      updateAiMemoryCount();
      renderAiPanel();
    });
  });

  container.querySelectorAll('.ai-apply').forEach(btn => {
    btn.addEventListener('click', function(e) {
      e.stopPropagation();
      const sugg = this.closest('.ai-suggestion');
      const fieldKey = sugg.dataset.field;
      const selector = sugg.dataset.selector;

      if (!confirm('Apply selector "' + selector + '" ke config field ' + fieldKey + '?')) return;

      const cfg = getConfig();
      cfg[fieldKey] = selector;

      if (prov) {
        const key = 'oce_cfg_' + prov.id + '_' + state.currentStep;
        try {
          localStorage.setItem(key, JSON.stringify({ config: cfg, customFields: state.customFields[state.currentStep] || [] }));
        } catch(e) {}
      }

      toast('↩ Applied: ' + selector, 'success');
      updateDataPreview();
      const header = sugg.closest('.ai-field-section').querySelector('.ai-field-header');
      const curr = header.querySelector('.ai-field-current');
      if (curr) {
        curr.textContent = selector;
        curr.className = 'ai-field-current';
      } else {
        const span = document.createElement('span');
        span.className = 'ai-field-current';
        span.textContent = selector;
        header.appendChild(span);
      }
    });
  });

  // Generate specific variants
  container.querySelectorAll('.ai-specific').forEach(btn => {
    btn.addEventListener('click', function(e) {
      e.stopPropagation();
      const sugg = this.closest('.ai-suggestion');
      const fieldKey = sugg.dataset.field;
      const selector = sugg.dataset.selector;
      const field = fields.find(f => f.key === fieldKey);

      if (!confirm('Cari variant lebih spesifik dari "' + selector + '"?')) return;

      const containerKey = state.currentStep === 'mainpage' ? 'searchItems' :
                           state.currentStep === 'detail' ? 'loadTitle' :
                           state.currentStep === 'loadlinks' ? 'episodeItems' : '';
      const cfg = getConfig();
      const containerSelector = cfg[containerKey] || '';
      const specific = generateSpecificSelectors(selector, fieldKey, containerSelector, field?.extract || 'text');

      if (specific.length === 0) {
        toast('Tidak ditemukan variant lebih spesifik', '');
        return;
      }

      specific.forEach(s => {
        addAiMemory(prov.id, state.currentStep, fieldKey, s, true, field?.extract || 'text', 'specific: ' + selector);
      });
      toast('⊕ Ditemukan ' + specific.length + ' variant lebih spesifik', 'success');
      updateAiMemoryCount();
      renderAiPanel();
    });
  });

  // Expand / Collapse all
  container.querySelectorAll('.ai-expand-all').forEach(btn => {
    btn.addEventListener('click', function() {
      container.querySelectorAll('.ai-sugg-values.hidden').forEach(v => { v.classList.remove('hidden'); });
      container.querySelectorAll('.ai-sugg-expand').forEach(e => { e.textContent = '▼'; });
      // Add all visible suggestion keys
      container.querySelectorAll('.ai-suggestion').forEach(s => {
        _expandedSuggestions.add(s.dataset.field + '::' + s.dataset.selector);
      });
      saveUiState();
    });
  });
  container.querySelectorAll('.ai-collapse-all').forEach(btn => {
    btn.addEventListener('click', function() {
      container.querySelectorAll('.ai-sugg-values:not(.hidden)').forEach(v => { v.classList.add('hidden'); });
      container.querySelectorAll('.ai-sugg-expand').forEach(e => { e.textContent = '▶'; });
      _expandedSuggestions.clear();
      saveUiState();
    });
  });

  // Toggle value list expand
  container.querySelectorAll('.ai-sugg-expand').forEach(el => {
    el.addEventListener('click', function(e) {
      e.stopPropagation();
      const sugg = this.closest('.ai-suggestion');
      const values = sugg.nextElementSibling;
      const key = sugg.dataset.field + '::' + sugg.dataset.selector;
      if (values && values.classList.contains('ai-sugg-values')) {
        values.classList.toggle('hidden');
        this.textContent = values.classList.contains('hidden') ? '▶' : '▼';
        if (values.classList.contains('hidden')) _expandedSuggestions.delete(key);
        else _expandedSuggestions.add(key);
        saveUiState();
      }
    });
  });

  // Find by Example buttons
  container.querySelectorAll('.ai-find-btn').forEach(btn => {
    btn.addEventListener('click', function() {
      const fieldKey = this.dataset.field;
      const input = this.parentElement.querySelector('.ai-find-input');
      const val = input ? input.value.trim() : '';
      if (!val) { toast('Masukkan contoh value terlebih dahulu', 'error'); return; }
      const field = fields.find(f => f.key === fieldKey);
      const cfg = getConfig();
      const containerKey = state.currentStep === 'mainpage' ? 'searchItems' :
                           state.currentStep === 'detail' ? 'loadTitle' :
                           state.currentStep === 'loadlinks' ? 'episodeItems' : '';
      const containerSelector = cfg[containerKey] || '';
      const results = findSelectorsByExample(fieldKey, val, containerSelector, field?.extract || 'text');
      if (results.length === 0) {
        toast('Tidak ditemukan elemen dengan value "' + val + '"', 'error');
        return;
      }
      toast('Ditemukan ' + results.length + ' selector untuk "' + val + '"', 'success');
      // Merge results into suggestions by re-rendering with memory injection
      results.forEach(r => addAiMemory(state.selectedProvider.id, state.currentStep, fieldKey, r.selector, true, field?.extract || 'text', val));
      renderAiPanel();
      // Pre-fill the input again after re-render
      setTimeout(() => {
        const newInput = container.querySelector('.ai-find-input[data-field="' + fieldKey + '"]');
        if (newInput) newInput.value = val;
      }, 0);
    });
  });

  container.querySelectorAll('.ai-sugg-selector').forEach(el => {
    el.addEventListener('click', function() {
      const selector = this.textContent;
      const fieldKey = this.closest('.ai-suggestion').dataset.field;
      const field = fields.find(f => f.key === fieldKey);

      const vals = fieldKey === 'searchItems' || fieldKey === 'episodeItems'
        ? (() => { try { return state.doc.querySelectorAll(selector).length + ' elements'; } catch(e) { return 'error'; } })()
        : extractValues(selector, field?.extract || 'text')
            .slice(0, 8).map(v => v.value || v.text).filter(Boolean).join('\n');

      toast('Selector: ' + selector + '\n' + (typeof vals === 'string' ? vals : vals.join('\n')).slice(0, 200), '');
    });
  });
}

function updateAiMemoryCount() {
  const mem = getAiMemory();
  const prov = state.selectedProvider;
  const count = prov ? mem.filter(m => m.providerId === prov.id).length : mem.length;
  const el = $('#aiMemoryCount');
  if (el) el.textContent = count + ' patterns';
}
