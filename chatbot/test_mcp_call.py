import requests
BASE = 'http://127.0.0.1:5678'
PATH = '/mcp/2714421f-0865-468b-b938-0d592153a235'

def get_endpoint():
    with requests.get(BASE + PATH, headers={'Accept': 'text/event-stream'}, stream=True, timeout=10) as resp:
        for line in resp.iter_lines():
            if not line:
                continue
            text = line.decode()
            print('SSE:', text)
            if text.startswith('data: /mcp'):
                return text.split('data: ')[1].strip()
    raise RuntimeError('no endpoint event received')

def post(endpoint, payload):
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
    resp = requests.post(BASE + endpoint, headers=headers, json=payload, timeout=10)
    print(f"POST {payload['method']} status {resp.status_code} body {resp.text!r}")
    return resp

if __name__ == '__main__':
    endpoint = get_endpoint()
    post(endpoint, {
        'jsonrpc': '2.0',
        'id': 'init-1',
        'method': 'initialize',
        'params': {
            'protocolVersion': '2024-11-05',
            'clientInfo': {'name': 'test-client', 'version': '0.1'},
            'capabilities': {}
        }
    })
    post(endpoint, {
        'jsonrpc': '2.0',
        'id': 'list-1',
        'method': 'tools/list',
        'params': {}
    })
    post(endpoint, {
        'jsonrpc': '2.0',
        'id': 'call-1',
        'method': 'tools/call',
        'params': {
            'name': 'wikipedia',
            'arguments': {'input': 'cats'}
        }
    })
