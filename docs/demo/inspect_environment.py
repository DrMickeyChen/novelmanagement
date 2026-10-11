"""Read-only inspection of the configured development database and local gateway."""
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.demo-tools'))
import pymysql
import requests
import yaml


def database_config():
    config = yaml.safe_load((ROOT / 'backend/novel-content/src/main/resources/application-dev.yml').read_text(encoding='utf-8'))
    ds = config['spring']['datasource']
    match = re.search(r'jdbc:mysql://([^:/]+):(\d+)/([^?]+)', ds['url'])
    return dict(host=match[1], port=int(match[2]), database=match[3], user=ds['username'],
                password=ds['password'], charset='utf8mb4', connect_timeout=10,
                cursorclass=pymysql.cursors.DictCursor)


if __name__ == '__main__':
    result = {}
    db = pymysql.connect(**database_config())
    with db.cursor() as c:
        c.execute('SELECT @@hostname host, DATABASE() db, VERSION() version')
        result['database'] = c.fetchone()
        c.execute('SHOW TABLES')
        tables = [next(iter(row.values())) for row in c.fetchall()]
        result['tables'] = {}
        for table in tables:
            c.execute(f'SELECT COUNT(*) n FROM `{table}`')
            count = c.fetchone()['n']
            c.execute(f'SHOW COLUMNS FROM `{table}`')
            result['tables'][table] = {'count': count, 'columns': [row['Field'] for row in c.fetchall()]}
        c.execute('SELECT u.id,u.username,u.nickname,u.status,u.deleted,r.role_code FROM sys_user u '
                  'LEFT JOIN sys_user_role ur ON ur.user_id=u.id LEFT JOIN sys_role r ON r.id=ur.role_id ORDER BY u.id')
        result['users'] = c.fetchall()
        c.execute('SELECT id,role_code,status,deleted FROM sys_role')
        result['roles'] = c.fetchall()
        c.execute('SELECT id,provider,model,enabled,api_key IS NOT NULL AND api_key <> "" AS has_key FROM ai_config')
        result['ai'] = c.fetchall()
    db.close()
    result['gateway'] = {}
    for port in (8080, 3000):
        try:
            r = requests.get(f'http://localhost:{port}/api/ping', timeout=5)
            result['gateway'][port] = {'status':r.status_code,'body':r.json()}
        except Exception as e:
            result['gateway'][port] = {'error': type(e).__name__}
    print(json.dumps(result, ensure_ascii=True, default=str, indent=2))
