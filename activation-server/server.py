#!/usr/bin/env python3
import argparse
import json
import sqlite3
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DB_PATH = Path(__file__).with_name('activations.db')
LOCK = threading.Lock()


def db():
    conn = sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)
    conn.execute('PRAGMA journal_mode=WAL')
    conn.execute('''CREATE TABLE IF NOT EXISTS bindings(
        file_id TEXT PRIMARY KEY,
        pair_id TEXT NOT NULL,
        device_id TEXT NOT NULL,
        created_at TEXT NOT NULL
    )''')
    return conn


def activate(route_id, traffic_id, device_id):
    if not route_id or not traffic_id or not device_id:
        return 400, {'ok': False, 'code': 'BAD_REQUEST', 'message': '缺少绑定参数'}
    pair_id = route_id + '::' + traffic_id
    now = datetime.now(timezone.utc).isoformat()
    with LOCK:
        conn = db()
        try:
            conn.execute('BEGIN IMMEDIATE')
            rows = conn.execute(
                'SELECT file_id,pair_id,device_id FROM bindings WHERE file_id IN (?,?)',
                (route_id, traffic_id)
            ).fetchall()
            for file_id, existing_pair, existing_device in rows:
                if existing_device != device_id:
                    conn.execute('ROLLBACK')
                    return 409, {
                        'ok': False,
                        'code': 'FILE_ALREADY_BOUND',
                        'message': '该配置已在另一台设备解析，不能再次解析'
                    }
                if existing_pair != pair_id:
                    conn.execute('ROLLBACK')
                    return 409, {
                        'ok': False,
                        'code': 'PAIR_MISMATCH',
                        'message': '该配置已用于其他文件配对'
                    }
            for file_id in (route_id, traffic_id):
                conn.execute(
                    'INSERT OR IGNORE INTO bindings(file_id,pair_id,device_id,created_at) VALUES(?,?,?,?)',
                    (file_id, pair_id, device_id, now)
                )
            conn.execute('COMMIT')
            return 200, {'ok': True, 'code': 'OK', 'message': '设备唯一绑定成功'}
        except Exception:
            try: conn.execute('ROLLBACK')
            except Exception: pass
            raise
        finally:
            conn.close()


class Handler(BaseHTTPRequestHandler):
    server_version = 'VelaGateActivation/1.0'

    def _json(self, status, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == '/health':
            return self._json(200, {'ok': True, 'service': 'velagate-activation'})
        return self._json(404, {'ok': False, 'code': 'NOT_FOUND'})

    def do_POST(self):
        if self.path != '/activate':
            return self._json(404, {'ok': False, 'code': 'NOT_FOUND'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length <= 0 or length > 64 * 1024:
                return self._json(400, {'ok': False, 'code': 'BAD_REQUEST'})
            body = json.loads(self.rfile.read(length).decode('utf-8'))
            status, result = activate(
                str(body.get('routeFileId', '')).strip(),
                str(body.get('trafficFileId', '')).strip(),
                str(body.get('deviceId', '')).strip()
            )
            return self._json(status, result)
        except Exception as e:
            return self._json(500, {'ok': False, 'code': 'SERVER_ERROR', 'message': str(e)})

    def log_message(self, fmt, *args):
        print('%s - %s' % (self.address_string(), fmt % args))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8787)
    args = parser.parse_args()
    db().close()
    print(f'VelaGate activation server: http://{args.host}:{args.port}')
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
