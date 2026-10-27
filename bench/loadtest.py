"""极简 HTTP 压测器：线程池 + keep-alive，输出 QPS / P50 / P95 / P99 / 错误率。

用法:
  python loadtest.py --url "http://localhost:8080/user/dish/list?categoryId=21" \
                     --concurrency 50 --duration 15 --header "authentication=eyJ..."
"""
import argparse
import http.client
import statistics
import sys
import threading
import time
from urllib.parse import urlsplit


def worker(url_parts, headers, deadline, latencies, statuses, lock):
    host, port, path = url_parts
    conn = http.client.HTTPConnection(host, port, timeout=10)
    local_lat = []
    local_status = []
    while time.time() < deadline:
        t0 = time.perf_counter()
        try:
            conn.request("GET", path, headers=headers)
            resp = conn.getresponse()
            body = resp.read()
            local_status.append(resp.status)
            if resp.status != 200:
                local_status.append(f"body:{len(body)}")
        except Exception as exc:
            local_status.append(type(exc).__name__)
            try:
                conn.close()
            except Exception:
                pass
            conn = http.client.HTTPConnection(host, port, timeout=10)
            continue
        finally:
            local_lat.append((time.perf_counter() - t0) * 1000)
    with lock:
        latencies.extend(local_lat)
        statuses.extend(local_status)
    try:
        conn.close()
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--concurrency", type=int, default=10)
    ap.add_argument("--duration", type=int, default=10)
    ap.add_argument("--header", action="append", default=[])
    args = ap.parse_args()

    u = urlsplit(args.url)
    host = u.hostname
    port = u.port or 80
    path = u.path + (("?" + u.query) if u.query else "")
    url_parts = (host, port, path)

    headers = {}
    for h in args.header:
        k, _, v = h.partition("=")
        headers[k.strip()] = v.strip()

    # 预热
    try:
        c = http.client.HTTPConnection(host, port, timeout=10)
        c.request("GET", path, headers=headers)
        pre = c.getresponse()
        pre.read()
        c.close()
        print(f"[warmup] status={pre.status}")
    except Exception as exc:
        print(f"[warmup] failed: {exc}", file=sys.stderr)
        return 1

    latencies, statuses, lock = [], [], threading.Lock()
    deadline = time.time() + args.duration
    threads = [
        threading.Thread(target=worker, args=(url_parts, headers, deadline, latencies, statuses, lock))
        for _ in range(args.concurrency)
    ]

    t_start = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - t_start

    ok = [s for s in statuses if isinstance(s, int)]
    errs = [s for s in statuses if not isinstance(s, int) or s != 200]
    total = len(ok)

    def pct(p):
        if not latencies:
            return 0.0
        s = sorted(latencies)
        idx = min(int(round((p / 100) * len(s) + 0.5)) - 1, len(s) - 1)
        return s[max(idx, 0)]

    print(f"\n{'='*58}")
    print(f"URL          : {args.url}")
    print(f"并发         : {args.concurrency}    时长: {elapsed:.2f}s")
    print(f"总请求       : {total}")
    print(f"QPS          : {total/elapsed:.1f}")
    print(f"成功率       : {(total-len(errs))/total*100:.2f}%   (异常/非200: {len(errs)})")
    if latencies:
        print(f"平均延迟     : {statistics.mean(latencies):.2f} ms")
        print(f"P50 / P95 / P99 : {pct(50):.2f} / {pct(95):.2f} / {pct(99):.2f} ms")
        print(f"Max          : {max(latencies):.2f} ms")
    if errs:
        from collections import Counter
        print(f"错误分布     : {dict(Counter(map(str, errs)))}")
    print(f"{'='*58}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())