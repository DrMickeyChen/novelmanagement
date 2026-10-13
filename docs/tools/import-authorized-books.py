"""Download user-authorized novels and import through the normal administrator API.
Credentials are read from environment variables and never written to the manifest.
"""
import os, re, json, time, html, io, zipfile, urllib.request, urllib.parse, mimetypes
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'data' / 'import-books'
ROOT.mkdir(parents=True, exist_ok=True)
BASE = 'http://localhost:8080/api'
MANIFEST = ROOT / 'manifest.json'
records = json.loads(MANIFEST.read_text('utf-8')) if MANIFEST.exists() else []

def fetch(url, data=None, headers=None, timeout=90):
    request = urllib.request.Request(url, data=data, headers=headers or {'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read(55 * 1024 * 1024 + 1)
        if len(body) > 55 * 1024 * 1024: raise ValueError('Download exceeds limit')
        return body

def text(value):
    return html.unescape(re.sub('<[^>]+>', '', value)).strip()

def capture(pattern, page, fallback=''):
    match = re.search(pattern, page, re.S)
    return text(match.group(1)) if match else fallback

def canonical_category(group, title, tags):
    overrides = {'毛盾天师':'悬疑','流风回雪剑':'武侠','风云英雄':'武侠','鬼道':'悬疑',
                 '绾青丝':'言情','重生之权宠小仙妻':'言情','黑客　―　幻世恩仇':'游戏竞技','我是布兰顿罗伊':'游戏竞技',
                 '生化之我是丧尸':'科幻','快穿之女主的男神总被我抢':'言情','三国寻娇':'历史'}
    overrides.update({'雍正皇帝':'历史','朝鲜战争':'历史','娇妻出轨之谜':'都市',
                      '风月大帝':'都市','起点':'游戏竞技','平凡的世界':'都市','血色浪漫':'都市',
                      '云之舞（心之永恒）':'言情','倾帝心':'言情','极品特工女皇':'言情'})
    overrides.update({'荒野游龙':'武侠','山上有个贼':'玄幻','磊落青衫行':'武侠',
                      '兵锋时刻':'历史','我是汉灵帝':'历史','机械魔偶师':'科幻','神鬼竞技场':'科幻',
                      '实习医生':'都市','综漫之七杀道':'同人','火影之远传':'同人','草莽英雄':'历史','神史':'都市'})
    if title in overrides: return overrides[title]
    joined = ','.join(tags)
    if any(t in joined for t in ['体育','篮球','网游','电子竞技']): return '游戏竞技'
    if any(t in joined for t in ['武侠','江湖']): return '武侠'
    if any(t in joined for t in ['古代言情','宫斗宅斗','现代言情','总裁','甜宠']): return '言情'
    if any(t in joined for t in ['悬疑灵异','悬疑推理','捉鬼']): return '悬疑'
    if group == 8 and any(t in joined for t in ['同人','综漫','火影','海贼','动漫']): return '同人'
    if any(t in joined for t in ['历史','军事','三国','抗战']): return '历史'
    if any(t in joined for t in ['都市','职场','校园']): return '都市'
    if any(t in joined for t in ['科幻','星际','末世']): return '科幻'
    if any(t in joined for t in ['玄幻','修仙','仙侠','奇幻']): return '玄幻'
    if group == 1 and any(t in ','.join(tags) for t in ['军人', '军旅', '军事']): return '历史'
    if group == 1 and any(t in title for t in ['出轨', '娇妻']): return '都市'
    if group == 6 and any(t in ','.join(tags) for t in ['悬疑', '恐怖', '灵异', '推理']): return '悬疑'
    return {1:'玄幻',2:'玄幻',3:'都市',4:'历史',5:'游戏竞技',6:'科幻',7:'言情',8:'同人',9:'言情',10:'武侠',0:'其他',14:'都市'}[group]

token = None
def api(path, payload=None, multipart=None, method=None, parse_mode='AUTO'):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    body = None
    if multipart:
        boundary = 'novel-import-' + str(time.time_ns())
        book = multipart
        parts = []
        for key, val in [('encoding', 'AUTO'), ('mode', parse_mode)]:
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{val}\r\n'.encode())
        mime = mimetypes.guess_type(book.name)[0] or 'application/octet-stream'
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{book.name}"\r\nContent-Type: {mime}\r\n\r\n'.encode('utf-8') + book.read_bytes() + b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        body = b''.join(parts); headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
    elif payload is not None:
        body = json.dumps(payload).encode(); headers['Content-Type'] = 'application/json'
    if method:
        req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=180) as response: result = json.load(response)
    else: result = json.loads(fetch(BASE + path, body, headers, timeout=180))
    if result['code'] != 200: raise RuntimeError(str(result['code']) + ': ' + result['message'])
    return result.get('data')

def save():
    MANIFEST.write_text(json.dumps(records, ensure_ascii=False, indent=2), 'utf-8')

def download_cover(page, record):
    if record.get('coverUrl'): return record['coverUrl']
    match = re.search(r'<div class="n-img">\s*<img[^>]+src="([^"]+)"', page)
    if not match: raise ValueError('No cover found')
    source = urllib.parse.urljoin(record['sourceUrl'], html.unescape(match.group(1)))
    raw = fetch(source)
    if len(raw) > 10 * 1024 * 1024: raise ValueError('Cover exceeds 10MB')
    ext = '.png' if raw.startswith(b'\x89PNG') else '.webp' if raw.startswith(b'RIFF') else '.jpg'
    covers = ROOT / 'covers'; covers.mkdir(exist_ok=True)
    file = covers / (record['siteId'] + ext); file.write_bytes(raw)
    cover = api('/author/upload/cover', multipart=file)
    record.update(coverSource=source, coverFile=str(file), coverUrl=cover)
    return cover

def reconcile(categories, selected=None):
    for record in (records if selected is None else selected):
        if not record.get('novelId') or record.get('status') not in ['COMPLETED','REPLACED']: continue
        target = canonical_category(record['siteCategoryId'], record['title'], record['tags'])
        if target not in categories: categories[target] = api('/admin/categories', {'name':target,'sort':100,'status':1})
        detail = api('/admin/novels/' + str(record['novelId']))
        cover = detail.get('cover') or ''
        if record['status'] == 'COMPLETED' and not cover:
            page = fetch(record['sourceUrl']).decode('utf-8'); cover = download_cover(page,record)
        if detail['categoryId'] != categories[target] or cover != (detail.get('cover') or ''):
            published = detail['status'] == 2
            if published: api('/admin/novels/'+str(record['novelId'])+'/status', {'status':3,'remark':'Merge equivalent categories and attach local cover'},method='PUT')
            payload = {k:detail.get(k) for k in ['title','intro','tags','isFinish']}
            payload.update(categoryId=categories[target],cover=cover)
            api('/author/novels/'+str(record['novelId']),payload,method='PUT')
            if published: api('/admin/novels/'+str(record['novelId'])+'/status', {'status':2,'remark':'Classification and cover correction completed'},method='PUT')
        record['category'] = target
        if record.get('options'): record['options'].update(categoryId=categories[target],cover=cover)
        save();print('Classification/cover checked: '+record['title']+' -> '+target,flush=True)
    for duplicate in ['玄幻奇幻','传统武侠','修真仙侠']:
        if duplicate in categories:
            api('/admin/categories/'+str(categories[duplicate]),method='DELETE')
            del categories[duplicate]
            print('Merged category: '+duplicate,flush=True)

def run():
    global token
    login = api('/auth/login', {'username': os.environ.get('NOVEL_ADMIN_USER', 'admin'), 'password': os.environ['NOVEL_ADMIN_PASSWORD']})
    token = login['token']
    categories = {c['name']: c['id'] for c in api('/admin/categories') if c['status'] == 1}
    additional = int(os.environ.get('NOVEL_IMPORT_ADDITIONAL', '0'))
    plan = None
    if additional:
        plan_path = ROOT / 'batch-002.json'
        if plan_path.exists(): plan = json.loads(plan_path.read_text('utf-8'))
        else:
            baseline = [r['novelId'] for r in records if r.get('status') == 'COMPLETED']
            plan = {'baselineIds':baseline, 'additional':additional, 'targetTotal':len(baseline)+additional,
                    'groupTargets':{str(g):sum(r.get('status')=='COMPLETED' and r.get('siteCategoryId')==g for r in records)+4+(1 if g in [1,3] else 0) for g in [1,10,2,3,4,5,6,7,8,9,0,14]}}
            plan_path.write_text(json.dumps(plan,ensure_ascii=False,indent=2),'utf-8')
        print('Batch target: '+str(plan['targetTotal'])+' total completed books',flush=True)
    else: reconcile(categories)
    print('Existing categories: ' + ', '.join(categories), flush=True)
    category_ids = [1,10,2,3,4,5,6,7,8,9,0,14]
    seen = {r['siteId'] for r in records if r.get('status') == 'COMPLETED'}
    for group in category_ids:
        if plan and sum(r.get('status')=='COMPLETED' for r in records)>=plan['targetTotal']: break
        completed = sum(r.get('status') == 'COMPLETED' and r.get('siteCategoryId') == group for r in records)
        group_target = plan['groupTargets'][str(group)] if plan else 4
        if completed >= group_target: continue
        candidates = []
        for page_no in ([2,3] if plan else [1]):
            try:
                listing = fetch(f'https://ixdzs8.com/sort/{group}/index-3-2-1-{page_no}.html',timeout=35).decode('utf-8')
                candidates.extend(re.findall(r'href="/read/(\d+)/"', listing))
            except Exception as error: print('Listing unavailable: '+str(group)+' / '+str(page_no)+' '+str(error),flush=True)
        candidates = list(dict.fromkeys(candidates))
        print(f'Category {group}: {len(candidates)} candidates', flush=True)
        for site_id in candidates:
            if completed >= group_target: break
            if plan and sum(r.get('status')=='COMPLETED' for r in records)>=plan['targetTotal']: break
            if site_id in seen: continue
            record = {'siteId': site_id, 'siteCategoryId': group, 'sourceUrl': f'https://ixdzs8.com/read/{site_id}/'}
            try:
                page = fetch(record['sourceUrl']).decode('utf-8')
                title = capture(r'<h1[^>]*>(.*?)</h1>', page)
                author = capture(r'<a[^>]*class="bauthor"[^>]*>(.*?)</a>', page)
                source_category = capture(r'<a[^>]*class="nsort"[^>]*>(.*?)</a>', page)
                intro = capture(r'<p[^>]*id="intro"[^>]*>(.*?)</p>', page)[:1000]
                tags = [text(t) for t in re.findall(r'<a[^>]*href="/tags/[^\"]+"[^>]*>(.*?)</a>', page)]
                tags = list(dict.fromkeys(tags)) or [source_category]
                category = canonical_category(group, title, tags)
                download = re.search(r'href="(https://down[^\"]+\.zip)"', page)
                size = capture(r'<span class="nsize">(.*?)</span>', page)
                if not title or not author or not category or not download: raise ValueError('Missing metadata or ZIP link')
                if re.search(r'([\d.]+)万', size) and float(re.search(r'([\d.]+)万', size).group(1)) > 500:
                    print('Skip oversized: ' + title, flush=True); continue
                record.update(title=title, originalAuthor=author, category=category, sourceCategory=source_category, tags=tags, intro=intro, downloadUrl=download.group(1))
                existing = api('/admin/novels?' + urllib.parse.urlencode({'keyword': title, 'size':100}))
                retired_ids = {r.get('novelId') for r in records if r.get('status') == 'REPLACED'}
                if any(b['title'] == title and b['id'] not in retired_ids for b in existing['records']):
                    print('Skip existing: ' + title, flush=True); seen.add(site_id); continue
                print('Downloading: ' + title, flush=True)
                folder = ROOT / str(group); folder.mkdir(exist_ok=True)
                archive_path = folder / (site_id + '.zip')
                archive = archive_path.read_bytes() if archive_path.exists() else fetch(record['downloadUrl'])
                archive_path.write_bytes(archive)
                with zipfile.ZipFile(io.BytesIO(archive)) as z:
                    members = [i for i in z.infolist() if i.filename.lower().endswith('.txt') and not i.is_dir()]
                    if len(members) != 1: raise ValueError('ZIP must contain exactly one TXT')
                    if members[0].file_size > 50 * 1024 * 1024: raise ValueError('TXT exceeds 50MB')
                    raw = z.read(members[0])
                filename = re.sub(r'[<>:"/\\|?*]', '_', title) + '.txt'
                original = folder / (site_id + '-original.txt'); original.write_bytes(raw)
                try: decoded = raw.decode('utf-8-sig')
                except UnicodeError: decoded = raw.decode('gb18030')
                # This site's exports can glue a heading to the preceding paragraph.
                # Only split a short Chinese heading at the end of a physical line.
                decoded, repairs = re.subn(r'(?<!\n)(第[0-9０-９零〇一二三四五六七八九十百千万两]+[章回][^\r\n]{0,80})(?=\r?\n)', r'\n\1', decoded)
                record['headingNewlinesAdded'] = repairs
                book = folder / filename; book.write_text(decoded, encoding='utf-8', newline='\n')
                record['file'] = str(book)
                preview = api('/admin/novels/imports/preview', multipart=book)
                text_size = len(re.sub(r'\s+', '', decoded))
                if preview['totalChapters'] < 4 or preview['preview']['wordCount'] < text_size * .9:
                    alternate = api('/admin/novels/imports/preview', multipart=book, parse_mode='NUMBERED')
                    if alternate['preview']['wordCount'] > preview['preview']['wordCount'] * 1.01:
                        api('/admin/novels/imports/'+preview['taskId'],method='DELETE'); preview=alternate
                    else: api('/admin/novels/imports/'+alternate['taskId'],method='DELETE')
                record.update(taskId=preview['taskId'], chapterCount=preview['totalChapters'], wordCount=preview['preview']['wordCount'], warnings=preview['preview']['warnings'])
                if not preview['totalChapters']: raise ValueError('No chapter headings recognized')
                if text_size > 10000 and preview['preview']['wordCount'] < text_size * .9:
                    api('/admin/novels/imports/'+preview['taskId'],method='DELETE')
                    raise ValueError('Too much text before recognized headings; skipped to avoid partial import')
                if category not in categories:
                    categories[category] = api('/admin/categories', {'name':category, 'sort':100+group, 'status':1})
                cover = download_cover(page, record)
                options = {'title':title, 'originalAuthor':author, 'categoryId':categories[category], 'intro':intro, 'tags':','.join(tags)[:200], 'cover':cover, 'isFinish':1 if 'class="end"' in page else 0}
                record['options'] = options
                status = api('/admin/novels/imports/' + preview['taskId'] + '/confirm', options)
                record['status'] = status['status']; records.append(record); save()
                deadline = time.monotonic() + 600
                while status['status'] in ['QUEUED','IMPORTING'] and time.monotonic() < deadline:
                    time.sleep(2)
                    status = api('/admin/novels/imports/' + preview['taskId'])
                record.update(status=status['status'], novelId=status.get('novelId'), error=status.get('errorMessage')); save()
                if status['status'] != 'COMPLETED': raise ValueError(status.get('errorMessage') or 'Import did not complete')
                detail = api('/novels/' + str(status['novelId']))
                record['readerVerified'] = detail['title'] == title and detail['authorName'] == author
                save(); completed += 1; seen.add(site_id)
                print(f'IMPORTED {title} | {category} | {status["totalChapters"]} chapters | ID {status["novelId"]}', flush=True)
            except Exception as e:
                record.update(status=record.get('status','FAILED'), error=str(e))
                if not any(r is record for r in records): records.append(record)
                save(); print('FAILED ' + record.get('title',site_id) + ': ' + str(e), flush=True)
            time.sleep(1)
    print('Completed imports: ' + str(sum(r.get('status') == 'COMPLETED' for r in records)), flush=True)

if __name__ == '__main__':
    try:
        run()
    finally:
        if token:
            try: api('/auth/logout', {})
            except Exception: pass
