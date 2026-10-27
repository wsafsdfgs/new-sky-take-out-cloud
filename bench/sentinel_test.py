"""Sentinel 限流复现：同时发 N 个 SSE 请求，统计状态码分布。

预期：全局规则 5 QPS、userId=1 单独 1 QPS → 5 并发下 1 个 200 + 4 个 429。
"""
import argparse
import http.client
import threading
from collections import Counter
from urllib.parse import urlsplit

results = []
lock = threading.Lock()
barrier = None


def fire(host, port, base_path, token, idx):
    path = f"{base_path}&sessionId=sentinel{idx}&token={token}"
    barrier.wait()  # 用栅栏保证真的"同时"打进去
    conn = http.client.HTTPConnection(host, port, timeout=30)
    status, note = None, ""
    try:
        conn.request("GET", path, headers={"Accept": "text/event-stream"})
        resp = conn.getresponse()
        status = resp.status
        if status == 200:
            chunk = resp.read(120)
            note = "streaming" if chunk else "empty"
        else:
            note = resp.read(200).decode("utf-8", "replace").replace("\n", " ")[:120]
    except Exception as exc:
        status, note = type(exc).__name__, str(exc)[:120]
    finally:
        try:
            conn.close()
        except Exception:
            pass
    with lock:
        results.append((status, note))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--n", type=int, default=5)
    args = ap.parse_args()

    u = urlsplit(args.url)
    host, port = u.hostname, u.port or 80
    base_path = u.path + (("?" + u.query) if u.query else "")
    if "?" not in base_path:
        base_path += "?"

    global barrier
    barrier = threading.Barrier(args.n)
    threads = [
        threading.Thread(target=fire, args=(host, port, base_path, args.token, i))
        for i in range(args.n)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    print("\n" + "=" * 58)
    print(f"并发 {args.n} 个请求同时打到 {args.url}")
    for status, note in sorted(results, key=lambda x: str(x[0])):
        print(f"  status={status:<20} {note}")
    print(f"\n状态码分布: {dict(Counter(str(s) for s, _ in results))}")
    print("=" * 58 + "\n")


if __name__ == "__main__":
    main()