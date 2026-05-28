const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const CONFIG_DIR = path.join(__dirname, '..', 'BaseProvider', 'src', 'main', 'kotlin', 'com', 'baseprovider', 'config');
const EXCLUDED = ['ProviderConfig.kt', 'GlobalConfig.kt', 'ConfigRegistry.kt'];
const PROJECT_DIR = path.resolve(__dirname, '..');

function findConfigFile(providerId) {
  const files = fs.readdirSync(CONFIG_DIR).filter(f => f.endsWith('.kt') && !EXCLUDED.includes(f));
  for (const file of files) {
    const content = fs.readFileSync(path.join(CONFIG_DIR, file), 'utf-8');
    const nameMatch = content.match(/val\s+(\w+)\s*=\s*GLOBAL_CONFIG\.copy\s*\(/);
    if (nameMatch && nameMatch[1].toLowerCase() === providerId.toLowerCase()) {
      return { file, content, configName: nameMatch[1] };
    }
  }
  return null;
}

function updateConfigValue(content, key, value) {
  const regex = new RegExp(`^([ \\t]+${key}\\s*=\\s*)(?:"(?:[^"\\\\]|\\\\.)*"|[^,\\n]+)(,?)`, 'm');
  const valStr = value === null || value === undefined || value === '' ? 'null' : `"${String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
  return content.replace(regex, `$1${valStr}$2`);
}

function writeConfig(providerId, config) {
  const found = findConfigFile(providerId);
  if (!found) throw new Error(`Config file not found for provider: ${providerId}`);

  let content = found.content;
  const changes = [];

  for (const [key, value] of Object.entries(config)) {
    if (!value && value !== false && value !== 0) {
      const before = content;
      content = updateConfigValue(content, key, null);
      if (content !== before) changes.push({ key, old: extractValue(before, key), new: null });
    } else {
      const before = content;
      content = updateConfigValue(content, key, String(value));
      if (content !== before) changes.push({ key, old: extractValue(before, key), new: String(value) });
    }
  }

  const filePath = path.join(CONFIG_DIR, found.file);
  fs.writeFileSync(filePath, content, 'utf-8');
  return { filePath, fileName: found.file, changes, configName: found.configName };
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

function readOriginalConfig(providerId) {
  const found = findConfigFile(providerId);
  if (!found) return null;

  const result = {};
  const fields = [
    'searchItems', 'searchTitle', 'searchHref', 'searchPoster', 'searchRating', 'searchEpText',
    'loadTitle', 'loadPoster', 'loadBanner', 'loadDesc', 'loadInfoBox', 'loadTags',
    'loadRating', 'loadStatus', 'loadQuality', 'loadTrailer', 'loadRecommend',
    'episodeItems', 'episodeHref', 'episodeTitle', 'episodeNum', 'episodeDesc', 'episodeTime',
    'linkOptions', 'downloadItems', 'actorItems', 'actorName',
    'watchButtons', 'seasonContainer', 'imdbExternal', 'tmdbExternal',
    'iframeTag', 'followLinkSelector', 'seasonDataSelector',
    'ajaxPlayerUrl', 'selectorJsonData',
    'hrefCleanRegex', 'hrefCleanReplace', 'yearSelector', 'yearExtractorRegex',
  ];
  for (const key of fields) {
    const val = extractValue(found.content, key);
    if (val !== null) result[key] = val;
  }
  return result;
}

function getRepoStatus() {
  try {
    const status = execSync('git status --porcelain', { cwd: PROJECT_DIR, encoding: 'utf-8' });
    return status.trim() ? status.split('\n').map(l => l.trim()).filter(Boolean) : [];
  } catch (e) {
    throw new Error('Git status failed: ' + e.message);
  }
}

function generateCommitMessage(providerId, changes, step) {
  if (changes.length === 0) return null;

  const stepLabel = { mainpage: 'mainpage', detail: 'detail', loadlinks: 'loadlinks' }[step] || step;
  const fieldLabels = {
    searchItems: 'searchItems container',
    searchTitle: 'title',
    searchHref: 'href/link',
    searchPoster: 'poster',
    searchRating: 'rating',
    searchEpText: 'episode text',
    loadTitle: 'detail title',
    loadPoster: 'detail poster',
    loadDesc: 'description',
    loadInfoBox: 'info box',
    loadTags: 'tags',
    loadRating: 'detail rating',
    loadStatus: 'status',
    loadQuality: 'quality',
    loadTrailer: 'trailer',
    loadRecommend: 'recommendations',
    loadBanner: 'banner',
    episodeItems: 'episode container',
    episodeHref: 'episode link',
    episodeTitle: 'episode title',
    episodeNum: 'episode number',
    episodeDesc: 'episode description',
    episodeTime: 'episode time',
    linkOptions: 'server options',
    downloadItems: 'download items',
    actorItems: 'actor items',
    actorName: 'actor name',
    watchButtons: 'watch buttons',
    seasonContainer: 'season container',
    imdbExternal: 'IMDb link',
    tmdbExternal: 'TMDB link',
    iframeTag: 'iframe selector',
    followLinkSelector: 'follow link',
    seasonDataSelector: 'season data',
    ajaxPlayerUrl: 'player URL',
    selectorJsonData: 'JSON selector',
    hrefCleanRegex: 'href clean regex',
    hrefCleanReplace: 'href clean replace',
    yearSelector: 'year selector',
    yearExtractorRegex: 'year regex',
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

function gitCommit(providerId, config, step) {
  const result = writeConfig(providerId, config);
  if (result.changes.length === 0) {
    return { success: false, message: 'No changes to commit', changes: [] };
  }

  const message = generateCommitMessage(providerId, result.changes, step);
  if (!message) {
    return { success: false, message: 'Could not generate commit message', changes: result.changes };
  }

  const relPath = path.relative(PROJECT_DIR, result.filePath);
  try {
    execSync(`git add "${relPath}"`, { cwd: PROJECT_DIR, encoding: 'utf-8' });
    execSync(`git commit -m "${message.replace(/"/g, '\\"')}"`, { cwd: PROJECT_DIR, encoding: 'utf-8' });
    return {
      success: true,
      message,
      changes: result.changes,
      commitHash: execSync('git rev-parse --short HEAD', { cwd: PROJECT_DIR, encoding: 'utf-8' }).trim(),
    };
  } catch (e) {
    throw new Error('Git commit failed: ' + e.message);
  }
}

function gitPush() {
  try {
    const output = execSync('git push', { cwd: PROJECT_DIR, encoding: 'utf-8' });
    return { success: true, message: output.trim() || 'Push completed' };
  } catch (e) {
    if (e.stdout && e.stderr) {
      return { success: false, message: e.stderr.toString().trim() || e.stdout.toString().trim() };
    }
    throw new Error('Git push failed: ' + e.message);
  }
}

module.exports = { writeConfig, readOriginalConfig, gitCommit, gitPush, getRepoStatus, generateCommitMessage };
