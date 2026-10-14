"""Copy metadata server-side; never rewrite original books or call model APIs."""
import json
import sys
import time
import urllib.request
import urllib.error

base = sys.argv[1] if len(sys.argv) > 1 else 'http://192.168.127.137:9200'
index = 'novel_book_knowledge_catalog_v1'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def call(method, path, data=None):
    req = urllib.request.Request(base + path, data=None if data is None else json.dumps(data).encode(),
                                 headers={'Content-Type': 'application/json'}, method=method)
    with opener.open(req, timeout=30) as response:
        return json.load(response)

try:
    call('PUT', '/' + index, {'mappings': {'dynamic': False, 'properties': {
        'sourceVersion': {'type': 'long'}, 'document': {'type': 'object', 'enabled': False}}}})
except urllib.error.HTTPError as error:
    details = json.load(error)
    if details.get('error', {}).get('type') != 'resource_already_exists_exception':
        raise

started = call('POST', '/_reindex?wait_for_completion=false', {
    'source': {'index': 'novel_book_knowledge_v1', 'size': 20},
    'dest': {'index': index},
    'script': {'lang': 'painless', 'source':
        "long v = ctx._version; def d = ctx._source; d.remove('chunks'); "
        "ctx._source = ['sourceVersion': v, 'document': d]; ctx._version = null;"}})
print(json.dumps(started), flush=True)
while True:
    result = call('GET', '/_tasks/' + started['task'])
    if result.get('completed'):
        if result.get('error') or result.get('response', {}).get('failures'):
            print(json.dumps(result), flush=True)
            raise SystemExit(1)
        call('POST', '/' + index + '/_refresh')
        print(json.dumps({'completed': True, 'response': result.get('response'), 'paidModelCalls': 0,
                          'originalDocumentsModified': False}), flush=True)
        break
    time.sleep(2)
