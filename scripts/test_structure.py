import requests
from bs4 import BeautifulSoup

urls = ["https://lk21.de", "https://v1.animasu.top"]
headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"}

for url in urls:
    print(f"\n--- Testing {url} ---")
    try:
        resp = requests.get(url, headers=headers, timeout=15, verify=False)
        print(f"Status: {resp.status_code}")
        soup = BeautifulSoup(resp.text, "html.parser")
        
        if "lk21" in url:
            items = soup.select("article")
            print(f"Total articles: {len(items)}")
            if items:
                print(f"Sample article: {items[0].prettify()[:500]}")
            
            items2 = soup.select("div#gmr-main-load article")
            print(f"Total articles in #gmr-main-load: {len(items2)}")
            
        if "animasu" in url:
            items = soup.select("div.listupd div.bs")
            print(f"Total items in .listupd .bs: {len(items)}")
            if items:
                img = items[0].select_one("img")
                if img:
                    print(f"Image attributes: {img.attrs}")
    except Exception as e:
        print(f"Error: {e}")
