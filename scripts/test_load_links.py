import requests
from bs4 import BeautifulSoup
import re
import time

# --- CONFIGURATION ---
EPISODE_URL = "https://tv3.nontondrama.my/perfect-crown-season-1-episode-1-2026"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

headers = {
    "User-Agent": USER_AGENT,
    "Referer": "https://tv3.nontondrama.my/"
}

def test_lk21_episode_load_links():
    print(f"Testing LoadLinks for Episode: {EPISODE_URL}")
    session = requests.Session()
    session.headers.update(headers)

    try:
        # 1. Fetch Episode Page
        resp = session.get(EPISODE_URL, timeout=20)
        soup = BeautifulSoup(resp.text, "html.parser")
        
        # 2. Find player links
        player_links = soup.select("ul#player-list a")
        if not player_links:
            print(f"FAILED: No player links found in {EPISODE_URL}")
            # print("HTML Snippet:")
            # print(resp.text[:2000])
            return

        print(f"Found {len(player_links)} player links.")

        for i, link in enumerate(player_links):
            href = link.get("href")
            if not href.startswith("http"):
                if href.startswith("//"): href = "https:" + href
                else: href = "/".join(resp.url.split("/")[:3]) + href
            
            print(f"[{i+1}] Processing Link: {href}")
            
            # 3. Follow the player link
            try:
                # Referer MUST be the page that had the player list
                p_resp = session.get(href, headers={"Referer": resp.url}, timeout=15)
                p_soup = BeautifulSoup(p_resp.text, "html.parser")
                
                # 4. Find Iframe
                iframe = p_soup.select_one("div.embed-container iframe")
                if not iframe:
                    iframe = p_soup.select_one("iframe")
                
                if iframe:
                    src = iframe.get("src")
                    print(f"   SUCCESS: Found Iframe: {src}")
                    
                    # 5. Handle short.icu redirect
                    if "short.icu" in src:
                        print(f"   Detected short.icu, following redirect...")
                        r_resp = session.get(src, allow_redirects=True, timeout=15)
                        print(f"   Final Redirect URL: {r_resp.url}")
                else:
                    print("   FAILED: No iframe found in player page.")
            except Exception as e:
                print(f"   ERROR processing player link: {e}")

    except Exception as e:
        print(f"FATAL ERROR: {e}")

if __name__ == "__main__":
    test_lk21_episode_load_links()
