"""Cryptographic / JS-decoding helpers ported from the OCE runtime.

  - findPackedJsInPage / decodePackedJs  (JsPackerDecoder.kt)
  - aesGcmDecrypt (b64url key parts + iv + payload)  (ConfigDrivenExtractor.kt)
  - decodeUnicodeEscapes  (MasterLinkGenerator.kt)
  - sigDecode (xor hex -> base64 -> drop/reverse/swap)  (ConfigDrivenExtractor.kt)
"""

import base64
import re
import struct

from Crypto.Cipher import AES

DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

# ── packed JS (JsPackerDecoder.kt) ──

_BASE36_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
_BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
_PACKED_JS_SCRIPT_REGEX = re.compile(
    r"<script[^>]*>.*?</script>", re.DOTALL | re.IGNORECASE)


def _to_base(n, base):
    if n == 0:
        return "0"
    chars = _BASE36_CHARS if base == 36 else _BASE62_CHARS
    out = []
    num = n
    while num > 0:
        out.append(chars[num % base])
        num //= base
    return "".join(reversed(out))


def _split_packed_js_args(s):
    args = []
    i = 0
    while i < len(s) and len(args) < 4:
        if s[i] == "'":
            end = i + 1
            while end < len(s):
                end = s.find("'", end)
                if end < 0:
                    return None
                slash_count = 0
                ci = end - 1
                while ci >= 0 and s[ci] == "\\":
                    slash_count += 1
                    ci -= 1
                if slash_count % 2 == 0:
                    args.append(s[i + 1:end])
                    i = end + 1
                    break
                end += 1
        elif s[i] in (",", " "):
            i += 1
        else:
            end = len(s)
            for c in (",", ")", " "):
                idx = s.find(c, i)
                if 0 <= idx < end:
                    end = idx
            args.append(s[i:end])
            i = end
    return args if len(args) >= 4 else None


def find_packed_js_in_page(html):
    """Returns (payload, keywords, base) or None. Port of findPackedJsInPage."""
    if not html:
        return None
    for match in _PACKED_JS_SCRIPT_REGEX.finditer(html):
        script = match.group(0)
        if "function(p,a,c,k,e,d)" not in script or ".split" not in script:
            continue
        start = script.find("}(")
        if start < 0:
            continue
        snippet = script[start:]
        end_idx = snippet.find("'.split")
        if end_idx < 0:
            continue
        raw = snippet[2:end_idx + 1]
        parts = _split_packed_js_args(raw)
        if parts is None:
            continue
        payload_raw = (parts[0]
                       .replace("\\'", "'").replace("\\\"", "\"")
                       .replace("\\n", "\n").replace("\\/", "/"))
        try:
            base = int(parts[1]) if len(parts) > 1 else 36
        except (TypeError, ValueError):
            base = 36
        keywords = parts[3].split("|") if len(parts) > 3 else None
        if keywords is None:
            continue
        return payload_raw, keywords, base
    return None


def decode_packed_js(payload, keywords, base):
    """Port of decodePackedJs: one-pass word-boundary replacement."""
    parts = []
    for i, kw in enumerate(keywords):
        if kw and kw.strip():
            parts.append((_to_base(i, base), kw))
    if not parts:
        return payload
    alternation = "|".join(re.escape(enc) for enc, _ in parts)
    pattern = re.compile(r"\b(?:%s)\b" % alternation)
    by_encoded = dict(parts)

    def repl(m):
        return by_encoded.get(m.group(0), m.group(0))

    return pattern.sub(repl, payload)


# ── AES-GCM (ByseSX style) ──

def b64_url_decode(s):
    fixed = s.replace("-", "+").replace("_", "/")
    pad = "=" * ((4 - len(fixed) % 4) % 4)
    return base64.b64decode(fixed + pad, validate=False)


def aes_gcm_decrypt(key_parts, iv, payload):
    """Port of decryptAesGcm: key = concat of b64url parts, GCM tag 128.

    Java's AES/GCM/NoPadding doFinal produces ciphertext||tag; the tag is the
    trailing mac_len bytes of the payload.
    """
    if not key_parts or not iv or not payload:
        return ""
    try:
        key = b"".join(b64_url_decode(kp) for kp in key_parts)
        nonce = b64_url_decode(iv)
        raw = b64_url_decode(payload)
        mac_len = 16
        if len(raw) <= mac_len:
            return ""
        ciphertext, tag = raw[:-mac_len], raw[-mac_len:]
        cipher = AES.new(key, AES.MODE_GCM, nonce=nonce, mac_len=mac_len)
        decrypted = cipher.decrypt_and_verify(ciphertext, tag)
        text = decrypted.decode("utf-8", errors="replace")
        return text[1:] if text.startswith("\ufeff") else text
    except Exception:
        return ""


# ── unicode escapes (MasterLinkGenerator.kt) ──

_UNICODE_ESCAPE = re.compile(r"\\u([0-9A-Fa-f]{4})")


def decode_unicode_escapes(input_str):
    if "\\u" not in input_str:
        return input_str
    return _UNICODE_ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), input_str)


# ── xor signature decode (Vidguardto) ──

def sig_decode(url):
    """Port of sigDecode: xor hex with 2 -> base64 -> drop/reverse/swap."""
    if not url:
        return url
    parts = url.split("sig=")
    if len(parts) < 2:
        return url
    sig = parts[1].split("&")[0]
    if not sig:
        return url
    try:
        chars = []
        for i in range(0, len(sig), 2):
            chars.append(chr(int(sig[i:i + 2], 16) ^ 2))
        t = "".join(chars)
        pad = {2: "==", 3: "="}.get(len(t) % 4, "")
        decoded = base64.b64decode((t + pad).encode()).decode("utf-8", errors="replace")
        decoded = decoded[:-5]
        decoded = decoded[::-1]
        arr = list(decoded)
        for i in range(0, len(arr), 2):
            if i + 1 < len(arr):
                arr[i], arr[i + 1] = arr[i + 1], arr[i]
        t = "".join(arr)[:-5]
        return url.replace(sig, t)
    except Exception:
        return url