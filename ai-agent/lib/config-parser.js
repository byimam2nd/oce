const EXCLUDED = ['ProviderConfig.kt', 'GlobalConfig.kt', 'ConfigRegistry.kt'];

function parseConfigContent(content, filename) {
  const nameMatch = content.match(/val\s+(\w+)\s*=\s*GLOBAL_CONFIG\.copy\s*\(/);
  if (!nameMatch) return null;
  const configName = nameMatch[1].toLowerCase();

  const startIdx = content.indexOf('GLOBAL_CONFIG.copy(');
  if (startIdx === -1) return null;
  let depth = 0;
  let parenStart = startIdx + 'GLOBAL_CONFIG.copy('.length;
  let inString = false; let inMultiline = false; let escape = false;
  let endIdx = -1;
  for (let i = parenStart - 1; i < content.length; i++) {
    const ch = content[i];
    if (escape) { escape = false; continue; }
    if (ch === '\\' && inString) { escape = true; continue; }
    if (ch === '"' && inMultiline) {
      if (content.slice(i, i+3) === '"""') { inMultiline = false; i += 2; continue; }
      continue;
    }
    if (ch === '"' && !inMultiline) {
      if (content.slice(i, i+3) === '"""') { inMultiline = true; i += 2; continue; }
      inString = !inString; continue;
    }
    if (inString || inMultiline) continue;
    if (ch === '(') depth++;
    if (ch === ')') { depth--; if (depth === 0) { endIdx = i; break; } }
  }
  if (endIdx === -1) return null;

  const body = content.slice(parenStart, endIdx);
  const pairs = parseKeyValuePairs(body);
  const basename = filename ? filename.replace(/\.kt$/, '') : configName;

  return {
    id: configName,
    fileName: basename,
    ...pairs,
  };
}

function parseKeyValuePairs(body) {
  const result = {};
  const pairs = splitTopLevel(body);
  for (const pair of pairs) {
    const eqIdx = pair.indexOf('=');
    if (eqIdx === -1) continue;
    const key = pair.slice(0, eqIdx).trim();
    const rawVal = pair.slice(eqIdx + 1).trim();
    result[key] = parseValue(rawVal);
  }
  return result;
}

function splitTopLevel(body) {
  const result = [];
  let depth = 0;
  let inString = false;
  let inMultiline = false;
  let escape = false;
  let start = 0;
  for (let i = 0; i < body.length; i++) {
    const ch = body[i];
    if (escape) { escape = false; continue; }
    if (ch === '\\' && inString) { escape = true; continue; }
    if (ch === '"' && inMultiline) {
      if (body.slice(i, i+3) === '"""') { inMultiline = false; i += 2; continue; }
      continue;
    }
    if (ch === '"' && !inMultiline) {
      if (body.slice(i, i+3) === '"""') { inMultiline = true; i += 2; continue; }
      inString = !inString; continue;
    }
    if (inString || inMultiline) continue;
    if (ch === '(' || ch === '[' || ch === '{') depth++;
    if (ch === ')' || ch === ']' || ch === '}') depth--;
    if (ch === ',' && depth === 0) {
      result.push(body.slice(start, i).trim());
      start = i + 1;
    }
  }
  const last = body.slice(start).trim();
  if (last) result.push(last);
  return result;
}

function parseValue(raw) {
  raw = raw.trim();

  if (raw === 'null') return null;
  if (raw === 'true') return true;
  if (raw === 'false') return false;

  const strMatch = raw.match(/^"(.*)"$/);
  if (strMatch) {
    return strMatch[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
  }

  const listMatch = raw.match(/^listOf\s*\((.*)\)$/s);
  if (listMatch) {
    return parseListValue(listMatch[1]);
  }

  const mapMatch = raw.match(/^mapOf\s*\((.*)\)$/s);
  if (mapMatch) {
    return parseMapValue(mapMatch[1]);
  }

  const setMatch = raw.match(/^setOf\s*\((.*)\)$/s);
  if (setMatch) {
    return parseListValue(setMatch[1]);
  }

  if (/^\d+$/.test(raw)) return parseInt(raw, 10);
  if (/^\d+\.\d+$/.test(raw)) return parseFloat(raw);
  if (/^\d+L$/.test(raw)) return parseInt(raw, 10);

  return raw;
}

function parseListValue(raw) {
  const items = splitTopLevel(raw);
  return items.map(item => {
    item = item.trim();
    const strMatch = item.match(/^"(.*)"\s*$/);
    if (strMatch) return strMatch[1];
    const pairMatch = item.match(/^"(.*)"\s+to\s+"(.*)"$/);
    if (pairMatch) return `"${pairMatch[1]}" to "${pairMatch[2]}"`;
    return item;
  });
}

function parseMapValue(raw) {
  const items = splitTopLevel(raw);
  const result = {};
  for (const item of items) {
    const trimmed = item.trim();
    const pairMatch = trimmed.match(/^"(.*)"\s+to\s+(.*)$/);
    if (pairMatch) {
      result[pairMatch[1]] = parseValue(pairMatch[2].trim());
    }
  }
  return result;
}

function loadAllConfigs(contentMap) {
  const configs = [];
  for (const [filename, content] of Object.entries(contentMap)) {
    if (!filename.endsWith('.kt')) continue;
    if (EXCLUDED.includes(filename)) continue;
    const parsed = parseConfigContent(content, filename);
    if (!parsed) continue;
    configs.push(parsed);
  }
  return configs;
}

module.exports = { parseConfigContent, parseKeyValuePairs, loadAllConfigs, EXCLUDED };
