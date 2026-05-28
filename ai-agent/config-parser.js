const fs = require('fs');
const path = require('path');

const CONFIG_DIR = path.join(__dirname, '..', 'BaseProvider', 'src', 'main', 'kotlin', 'com', 'baseprovider', 'config');
const EXCLUDED = ['ProviderConfig.kt', 'GlobalConfig.kt', 'ConfigRegistry.kt'];

function parseConfigFile(filepath) {
  const content = fs.readFileSync(filepath, 'utf-8');
  const basename = path.basename(filepath, '.kt');

  // Extract config name from val NAME =
  const nameMatch = content.match(/val\s+(\w+)\s*=\s*GLOBAL_CONFIG\.copy\s*\(/);
  if (!nameMatch) return null;
  const configName = nameMatch[1].toLowerCase();

  // Extract the content inside GLOBAL_CONFIG.copy( ... )
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
  return { configName, body, basename };
}

function parseKeyValuePairs(body) {
  const result = {};
  // Tokenize the body into key-value pairs, respecting nested parens and strings
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
  let depth = 0; let inString = false; let inMultiline = false; let escape = false;
  let start = 0;
  for (let i = 0; i < body.length; i++) {
    const ch = body[i];
    if (escape) { escape = false; continue; }
    if (ch === '\\' && inString) { escape = true; continue; }
    if (ch === '"' && !inMultiline) {
      if (body.slice(i, i+3) === '"""') { inMultiline = true; i += 2; continue; }
      inString = !inString; continue;
    }
    if (ch === '"' && inMultiline) {
      if (body.slice(i, i+3) === '"""') { inMultiline = false; i += 2; continue; }
      continue;
    }
    if (inString || inMultiline) continue;
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if ((ch === ',' || ch === '\n') && depth === 0) {
      const seg = body.slice(start, i).trim();
      if (seg) result.push(seg);
      start = i + 1;
    }
  }
  const last = body.slice(start).trim();
  if (last) result.push(last);
  return result;
}

function parseValue(raw) {
  raw = raw.trim();

  // null / empty
  if (raw === 'null' || raw === '') return '';

  // Empty list
  if (raw === 'emptyList()' || raw === 'emptySet()') return [];

  // Boolean
  if (raw === 'true') return true;
  if (raw === 'false') return false;

  // Number
  if (/^-?\d+(\.\d+)?$/.test(raw) && !raw.startsWith('0x')) {
    return raw.includes('.') ? parseFloat(raw) : parseInt(raw, 10);
  }

  // Regex: Regex("...")
  const regexMatch = raw.match(/^Regex\s*\(\s*"(.*)"\s*(?:,\s*RegexOption\.\w+\s*)?\)$/);
  if (regexMatch) return regexMatch[1];

  // Triple-quoted string: """..."""
  const tqMatch = raw.match(/^"""([\s\S]*)"""$/);
  if (tqMatch) return tqMatch[1].trim();

  // Single-quoted string: "..." (handle escapes)
  const sqMatch = raw.match(/^"(.*)"\s*$/);
  if (sqMatch) return sqMatch[1].replace(/\\"/g, '"').replace(/\\n/g, '\n');

  // listOf(...) or setOf(...)
  if (raw.startsWith('listOf(') || raw.startsWith('setOf(')) {
    const inner = raw.slice(raw.indexOf('(') + 1, raw.lastIndexOf(')'));
    return parseListOrSet(inner);
  }

  // mapOf(...)
  if (raw.startsWith('mapOf(')) {
    const inner = raw.slice(raw.indexOf('(') + 1, raw.lastIndexOf(')'));
    return parseMap(inner);
  }

  // Object reference like TvType.Anime - skip non-data types
  if (raw.startsWith('TvType.') || raw.startsWith('BLOAT_REGEX') || raw.match(/^[A-Z_]+$/)) {
    return null; // skip type references
  }

  // Pair expression "key" to value
  if (raw.includes(' to ')) {
    const parts = raw.split(/\s+to\s+/);
    if (parts.length === 2) {
      return [parseValue(parts[0]), parseValue(parts[1])];
    }
  }

  return raw;
}

function parseListOrSet(inner) {
  if (!inner.trim()) return [];
  const items = splitByComma(inner);
  return items.map(item => parseValue(item.trim())).filter(v => v !== null);
}

function parseMap(inner) {
  if (!inner.trim()) return {};
  const result = {};
  const items = splitByComma(inner);
  for (const item of items) {
    const toIdx = item.lastIndexOf(' to ');
    if (toIdx === -1) continue;
    const key = parseValue(item.slice(0, toIdx).trim());
    const val = parseValue(item.slice(toIdx + 4).trim());
    if (key != null && val != null) result[key] = val;
  }
  return result;
}

function splitByComma(text) {
  const result = [];
  let depth = 0; let inString = false; let inMultiline = false; let escape = false;
  let start = 0;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (escape) { escape = false; continue; }
    if (ch === '\\' && inString) { escape = true; continue; }
    if (ch === '"' && !inMultiline) {
      if (text.slice(i, i+3) === '"""') { inMultiline = true; i += 2; continue; }
      inString = !inString; continue;
    }
    if (ch === '"' && inMultiline) {
      if (text.slice(i, i+3) === '"""') { inMultiline = false; i += 2; continue; }
      continue;
    }
    if (inString || inMultiline) continue;
    if (ch === '(' || ch === '[' || ch === '{') depth++;
    if (ch === ')' || ch === ']' || ch === '}') depth--;
    if (ch === ',' && depth === 0) {
      result.push(text.slice(start, i).trim());
      start = i + 1;
    }
  }
  const last = text.slice(start).trim();
  if (last) result.push(last);
  return result;
}

function loadAllConfigs() {
  const files = fs.readdirSync(CONFIG_DIR).filter(f => f.endsWith('.kt') && !EXCLUDED.includes(f));
  const configs = [];
  for (const file of files) {
    const filepath = path.join(CONFIG_DIR, file);
    const parsed = parseConfigFile(filepath);
    if (!parsed) continue;
    const pairs = parseKeyValuePairs(parsed.body);
    configs.push({
      id: parsed.configName,
      fileName: parsed.basename,
      ...pairs,
    });
  }
  return configs;
}

module.exports = { loadAllConfigs };
