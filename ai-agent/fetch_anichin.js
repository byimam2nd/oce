const axios = require('axios');

(async () => {
  try {
    const resp = await axios.get('https://anichin.cafe/anime/kuroshitsuji-season-2-sub-indo/', {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      },
      timeout: 15000,
    });
    const html = resp.data;
    
    if (html.toLowerCase().includes('cloudflare') || html.includes('just a moment')) {
      console.log('BLOCKED by Cloudflare');
      return;
    }
    
    const eplisterMatch = html.match(/<ul[^>]*class="[^"]*eplister[^"]*"[^>]*>[\s\S]*?<\/ul>/i);
    if (eplisterMatch) {
      console.log('=== eplister ul found, length:', eplisterMatch[0].length);
      console.log(eplisterMatch[0].substring(0, 5000));
    } else {
      console.log('eplister ul NOT found');
      const liCount = (html.match(/<li[^>]*>/g) || []).length;
      console.log('Total li tags:', liCount);
    }
  } catch(e) {
    console.log('Error:', e.message);
  }
})();
