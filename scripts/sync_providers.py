import os
import re
import sys

# List of HTML-based providers
HTML_PROVIDERS = [
    "Anichin", "Animasu", "Donghuastream", 
    "LayarKaca21", "IndoDrama21", "Pencurimovie", "Samehadaku"
]

TEMPLATE_DIR = "BaseHtmlProvider"
SRC_DIR = TEMPLATE_DIR

# Full overwrite sync for all core logic and constants
FILES_TO_SYNC = {
    "Provider.kt": "{name}.kt",
    "ProviderEkstraktors.kt": "{name}Ekstraktors.kt",
    "ProviderPlugin.kt": "{name}Plugin.kt",
    "ProviderUtils.kt": "{name}Utils.kt",
    "ProviderConstants.kt": "{name}Constants.kt"
}

class Colors:
    HEADER = '\033[95m'; OKBLUE = '\033[94m'; OKCYAN = '\033[96m'
    OKGREEN = '\033[92m'; WARNING = '\033[93m'; FAIL = '\033[91m'
    ENDC = '\033[0m'; BOLD = '\033[1m'

def print_header():
    print(f"{Colors.HEADER}{Colors.BOLD}==================================================")
    print(f"      BASE HTML PROVIDER SYNC ENGINE v7.0 (TOTAL)")
    print(f"=================================================={Colors.ENDC}")

def safe_replace(content, mapping):
    for old, new in mapping.items():
        pattern = re.compile(rf'\b{re.escape(old)}\b')
        content = pattern.sub(new, content)
    return content

def apply_change(target_path, new_content):
    """Menerapkan perubahan secara otomatis."""
    if os.path.exists(target_path):
        with open(target_path, 'r', encoding="utf-8") as f: old_content = f.read()
        if old_content == new_content:
            return False
            
    with open(target_path, 'w', encoding="utf-8") as f: f.write(new_content)
    return True

def sync_provider(name):
    print(f"\n{Colors.OKBLUE}➤ Memproses: {Colors.BOLD}{name}{Colors.ENDC}")
    target_package_path = os.path.join(f"Provider{name}", "src", "main", "kotlin", "com", name)

    ops = 0
    mapping = {
        "TemplatesProvider": name,
        "ProviderConstants": f"{name}Constants",
        "ProviderEkstraktors": f"{name}Ekstraktors",
        "ProviderUtils": f"{name}Utils",
        "basehtmlprovider": name
    }

    for src_file, target_template in FILES_TO_SYNC.items():
        src_path = os.path.join(SRC_DIR, src_file)
        target_path = os.path.join(target_package_path, target_template.format(name=name))
        
        if not os.path.exists(src_path): continue
        
        with open(src_path, 'r', encoding="utf-8") as f: content = f.read()
        
        # Apply name mapping and common tags
        content = safe_replace(content, mapping)
        content = re.sub(r'TAG = ".*?"', f'TAG = "{name}"', content)
        
        if apply_change(target_path, content):
            print(f"  {Colors.OKGREEN}✔{Colors.ENDC} Sinkron: {target_template.format(name=name)}")
            ops += 1
        else:
            print(f"  {Colors.OKCYAN}•{Colors.ENDC} Up-to-date: {target_template.format(name=name)}")

    return ops

if __name__ == "__main__":
    os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    print_header()
    total = sum(sync_provider(p) for p in HTML_PROVIDERS)
    print(f"\n{Colors.HEADER}SYNC SELESAI! Total file diperbarui: {total}{Colors.ENDC}")
