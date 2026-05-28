const express = require('express');
const cors = require('cors');
const path = require('path');
const axios = require('axios');
const { SocksProxyAgent } = require('socks-proxy-agent');
const { loadAllProviderConfigs, commitProviderConfig } = require('../lib/github');

const app = express();
let providersCache = null;
let cacheTime = 0;
const CACHE_TTL = 60000;

app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(express.static(path.join(__dirname, '..', 'public')));

const DEFAULT_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7',
};

function makeProxyAgent(proxyUrl) {
  if (!proxyUrl || !proxyUrl.startsWith('socks')) return null;
  return new SocksProxyAgent(proxyUrl);
}

async function getProviders() {
  const now = Date.now();
  if (providersCache && (now - cacheTime) < CACHE_TTL) {
    return providersCache;
  }
  try {
    providersCache = await loadAllProviderConfigs();
    cacheTime = now;
    console.log(`Loaded ${providersCache.length} provider configs from GitHub`);
    return providersCache;
  } catch (e) {
    console.error('GitHub load error:', e.message);
    if (providersCache) return providersCache;
    throw e;
  }
}

app.get('/api/providers', async (req, res) => {
  try {
    const providers = await getProviders();
    res.json(providers);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/fetch', async (req, res) => {
  const { url, headers, proxy } = req.body;
  if (!url) return res.status(400).json({ error: 'URL is required' });

  const cfg = {
    headers: { ...DEFAULT_HEADERS, ...headers },
    timeout: 30000,
    maxRedirects: 5,
    responseType: 'text',
  };

  if (proxy) {
    const agent = makeProxyAgent(proxy);
    if (agent) {
      cfg.httpsAgent = agent;
      cfg.httpAgent = agent;
    }
  }

  try {
    const response = await axios.get(url, cfg);
    res.json({
      url: response.request?.res?.responseUrl || url,
      html: response.data,
      status: response.status,
      headers: response.headers,
      proxy: proxy ? 'enabled' : 'none',
    });
  } catch (err) {
    const status = err.response?.status || 0;
    const html = err.response?.data || '';
    res.status(200).json({
      url,
      html: typeof html === 'string' ? html : '',
      status,
      error: err.message,
      headers: err.response?.headers || {},
      proxy: proxy ? 'enabled' : 'none',
    });
  }
});

app.post('/api/commit', async (req, res) => {
  const { providerId, config, step } = req.body;
  if (!providerId) return res.status(400).json({ error: 'providerId is required' });
  if (!config || typeof config !== 'object') return res.status(400).json({ error: 'config object is required' });

  try {
    const result = await commitProviderConfig(providerId, config, step || 'mainpage');
    if (result.success) providersCache = null;
    res.json(result);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/push', async (req, res) => {
  res.json({ success: true, message: 'Push not needed — GitHub API commits directly' });
});

module.exports = app;
