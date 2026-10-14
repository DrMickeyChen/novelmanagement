"""Isolated end-to-end acceptance: real ES/Redis, Mock model, in-memory test books.
Never imports novels into MySQL, changes AI config, or stops existing application processes.
Run after Maven package. Paid providers are explicitly disabled in the temporary AI process.
"""
import json
import os
import re
import socket
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ.get('NOVEL_JAVA', r'D:\MickeyChen\usefultool\IDEA\JDK\bin\java.exe'))
BACKEND = ROOT / 'backend'
RUN = uuid.uuid4().hex
UID = str(7_000_000_000_000_000 + int(RUN[:8], 16))
IDS = [str(8_000_000_000_000_000 + int(RUN[:8], 16) * 10 + i) for i in range(3)]
TOKEN = os.environ.get('INTERNAL_TOKEN') or re.search(r'token:\s*\$\{INTERNAL_TOKEN:([^}]+)\}', (BACKEND / 'novel-ai/src/main/resources/application-dev.yml').read_text(encoding='utf-8')).group(1)
HEADERS = {'X-Internal-Token': TOKEN, 'X-User-Id': UID, 'X-Jti': RUN, 'X-Roles': 'ADMIN', 'X-Username': 'isolated-smoke'}
BOOKS = [{'id': i, 'title': f'隔离验收书{n + 1}', 'author': '测试作者', 'cover': '', 'category': '悬疑', 'tags': '推理',
          'intro': '开篇是侦探调查案件的故事。', 'completed': True, 'wordCount': 300000} for n, i in enumerate(IDS)]
BODY = '侦探调查现场寻找案件线索。' * 80 + '真凶是某人，结局有人死亡。'


class ContentFixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, data):
        raw = json.dumps({'code': 200, 'message': '成功', 'data': data}, ensure_ascii=False).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json;charset=UTF-8')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path.startswith('/internal/book-finder/categories'):
            return self.reply(['悬疑'])
        if '/knowledge/' in self.path:
            book_id = self.path.split('/knowledge/')[1].split('?')[0]
            book = next((b for b in BOOKS if b['id'] == book_id), None)
            count = int(re.search(r'count=(\d+)', self.path).group(1))
            return self.reply(None if book is None else {'book': book, 'fingerprint': 'fixture-' + book_id, 'requestedChapters': count,
                'totalChapters': 20, 'truncated': False, 'chapters': [{'id': book_id, 'number': 1, 'title': '第一章', 'text': BODY, 'version': 'v1', 'truncated': False}]})
        self.send_error(404)

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path.endswith('/details'):
            return self.reply([b for i in payload for b in BOOKS if b['id'] == i])
        if self.path.endswith('/search'):
            return self.reply([b for b in BOOKS if b['id'] not in payload.get('excludeIds', [])
                and (payload.get('category') is None or b['category'] == payload['category'])
                and (payload.get('completed') is None or b['completed'] == payload['completed'])
                and (payload.get('minWords') is None or b['wordCount'] >= payload['minWords'])
                and (payload.get('maxWords') is None or b['wordCount'] <= payload['maxWords'])])
        self.send_error(404)


class RedisCleanup:
    """Tiny RESP client avoids installing packages solely for cleanup."""
    def __init__(self, host, password):
        self.socket = socket.create_connection((host, 6379), timeout=5)
        self.stream = self.socket.makefile('rb')
        if password:
            self.command('AUTH', password)

    def command(self, *args):
        values = [str(a).encode('utf-8') for a in args]
        self.socket.sendall(b'*' + str(len(values)).encode() + b'\r\n' + b''.join(b'$' + str(len(v)).encode() + b'\r\n' + v + b'\r\n' for v in values))
        return self.read()

    def read(self):
        kind, payload = self.stream.read(1), self.stream.readline()[:-2]
        if kind == b'-':
            raise RuntimeError('Redis cleanup command rejected')
        if kind == b':':
            return int(payload)
        if kind == b'$':
            length = int(payload)
            if length == -1:
                return None
            value = self.stream.read(length); self.stream.read(2)
            return value.decode('utf-8')
        if kind == b'*':
            return [self.read() for _ in range(int(payload))]
        return payload.decode('utf-8')

    def scan(self, pattern):
        cursor = '0'
        while True:
            cursor, keys = self.command('SCAN', cursor, 'MATCH', pattern, 'COUNT', 100)
            yield from keys
            if cursor == '0':
                break

    def close(self):
        self.stream.close(); self.socket.close()


def api(method, path, payload=None):
    response = requests.request(method, 'http://127.0.0.1:18085' + path, json=payload, headers=HEADERS, timeout=15)
    if method == 'GET':
        for _ in range(2):
            if response.status_code < 500: break
            time.sleep(1)
            response = requests.get('http://127.0.0.1:18085' + path, headers=HEADERS, timeout=15)
    response.raise_for_status()
    data = response.json()
    assert data['code'] == 200, data.get('message')
    return data.get('data')


def chat(message, conversation=None):
    request_id = str(uuid.uuid4())
    payload = {'message': message, 'requestId': request_id}
    if conversation:
        payload['conversationId'] = conversation
    with requests.post('http://127.0.0.1:18085/api/ai/book-finder/chat', json=payload,
                       headers={**HEADERS, 'Accept': 'text/event-stream, application/json;q=0.9'}, stream=True, timeout=70) as response:
        response.raise_for_status()
        response.encoding = 'utf-8'
        event, result, complete = None, None, False
        for line in response.iter_lines(decode_unicode=True):
            if line.startswith('event:'):
                event = line[6:].strip()
            elif line.startswith('data:'):
                data = json.loads(line[5:].strip())
                if event == 'error':
                    raise AssertionError(data)
                if event == 'result':
                    result = data
                if event == 'done':
                    complete = True
        assert result is not None and complete
        return result


def main():
    processes, handles, conversation = [], [], None
    fixture = ThreadingHTTPServer(('127.0.0.1', 18089), ContentFixture)
    threading.Thread(target=fixture.serve_forever, daemon=True).start()
    try:
        common = ['--spring.cloud.nacos.discovery.enabled=false', '--spring.cloud.nacos.config.enabled=false',
                  '--spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration',
                  '--novel.book-finder.content-url=http://127.0.0.1:18089', '--logging.level.root=WARN']
        for name, port, extra in [('search', 18084, []), ('ai', 18085, ['--novel.ai.provider=mock', '--novel.book-finder.search-url=http://127.0.0.1:18084',
                '--novel.book-finder.eval.directory=logs/book-finder-eval-smoke-'+RUN, '--novel.ai.disposition-sync-initial-ms=3600000', '--novel.book-finder.embedding.url=', '--novel.book-finder.embedding.model=',
                '--novel.book-finder.embedding.key=', '--novel.book-finder.embedding.use-database=false', '--novel.book-finder.rerank.use-database=false', '--novel.book-finder.rerank.url=', '--novel.book-finder.rerank.key='])]:
            jar = BACKEND / f'novel-{name}/target/novel-{name}.jar'
            assert jar.is_file() and JAVA.is_file()
            handle = open(BACKEND / f'logs/knowledge-smoke-{name}-{RUN}.log', 'wb')
            handles.append(handle)
            processes.append(subprocess.Popen([str(JAVA), '-jar', str(jar), f'--server.port={port}', '--server.address=127.0.0.1',
                f'--logging.file.name=logs/knowledge-smoke-{name}-{RUN}-app.log', *common, *extra], cwd=BACKEND,
                stdout=handle, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0)))
        for _ in range(60):
            if any(p.poll() is not None for p in processes):
                raise AssertionError('Temporary service startup failed; inspect ignored knowledge-smoke logs')
            try:
                if api('GET', '/api/ai/book-finder/quota')['mock']:
                    break
            except requests.RequestException:
                pass
            time.sleep(1)
        else:
            raise AssertionError('Temporary service startup timed out')
        root = '/api/admin/ai/book-finder'
        rerank_config = api('GET', root + '/rerank-config')
        assert 'apiKey' not in rerank_config and 'key' not in rerank_config
        embedding_config = api('GET', root + '/embedding-config')
        assert 'apiKey' not in embedding_config and 'key' not in embedding_config
        assert 1 <= embedding_config['batchSize'] <= 16
        assert not api('GET', root + '/status')['embeddingConfigured']
        assert not any(d['novelId'] in IDS for d in api('GET', root + '/knowledge'))
        jobs = api('POST', root + '/build', {'ids': IDS, 'chapters': 10, 'vectors': False})
        expected = {t['id'] for t in jobs}
        for _ in range(45):
            rows = [t for t in api('GET', root + '/tasks') if t['id'] in expected]
            if all(t['status'] == 'DONE' for t in rows) and len(rows) == len(expected):
                break
            assert not any(t['status'] == 'FAILED' for t in rows), rows
            time.sleep(1)
        else:
            raise AssertionError('Knowledge build timed out')
        preview = api('GET', root + '/knowledge/' + IDS[0])
        chunk = preview['chunks'][0]
        api('PUT', root + '/notes/' + IDS[0], {'summary': '仅开篇有侦探调查场景，不能代表全书风格。', 'chunkId': chunk['id'], 'quote': '侦探调查现场'})
        api('POST', root + '/notes/' + IDS[0] + '/review', {'approved': True})
        result = chat('推荐已完结的悬疑小说，开篇有调查线索')
        conversation = result['conversationId']
        assert len(result['recommendations']) == 3
        assert any(r.get('sources') for r in result['recommendations'])
        assert all(r['book']['completed'] for r in result['recommendations'])
        serialized = json.dumps(result, ensure_ascii=False)
        assert '真凶' not in serialized and '死亡' not in serialized
        second = chat('换一批', conversation)
        assert second['recommendations'] == []
        assert api('GET', '/api/ai/book-finder/quota')['unlimited']
        mcp_url='http://127.0.0.1:18085/api/ai/book-finder/mcp'
        mcp_headers={**HEADERS, 'Accept':'application/json, text/event-stream'}
        def rpc(method, params=None):
            response=requests.post(mcp_url,json={'jsonrpc':'2.0','id':str(uuid.uuid4()),'method':method,'params':params or {}},headers=mcp_headers,timeout=20)
            response.raise_for_status()
            return response.json()['result']
        assert rpc('initialize',{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'isolated-smoke','version':'1'}})['protocolVersion']=='2025-03-26'
        assert requests.post(mcp_url,json={'jsonrpc':'2.0','method':'notifications/initialized'},headers=mcp_headers,timeout=10).status_code==202
        assert len(rpc('tools/list')['tools'])==3
        tool_result=rpc('tools/call',{'name':'searchBooks','arguments':{'completed':True,'category':'\u60ac\u7591'}})
        assert not tool_result['isError'] and json.loads(tool_result['content'][0]['text'])
        assert requests.get(mcp_url,headers={**HEADERS,'Origin':'https://untrusted.example'},timeout=10).status_code==403
        assert requests.post(mcp_url,json={'jsonrpc':'2.0','id':1,'method':'tools/list'},timeout=10).status_code==401
        evaluation=api('POST',root+'/evaluations',{'label':'Isolated Mock verification','cases':[
            {'id':'first','group':'refresh','prompt':'\u63a8\u8350\u5df2\u5b8c\u7ed3\u7684\u60ac\u7591\u5c0f\u8bf4','expected':{'completed':True},'allowEmpty':False},
            {'id':'next','group':'refresh','prompt':'\u6362\u4e00\u6279','allowEmpty':True}]})
        for _ in range(30):
            evaluation=api('GET',root+'/evaluations/'+evaluation['id'])
            if evaluation['status'] not in ['QUEUED','RUNNING']: break
            time.sleep(1)
        assert evaluation['status']=='DONE' and evaluation['mock']
        assert evaluation['metrics']['automaticPassed']==2 and evaluation['metrics']['estimatedChatCost'] is None
        export=requests.get('http://127.0.0.1:18085'+root+'/evaluations/'+evaluation['id']+'/export',headers=HEADERS,timeout=10)
        export.raise_for_status(); assert export.json()['agentCodeSha256']
        destination=BACKEND.parent/'docs/evals/book-finder/mock-verification-report.json'
        destination.write_text(json.dumps(export.json(),ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({'passed': True, 'mode': 'Mock + real ES/Redis + in-memory fixtures', 'paidCalls': 0,
            'recommendations': 3, 'bodySources': True, 'refreshExcludesShown': True, 'adminUnlimited': True, 'mcpHttp':True, 'durableEvaluationReport':True}, ensure_ascii=False))
    finally:
        for book_id in IDS:
            try:
                api('DELETE', '/api/admin/ai/book-finder/knowledge/' + book_id)
            except Exception:
                pass
        if conversation:
            try:
                api('DELETE', '/api/ai/book-finder/conversations/' + conversation)
            except Exception:
                pass
        # Exact cleanup of our user/session/run keys and isolated task references.
        try:
            text = (BACKEND / 'novel-ai/src/main/resources/application-dev.yml').read_text(encoding='utf-8')
            host = re.search(r'host:\s*([^\s]+)', text).group(1)
            password = re.search(r'password:\s*([^\s]+)', text[text.index('redis:'):]).group(1).strip('"\'')
            match = re.fullmatch(r'\$\{([^:}]+):([^}]+)\}', password)
            if match:
                password = os.environ.get(match.group(1), match.group(2))
            client = RedisCleanup(host, password)
            for pattern in [f'novel:book-finder:*:{UID}:*', f'novel:book-finder:lock:{UID}']:
                for key in list(client.scan(pattern)):
                    if key.startswith('novel:book-finder:') and UID in key.split(':'):
                        client.command('DEL', key)
            for task in client.command('LRANGE', 'agent:knowledge:tasks', 0, 99):
                key = 'agent:knowledge:task:' + task
                value = client.command('GET', key)
                if value and json.loads(value).get('novelId') in IDS:
                    client.command('DEL', key); client.command('LREM', 'agent:knowledge:tasks', 0, task)
            for key in client.command('LRANGE', 'novel:book-finder:runs', 0, 199):
                if UID in key.split(':'):
                    client.command('LREM', 'novel:book-finder:runs', 0, key)
            assert not list(client.scan(f'novel:book-finder:*:{UID}:*'))
            client.close()
        except Exception as exc:
            print('Cleanup notice:', type(exc).__name__)
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=5)
        for handle in handles:
            handle.close()
        fixture.shutdown(); fixture.server_close()


if __name__ == '__main__':
    main()
