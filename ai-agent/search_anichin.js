const axios = require('axios');

(async () => {
  try {
    // First search for a valid anime
    const searchResp = await axios.get('https://anichin.cafe/', {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      },
      timeout: 15000,
    });
    const html = searchResp.data;
    
    if (html.toLowerCase().includes('cloudflare') || html.includes('just a moment')) {
      console.log('BLOCKED by Cloudflare');
      return;
    }
    
    // Find anime links
    const linkMatches = html.match(/href="([^"]*\/anime\/[^"]+)"/g);
    if (linkMatches) {
      const urls = [...new Set(linkMatches.map(m => m.replace(/^href="/, '').replace(/"$/, '')))];
      console.log('Anime links found:', urls.slice(0, 5));
    } else {
      console.log('No anime links found on homepage');
      // Show part of the HTML to understand structure
      console.log(html.substring(0, 3000));
    }
  } catch(e) {
    console.log('Error:', e.message);
  }
})();
