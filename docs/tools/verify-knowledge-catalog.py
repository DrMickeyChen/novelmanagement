"""Verify directory projection performance; optionally clean this suite's reserved fixtures."""
import json
import time
import urllib.request
import urllib.error
import sys
from pathlib import Path

base = 'http://192.168.127.137:9200'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(method, path, data=None):
    req = urllib.request.Request(base + path, data=None if data is None else json.dumps(data).encode(),
        headers={'Content-Type': 'application/json'}, method=method)
    try:
        with opener.open(req, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if method == 'DELETE' and error.code == 404:
            return {}
        raise

if '--clean-fixtures' in sys.argv:
    hits = call('POST', '/novel_book_knowledge_catalog_v1/_search', {'size': 1000})['hits']['hits']
    for hit in hits:
        doc = hit['_source']['document']
        if (hit['_id'].startswith('8000') and doc['book']['title'] == '\u9694\u79bb\u6d4b\u8bd5\u8d44\u6599'
            and doc['book']['author'] == '\u6d4b\u8bd5\u4f5c\u8005'):
            call('DELETE', '/novel_book_knowledge_v1/_doc/' + hit['_id'] + '?refresh=true')
            call('DELETE', '/novel_book_knowledge_catalog_v1/_doc/' + hit['_id'] + '?refresh=true')
            call('POST', '/novel_book_chunks_v2_2/_delete_by_query?refresh=true',
                 {'query': {'term': {'novelId': hit['_id']}}})
            print('Removed reserved smoke fixture: ' + hit['_id'])

measurements = []
for _ in range(3):
    start = time.perf_counter()
    canonical = call('POST', '/novel_book_knowledge_v1/_search',
        {'size': 1000, '_source': False, 'version': True, 'sort': [{'builtAt': 'desc'}]})['hits']['hits']
    projected = call('POST', '/novel_book_knowledge_catalog_v1/_mget',
        {'ids': [hit['_id'] for hit in canonical]})['docs']
    expected = {hit['_id']: hit['_version'] for hit in canonical}
    assert all(doc.get('found') and doc['_source']['sourceVersion'] == expected[doc['_id']]
               and not doc['_source']['document'].get('chunks') for doc in projected)
    measurements.append(round((time.perf_counter() - start) * 1000, 2))
report = {'books': len(canonical), 'twoRequestMilliseconds': measurements,
          'sourceVersionsMatch': True, 'bodyAndVectorsReturned': False, 'paidModelCalls': 0}
Path('docs/evals/book-finder/knowledge-catalog-verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report))
