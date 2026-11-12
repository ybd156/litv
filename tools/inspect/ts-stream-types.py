#!/usr/bin/env python3
# 分析 MPEG-TS 的 PAT/PMT，列出每条基本流的 stream_type。
# 用来确认 AV3A 音频在 TS 里到底用哪个 stream_type ——
# 这决定了 media3 的 TsExtractor 能不能识别它。
import sys, urllib.request, re

URL = sys.argv[1] if len(sys.argv) > 1 else ""
UA = "okHttp/Mod-1.5.0.0"

def fetch(url, limit=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as r:
        data = r.read(limit) if limit else r.read()
    return data

def main():
    m3u8 = fetch(URL).decode("utf-8", "replace")
    segs = [l.strip() for l in m3u8.splitlines() if l.strip() and not l.startswith("#")]
    if not segs:
        print("没有分片")
        return
    seg = segs[0]
    if not seg.startswith("http"):
        base = URL.rsplit("/", 1)[0]
        seg = base + "/" + seg
    print("分片:", seg[:100])
    ts = fetch(seg, 188 * 4000)  # 前 4000 个包够解析表了
    print("字节数:", len(ts))

    pmt_pid = None
    streams = []
    for i in range(0, len(ts) - 188 + 1, 188):
        p = ts[i:i + 188]
        if p[0] != 0x47:
            continue
        pid = ((p[1] & 0x1F) << 8) | p[2]
        payload_start = (p[1] & 0x40) != 0
        if not payload_start:
            continue
        afc = (p[3] >> 4) & 0x3
        off = 4
        if afc & 0x2:  # adaptation field
            off = 5 + p[4]
        if off >= 188:
            continue
        sec = p[off:]
        if pid == 0 and len(sec) > 8 and sec[0] == 0x00:  # PAT
            seclen = ((sec[1] & 0x0F) << 8) | sec[2]
            body = sec[8:3 + seclen - 4]
            for j in range(0, len(body) - 3, 4):
                prog = (body[j] << 8) | body[j + 1]
                pmt = ((body[j + 2] & 0x1F) << 8) | body[j + 3]
                if prog != 0:
                    pmt_pid = pmt
                    print("PMT PID =", pmt_pid)
                    break
        elif pmt_pid is not None and pid == pmt_pid and len(sec) > 12 and sec[0] == 0x02:
            seclen = ((sec[1] & 0x0F) << 8) | sec[2]
            info_len = ((sec[10] & 0x0F) << 8) | sec[11]
            k = 12 + info_len
            end = 3 + seclen - 4
            while k + 4 < end:
                st = sec[k]
                epid = ((sec[k + 1] & 0x1F) << 8) | sec[k + 2]
                es_len = ((sec[k + 3] & 0x0F) << 8) | sec[k + 4]
                streams.append((st, epid))
                k += 5 + es_len
        if pmt_pid is not None and streams:
            break

    print("--- 基本流 ---")
    names = {
        0x1B: "H.264", 0x24: "HEVC", 0x0F: "AAC(ADTS)", 0x11: "AAC(LATM)",
        0x03: "MP3", 0x81: "AC3", 0x87: "EAC3", 0x06: "PES私有",
        0x86: "SCTE35", 0x15: "ID3",
    }
    for st, pid in streams:
        print("  stream_type=0x%02X (%3d)  PID=0x%04X  %s" %
              (st, st, pid, names.get(st, "未知")))

if __name__ == "__main__":
    main()
