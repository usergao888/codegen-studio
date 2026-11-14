#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""本地 OpenAI 兼容桩服务：没有 API Key 时也能验证「真实模型」这条链路。

用法：
    python scripts/stub-openai.py --port 8099
    # 然后启动应用时把模型指向它：
    java -Dstudio.llm.enabled=true -Dstudio.llm.api-key=stub \
         -Dstudio.llm.base-url=http://127.0.0.1:8099 -cp ... com.gaoyc.studio.StudioApplication

它只实现 POST /chat/completions，返回一个 OpenAI 格式的响应，content 里是
{"files":[{"path":"index.html","content":"..."}]} 结构 —— 与真实模型要求的输出一致。
"""

from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

INDEX_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>由桩模型生成</title>
  <link rel="stylesheet" href="styles.css">
</head>
<body>
  <main class="wrap">
    <h1>STUB-LLM-MARKER</h1>
    <p>这段内容来自本地桩服务，用来证明代码真的发起了 HTTP 调用并解析了响应。</p>
  </main>
  <script src="app.js"></script>
</body>
</html>
"""

STYLES_CSS = """:root { --accent: #3d6bfb; }
body { font-family: system-ui, sans-serif; margin: 0; background: #f7f8fa; color: #1b1d21; }
.wrap { max-width: 720px; margin: 64px auto; padding: 0 20px; }
h1 { color: var(--accent); }
"""

APP_JS = """(function () {
  "use strict";
  console.log("stub-llm generated");
})();
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)

        files = [
            {"path": "index.html", "content": INDEX_HTML},
            {"path": "styles.css", "content": STYLES_CSS},
            {"path": "app.js", "content": APP_JS},
        ]
        content = json.dumps({"files": files}, ensure_ascii=False)
        body = json.dumps(
            {
                "id": "chatcmpl-stub",
                "object": "chat.completion",
                "model": self.headers.get("X-Stub-Model", "stub-model"),
                "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": content},
                     "finish_reason": "stop"}
                ],
                "usage": {"prompt_tokens": 100, "completion_tokens": 500, "total_tokens": 600},
            },
            ensure_ascii=False,
        ).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802 - 健康检查用
        if self.path == "/health":
            payload = b'{"status":"ok","stub":true}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, fmt, *args):
        print("[stub-openai] " + (fmt % args))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"stub OpenAI 服务已启动：http://{args.host}:{args.port}/chat/completions")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
