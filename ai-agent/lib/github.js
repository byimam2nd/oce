const axios = require('axios');
const { loadAllConfigs, parseConfigContent } = require('./config-parser');

const OWNER = process.env.GITHUB_OWNER || 'byimam2nd';
const REPO = process.env.GITHUB_REPO || 'oce';
const TOKEN = process.env.GITHUB_TOKEN || '';
const CONFIG_DIR = 'BaseProvider/src/main/kotlin/com/baseprovider/config';
const API_BASE = `https://api.github.com/repos/${OWNER}/${REPO}`;

const AXIOS_CFG = {
  headers: {
    'Accept': 'application/vnd.github.v3+json',
    'User-Agent': 'oce-ai-agent/1.0',
  },
  timeout: 15000,
};
if (TOKEN) AXIOS_CFG.headers['Authorization'] = `Bearer ${TOKEN}`;

async function githubFetch(url, options = {}) {
  try {
    const res = await axios.get(url, { ...AXIOS_CFG, ...options });
    return res.data;
  } catch (err) {
    const status = err.response?.status || 0;
    const text = typeof err.response?.data === 'string' ? err.response.data : JSON.stringify(err.response?.data || '');
    throw new Error(`GitHub API ${status}: ${text.slice(0, 200)}`);
  }
}

async function githubPut(url, data) {
  try {
    const res = await axios.put(url, data, {
      ...AXIOS_CFG,
      headers: { ...AXIOS_CFG.headers, 'Content-Type': 'application/json' },
    });
    return res.data;
  } catch (err) {
    const status = err.response?.status || 0;
    const text = typeof err.response?.data === 'string' ? err.response.data : JSON.stringify(err.response?.data || '');
    throw new Error(`GitHub API PUT ${status}: ${text.slice(0, 200)}`);
  }
}

async function listConfigFiles() {
  const data = await githubFetch(`${API_BASE}/contents/${CONFIG_DIR}`);
  if (!Array.isArray(data)) return [];
  return data
    .filter(f => f.name.endsWith('.kt') && f.type === 'file')
    .map(f => ({ name: f.name, path: f.path, downloadUrl: f.download_url }));
}

async function rawFetch(url) {
  const res = await axios.get(url, {
    headers: { 'User-Agent': 'oce-ai-agent/1.0' },
    timeout: 15000,
    responseType: 'text',
  });
  return res.data;
}

async function getFileContent(path) {
  const data = await githubFetch(`${API_BASE}/contents/${path}`);
  const content = Buffer.from(data.content, 'base64').toString('utf-8');
  return { content, sha: data.sha, name: data.name };
}

async function updateFileContent(path, content, message) {
  const existing = await getFileContent(path);
  const base64 = Buffer.from(content, 'utf-8').toString('base64');

  return githubPut(`${API_BASE}/contents/${path}`, {
    message,
    content: base64,
    sha: existing.sha,
  });
}

async function loadAllProviderConfigs() {
  const files = await listConfigFiles();
  const contentMap = {};

  for (const file of files) {
    try {
      contentMap[file.name] = await rawFetch(file.downloadUrl);
    } catch (e) {
      console.error(`Failed to fetch ${file.name}:`, e.message);
    }
  }

  return loadAllConfigs(contentMap);
}

async function commitProviderConfig(providerId, config, step) {
  const files = await listConfigFiles();
  let targetFile = null;

  for (const file of files) {
    try {
      const content = await rawFetch(file.downloadUrl);
      const parsed = parseConfigContent(content, file.name);
      if (parsed && parsed.id === providerId.toLowerCase()) {
        targetFile = { ...file, content, parsed };
        break;
      }
    } catch (e) {
      continue;
    }
  }

  if (!targetFile) {
    throw new Error(`Config file not found for provider: ${providerId}`);
  }

  const changes = [];
  let newContent = targetFile.content;

  for (const [key, value] of Object.entries(config)) {
    if (!value && value !== false && value !== 0) {
      const updated = updateConfigValue(newContent, key, null);
      if (updated !== newContent) {
        changes.push({ key, old: extractValue(newContent, key), new: null });
        newContent = updated;
      }
    } else {
      const updated = updateConfigValue(newContent, key, String(value));
      if (updated !== newContent) {
        changes.push({ key, old: extractValue(newContent, key), new: String(value) });
        newContent = updated;
      }
    }
  }

  if (changes.length === 0) {
    return { success: false, message: 'No changes to commit', changes: [] };
  }

  const message = generateCommitMessage(providerId, changes, step);
  if (!message) {
    return { success: false, message: 'Could not generate commit message', changes };
  }

  await updateFileContent(targetFile.path, newContent, message);

  return {
    success: true,
    message,
    changes,
    file: targetFile.name,
  };
}

function updateConfigValue(content, key, value) {
  const regex = new RegExp(`^([ \\t]+${key}\\s*=\\s*)(?:"(?:[^"\\\\]|\\\\.)*"|[^,\\n]+)(,?)`, 'm');
  const valStr = value === null || value === undefined || value === '' ? 'null' : `"${String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
  return content.replace(regex, `$1${valStr}$2`);
}

function extractValue(content, key) {
  const regex = new RegExp(`${key}\\s*=\\s*("(?:[^"\\\\]|\\\\.)*"|[^,\\n]+)`, 'm');
  const match = content.match(regex);
  if (!match) return null;
  let val = match[1].trim();
  if (val.startsWith('"') && val.endsWith('"')) {
    val = val.slice(1, -1).replace(/\\"/g, '"').replace(/\\\\/g, '\\');
  }
  return val === 'null' ? null : val;
}

function generateCommitMessage(providerId, changes, step) {
  if (changes.length === 0) return null;

  const stepLabel = { mainpage: 'mainpage', detail: 'detail', loadlinks: 'loadlinks' }[step] || step;
  const fieldLabels = {
    searchItems: 'searchItems container', searchTitle: 'title',
    searchHref: 'href/link', searchPoster: 'poster',
    searchRating: 'rating', searchEpText: 'episode text',
    loadTitle: 'detail title', loadPoster: 'detail poster',
    loadDesc: 'description', loadInfoBox: 'info box',
    loadTags: 'tags', loadRating: 'detail rating',
    loadStatus: 'status', loadQuality: 'quality',
    loadTrailer: 'trailer', loadRecommend: 'recommendations',
    loadBanner: 'banner', episodeItems: 'episode container',
    episodeHref: 'episode link', episodeTitle: 'episode title',
    episodeNum: 'episode number', episodeDesc: 'episode description',
    episodeTime: 'episode time', linkOptions: 'server options',
    downloadItems: 'download items', actorItems: 'actor items',
    actorName: 'actor name', watchButtons: 'watch buttons',
    seasonContainer: 'season container', imdbExternal: 'IMDb link',
    tmdbExternal: 'TMDB link', iframeTag: 'iframe selector',
    followLinkSelector: 'follow link', seasonDataSelector: 'season data',
    ajaxPlayerUrl: 'player URL', selectorJsonData: 'JSON selector',
    hrefCleanRegex: 'href clean regex', hrefCleanReplace: 'href clean replace',
    yearSelector: 'year selector', yearExtractorRegex: 'year regex',
  };

  const changedNames = changes.map(c => fieldLabels[c.key] || c.key);
  const uniqueNames = [...new Set(changedNames)];

  let message;
  if (changes.length === 1) {
    const c = changes[0];
    const label = fieldLabels[c.key] || c.key;
    message = `Update ${providerId} ${stepLabel} ${label}`;
    if (c.old && c.new && c.old !== c.new) {
      message += `: ${c.old} → ${c.new}`;
    }
  } else {
    message = `Update ${providerId} ${stepLabel}: ${uniqueNames.join(', ')}`;
  }

  if (message.length > 100) {
    message = `Update ${providerId} ${stepLabel}: ${uniqueNames.length} fields changed`;
  }

  return message;
}

module.exports = {
  getFileContent,
  updateFileContent,
  loadAllProviderConfigs,
  commitProviderConfig,
  updateConfigValue,
  extractValue,
  generateCommitMessage,
};
