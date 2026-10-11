"""Upload the generated project covers through the existing author upload endpoint."""
import json
import os
from pathlib import Path
import sys

from seed_demo import DEMO, GATEWAY, api, administrators, database_config, emit, login
import bcrypt
import pymysql
import requests
from PIL import Image


def main():
    files=[DEMO/'covers'/f'{i:02}.jpg' for i in range(1,21)]
    if not all(path.exists() for path in files):
        raise RuntimeError('All 20 generated covers must exist before uploading.')
    db=pymysql.connect(**database_config())
    admin=administrators(db)[0]
    password=os.environ.get('DEMO_ADMIN_PASSWORD','admin123')
    if not bcrypt.checkpw(password.encode(),admin['password'].encode()):
        raise RuntimeError('Set DEMO_ADMIN_PASSWORD locally to the retained administrator password.')
    db.close()
    token=login(admin['username'],password)
    manifest=DEMO/'cover-urls.json'
    urls=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else {}
    for i,path in enumerate(files,1):
        # Re-running should not create another upload if the existing resource is still reachable.
        old=urls.get(str(i))
        if old:
            r=requests.get(GATEWAY+old,timeout=10)
            if r.status_code==200 and r.headers.get('Content-Type','').startswith('image/'):
                continue
        with Image.open(path) as im:im.verify()
        with path.open('rb') as f:
            url=api('POST','/api/author/upload/cover',token=token,files={'file':(path.name,f,'image/jpeg')})
        if not isinstance(url,str) or not url.startswith('/uploads/cover/'):
            raise RuntimeError('Unexpected cover upload response.')
        response=requests.get(GATEWAY+url,timeout=10)
        if response.status_code!=200 or not response.headers.get('Content-Type','').startswith('image/'):
            raise RuntimeError('Uploaded cover cannot be read through the gateway: '+url)
        urls[str(i)]=url
        manifest.write_text(json.dumps(urls,indent=2),encoding='utf-8')
        emit({'cover_uploaded':i,'bytes':path.stat().st_size})
    emit({'covers_uploaded_and_verified':len(urls)})


if __name__=='__main__':main()
