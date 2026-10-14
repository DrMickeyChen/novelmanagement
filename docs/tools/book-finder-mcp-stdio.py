"""Optional stdio bridge for MCP clients. Uses the platform JWT through the gateway.
Set NOVEL_MCP_URL and NOVEL_ACCESS_TOKEN in the client's process environment.
Never logs tokens or HTTP response bodies. No third-party Python dependencies.
"""
import json
import os
import sys
import urllib.request
import urllib.error


def main():
    url = os.environ.get('NOVEL_MCP_URL', 'http://127.0.0.1:8080/api/ai/book-finder/mcp')
    token = os.environ.get('NOVEL_ACCESS_TOKEN', '')
    if not token:
        print('NOVEL_ACCESS_TOKEN is required; use your platform access token.', file=sys.stderr)
        return 1
    for line in sys.stdin:
        message = None
        try:
            message = json.loads(line)
            request = urllib.request.Request(url, data=json.dumps(message).encode('utf-8'), method='POST', headers={
                'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-03-26'})
            with urllib.request.urlopen(request, timeout=65) as response:
                if response.status == 202:
                    continue
                result = json.load(response)
            print(json.dumps(result, ensure_ascii=False), flush=True)
        except Exception as error:
            # Notifications must not produce JSON-RPC responses.
            if isinstance(message, dict) and 'id' in message:
                status = error.code if isinstance(error, urllib.error.HTTPError) else 'unavailable'
                print(json.dumps({'jsonrpc': '2.0', 'id': message['id'], 'error': {
                    'code': -32000, 'message': 'MCP transport error: ' + str(status)}}), flush=True)
            else:
                print('MCP transport or input error', file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
