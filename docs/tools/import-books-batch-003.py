"""Add 300 authorized local-test books, using existing admin import APIs.

Resume with the same command. No credentials are persisted, no old books are changed.
Real parsed word counts, public reader access, chapter catalog and local cover are checked.
"""
import importlib.util
import json
import os
import re
import time
import urllib.parse
import io
import zipfile
import threading
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
from pathlib import Path

spec = importlib.util.spec_from_file_location('previous_batch', Path(__file__).with_name('import-authorized-books.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
PLAN = b.ROOT / 'batch-003.json'
REPORT = b.ROOT / 'batch-003-verification.json'
GROUPS = [1, 10, 2, 3, 4, 5, 6, 7, 8, 9]
TARGETS = {'玄幻':40, '科幻':35, '历史':35, '都市':35, '悬疑':35, '武侠':35, '言情':35, '游戏竞技':35, '同人':15}
MIN_WORDS, MAX_WORDS = 200000, 1000000
plan = None
categories = {}
existing_titles = set()
import_lock = threading.RLock()
original_fetch = b.fetch

def bounded_fetch(url, data=None, headers=None, timeout=15):
    if url.startswith('https://'): time.sleep(.25)
    return original_fetch(url, data, headers, min(timeout,15) if url.startswith('https://') else timeout)

b.fetch = bounded_fetch

def atomic_manifest():
    with import_lock:
        temp=b.MANIFEST.with_suffix('.tmp')
        temp.write_text(json.dumps(b.records,ensure_ascii=False,indent=2),'utf-8')
        temp.replace(b.MANIFEST)

b.save = atomic_manifest

def classify(group, title, tags, intro):
    overrides={'月上重火':'言情','大手印':'武侠','都市鬼才':'都市','极品诡仙':'玄幻','再创大汉':'历史',
               '茄子胡同槐树院':'都市','蛇蝎':'都市','天才淡定妃：陛下，乖乖臣服':'言情',
               '茶道师的烦恼':'言情','总有情敌想要攻略我2':'言情','GAY三生缘之当GAY爱上直男':'言情',
               '帝王心术':'历史','新明':'历史'}
    if title in overrides: return overrides[title]
    if any(word in title for word in ['男妾','王妃','皇妃','娇妻','宠妻','总裁','霸总','前妻','弃妃','夫君']): return '言情'
    # The site's newer automatic tags sometimes mislabel classic martial-arts books.
    if group == 10: return '武侠'
    if any(t in title for t in ['火影','海贼','综漫','高达','超时空要塞','名侦探柯南','圣斗士','漫威']) or any(t in tags for t in ['同人','综漫','动漫同人']): return '同人'
    if group==6 and any(word in intro+title for word in ['星际','星球','宇宙','太空','核战','末世','末日','生化','机器人','外星']): return '科幻'
    if group == 2: return '玄幻'
    if group == 5: return '游戏竞技'
    if any(t in tags for t in ['都市言情','现代言情','古代言情','宫斗宅斗','总裁','甜宠','霸总','宠妻']): return '言情'
    if any(t in tags for t in ['悬疑灵异','悬疑推理','捉鬼','推理']): return '悬疑'
    if any(t in tags for t in ['网游','电子竞技','体育','篮球']): return '游戏竞技'
    if '玄幻' in tags and any(t in intro for t in ['魔法','异界','魔王','修炼','修仙']): return '玄幻'
    return b.canonical_category(group,title,tags)

def evidence_tags(record, category):
    # Keep broad genre and only add themes explicitly present in the introduction.
    # Source automatic labels such as "invincible" are not reliable evidence.
    intro=record.get('intro','')
    tags=[category]
    terms={'穿越':['穿越'],'重生':['重生'],'修仙':['修仙','修真'],'魔法':['魔法'],
           '异界':['异界'],'系统':['系统'],'网游':['网游','网络游戏'],'足球':['足球','踢球'],
           '篮球':['篮球'],'校园':['校园','大学生','高中','学校'],'职场':['职场'],
           '灵异':['灵异','鬼魂','鬼怪','捉鬼'],'推理':['推理'],'悬疑':['悬疑'],
           '军事':['军事','军队','战场'],'战争':['战争','抗战'],'大唐':['大唐','唐朝'],
           '三国':['三国'],'末世':['末世'],'星际':['星际'],'科幻':['科幻'],
           '江湖':['江湖'],'武侠':['武侠'],'婚姻':['婚姻','娶她','嫁他']}
    for tag,words in terms.items():
        if tag in TARGETS and tag!=category: continue
        if any(word in intro for word in words) and tag not in tags: tags.append(tag)
    return tags

def correct_metadata(record):
    record.setdefault('sourceTags',list(record['tags']))
    category=classify(record['siteCategoryId'],record['title'],record['sourceTags'],record['intro'])
    tags=evidence_tags(record,category)
    if category==record['category'] and ','.join(tags)==record['options']['tags']:
        record['metadataReviewed']=True
        b.save()
        return
    detail=b.api('/admin/novels/'+str(record['novelId']))
    was_published=detail['status']==2
    if was_published: b.api('/admin/novels/'+str(record['novelId'])+'/status',{'status':3,'remark':'Correct source genre metadata'},method='PUT')
    options={k:detail.get(k) for k in ['title','intro','tags','isFinish','cover']}
    options['categoryId']=categories[category]
    options['tags']=','.join(tags)[:200]
    b.api('/author/novels/'+str(record['novelId']),options,method='PUT')
    if was_published: b.api('/admin/novels/'+str(record['novelId'])+'/status',{'status':2,'remark':'Source genre metadata corrected'},method='PUT')
    record.update(category=category,tags=tags)
    record['metadataReviewed']=True
    record['options'].update(categoryId=categories[category],tags=options['tags'])
    b.save()
    verify(record)
    print('METADATA REVIEWED '+record['title']+' -> '+category,flush=True)

def save_plan():
    temp = PLAN.with_suffix('.tmp')
    temp.write_text(json.dumps(plan, ensure_ascii=False, indent=2), 'utf-8')
    temp.replace(PLAN)

def progress():
    done = [r for r in b.records if r.get('batch') == 3 and r.get('status') == 'COMPLETED' and r.get('readerVerified')]
    return done, Counter(r['category'] for r in done)

def verify(record):
    detail = b.api('/novels/' + str(record['novelId']))
    catalog = b.api('/novels/' + str(record['novelId']) + '/catalog')
    assert detail['title'] == record['title']
    assert detail['authorName'] == record['originalAuthor']
    assert detail['sourceType'] == 'EXTERNAL' and detail['status'] == 2
    assert MIN_WORDS <= detail['wordCount'] <= MAX_WORDS
    assert len(catalog) == record['chapterCount'] and len(catalog) > 0
    assert detail['categoryId'] == record['options']['categoryId']
    assert detail.get('cover', '').startswith('/uploads/cover/')
    # Inspect content at both ends without printing or copying text to logs.
    for chapter in (catalog[0], catalog[-1]):
        content = b.api('/novels/' + str(record['novelId']) + '/chapters/' + str(chapter['id']))
        assert isinstance(content.get('content'), str)
    cover = b.fetch('http://localhost:8080' + detail['cover'], timeout=20)
    assert cover.startswith((b'\xff\xd8', b'\x89PNG', b'RIFF'))
    record.update(readerVerified=True, wordCount=detail['wordCount'])
    existing_titles.add(record['title'])
    b.save()

def report():
    done, counts = progress()
    result = {'batch':3, 'target':300, 'completed':len(done), 'categories':dict(counts),
              'minWords':MIN_WORDS, 'maxWords':MAX_WORDS,
              'totalWords':sum(r['wordCount'] for r in done),
              'totalChapters':sum(r['chapterCount'] for r in done),
              'books':[{k:r.get(k) for k in ['title','originalAuthor','category','tags','novelId','wordCount','chapterCount','file','coverFile','coverUrl','sourceUrl','readerVerified']} for r in done]}
    REPORT.write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
    print('PROGRESS ' + json.dumps({k:result[k] for k in ['completed','target','categories','totalWords','totalChapters']}, ensure_ascii=False), flush=True)

def wait_task(record):
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        status = b.api('/admin/novels/imports/' + record['taskId'])
        record.update(status=status['status'], novelId=status.get('novelId'), error=status.get('errorMessage'))
        b.save()
        if status['status'] == 'COMPLETED':
            verify(record)
            report()
            return
        if status['status'] not in ('QUEUED','IMPORTING'):
            raise ValueError(status.get('errorMessage') or 'Import failed')
        time.sleep(.5)
    raise TimeoutError('Import still running; retain task for resume')

def import_one(item, relaxed=False):
    site_id, group = item['siteId'], item['group']
    record = {'siteId':site_id, 'siteCategoryId':group, 'sourceUrl':f'https://ixdzs8.com/read/{site_id}/', 'batch':3}
    preview = None
    try:
        page = b.fetch(record['sourceUrl'], timeout=30).decode('utf-8')
        title = b.capture(r'<h1[^>]*>(.*?)</h1>', page)
        author = b.capture(r'<a[^>]*class="bauthor"[^>]*>(.*?)</a>', page)
        source_category = b.capture(r'<a[^>]*class="nsort"[^>]*>(.*?)</a>', page)
        intro = b.capture(r'<p[^>]*id="intro"[^>]*>(.*?)</p>', page)[:1000]
        intro = re.sub(r'#[^\s{]+\{[^}]*\}', '', intro).strip()
        tags = list(dict.fromkeys(b.text(t) for t in re.findall(r'<a[^>]*href="/tags/[^\"]+"[^>]*>(.*?)</a>', page)))
        category = classify(group,title,tags,intro)
        if not title or not author or title in existing_titles: return 'existing'
        if category not in categories: return 'category'
        _, counts = progress()
        if not relaxed and counts[category] >= TARGETS[category]: return 'full'
        if relaxed and counts[category] >= 50: return 'full'
        download = re.search(r'href="(https://down[^\"]+\.zip)"', page)
        if not download: return 'no-download'
        if not tags: tags = [category]
        tags = [t for t in tags if t not in TARGETS or t == category]
        if category not in tags: tags.insert(0, category)
        record.update(title=title, originalAuthor=author, category=category, sourceCategory=source_category,
                      tags=tags, intro=intro, downloadUrl=download.group(1))
        folder = b.ROOT / 'batch-003' / str(group)
        folder.mkdir(parents=True, exist_ok=True)
        archive_path = folder / (site_id + '.zip')
        raw_zip = archive_path.read_bytes() if archive_path.exists() else b.fetch(record['downloadUrl'], timeout=45)
        archive_path.write_bytes(raw_zip)
        with zipfile.ZipFile(io.BytesIO(raw_zip)) as archive:
            members = [m for m in archive.infolist() if m.filename.lower().endswith('.txt') and not m.is_dir()]
            if len(members) != 1 or members[0].file_size > 50*1024*1024: raise ValueError('Unexpected ZIP content')
            raw = archive.read(members[0])
        original = folder / (site_id + '-original.txt')
        original.write_bytes(raw)
        try: decoded = raw.decode('utf-8-sig')
        except UnicodeError: decoded = raw.decode('gb18030')
        decoded, repairs = re.subn(r'(?<!\n)(第[0-9０-９零〇一二三四五六七八九十百千万两]+[章回][^\r\n]{0,80})(?=\r?\n)', r'\n\1', decoded)
        filename = re.sub(r'[<>:"/\\|?*]', '_', title)[:120] + '.txt'
        book = folder / filename
        book.write_text(decoded, encoding='utf-8', newline='\n')
        record.update(file=str(book), headingNewlinesAdded=repairs)
        return import_prepared(record,page,decoded,book,relaxed)
    except Exception as error:
        record.setdefault('status','FAILED')
        record['error'] = type(error).__name__ + ': ' + str(error)
        if not any(r is record for r in b.records): b.records.append(record)
        b.save()
        print('FAILED ' + record.get('title',site_id) + ' | ' + record['error'], flush=True)
        # Never submit more imports after an uncertain confirmation outcome.
        if record['status'] in ('CONFIRMING','QUEUED','IMPORTING'): raise
        return 'failed'

def import_prepared(record,page,decoded,book,relaxed):
    # Downloads can overlap; parsing, quota checks, confirmation and DB writes stay serial.
    with import_lock:
        title,author,category,intro,tags=(record[k] for k in ('title','originalAuthor','category','intro','tags'))
        done,counts=progress()
        if len(done)>=300 or title in existing_titles: return 'existing'
        if counts[category]>=(50 if relaxed else TARGETS[category]): return 'full'
        preview = b.api('/admin/novels/imports/preview', multipart=book)
        text_size = len(re.sub(r'\s+', '', decoded))
        if preview['totalChapters'] < 4 or preview['preview']['wordCount'] < text_size*.9:
            alternate = b.api('/admin/novels/imports/preview', multipart=book, parse_mode='NUMBERED')
            if alternate['preview']['wordCount'] > preview['preview']['wordCount']*1.01:
                b.api('/admin/novels/imports/'+preview['taskId'], method='DELETE')
                preview = alternate
            else: b.api('/admin/novels/imports/'+alternate['taskId'], method='DELETE')
        words = preview['preview']['wordCount']
        if not MIN_WORDS <= words <= MAX_WORDS:
            b.api('/admin/novels/imports/'+preview['taskId'], method='DELETE')
            record.update(status='SKIPPED', wordCount=words, error='Outside actual word range')
            b.records.append(record); b.save()
            return 'words'
        if not preview['totalChapters'] or words < text_size*.9:
            b.api('/admin/novels/imports/'+preview['taskId'], method='DELETE')
            raise ValueError('Incomplete chapter recognition, not imported')
        cover = b.download_cover(page, record)
        options = {'title':title, 'originalAuthor':author, 'categoryId':categories[category], 'intro':intro,
                   'tags':','.join(tags)[:200], 'cover':cover, 'isFinish':1 if 'class="end"' in page else 0}
        record.update(taskId=preview['taskId'], chapterCount=preview['totalChapters'], wordCount=words,
                      options=options, warnings=preview['preview']['warnings'], status='CONFIRMING')
        # Persist the task before confirmation: a lost response must not cause duplicate imports.
        b.records.append(record); b.save()
        status = b.api('/admin/novels/imports/'+preview['taskId']+'/confirm', options)
        record.update(status=status['status'], novelId=status.get('novelId')); b.save()
        wait_task(record)
        print('IMPORTED ' + title + ' | ' + category + ' | ' + str(record['wordCount']), flush=True)
        return 'ok'

def main():
    global plan, categories
    login = b.api('/auth/login', {'username':os.environ.get('NOVEL_ADMIN_USER','admin'),
                                'password':os.environ.get('NOVEL_ADMIN_PASSWORD','admin123')})
    b.token = login['token']
    categories = {c['name']:c['id'] for c in b.api('/admin/categories') if c['status'] == 1}
    page_no = 1
    baseline = []
    while True:
        data = b.api('/admin/novels?' + urllib.parse.urlencode({'page':page_no,'size':100}))
        baseline.extend(str(r['id']) for r in data['records'])
        existing_titles.update(r['title'] for r in data['records'])
        if len(baseline) >= data['total'] or not data['records']: break
        page_no += 1
    plan = json.loads(PLAN.read_text('utf-8')) if PLAN.exists() else {
        'batch':3,'additional':300,'baselineIds':baseline,'targets':TARGETS,'minWords':MIN_WORDS,
        'maxWords':MAX_WORDS,'visited':[],'deferred':[],'createdAt':time.strftime('%Y-%m-%d %H:%M:%S')}
    plan['runnerPid']=os.getpid()
    save_plan()
    for record in b.records:
        if record.get('batch') != 3: continue
        if record.get('status') in ('CONFIRMING','QUEUED','IMPORTING'):
            task = b.api('/admin/novels/imports/'+record['taskId'])
            if task['status'] == 'PREVIEWED': b.api('/admin/novels/imports/'+record['taskId']+'/confirm',record['options'])
            wait_task(record)
        elif record.get('status') == 'COMPLETED':
            correct_metadata(record)
            if not record.get('readerVerified'): verify(record)
    visited = set(plan['visited']) | {r['siteId'] for r in b.records if r.get('status') in ('COMPLETED','REPLACED')}
    report()
    skipped = Counter()
    # Rotate source genres and word bands to keep the new library varied.
    for listing_page in range(1,31):
        for group in GROUPS:
            _,counts=progress()
            likely={1:['玄幻'],2:['玄幻'],10:['武侠'],3:['都市'],4:['历史'],5:['游戏竞技'],6:['科幻','悬疑'],7:['言情'],8:['同人'],9:['言情']}[group]
            if all(counts[c]>=TARGETS[c] for c in likely): continue
            for band in (3,2,1):
                _,counts=progress()
                if all(counts[c]>=TARGETS[c] for c in likely): break
                if len(progress()[0]) >= 300: return
                url = f'https://ixdzs8.com/sort/{group}/index-{band}-2-1-{listing_page}.html'
                try: listing = b.fetch(url,timeout=25).decode('utf-8')
                except Exception as error:
                    print('LISTING FAILED '+str(group)+' '+str(listing_page)+' '+type(error).__name__,flush=True)
                    continue
                # Read book-root links independently of item layout / class / nesting.
                items = [(sid,'') for sid in dict.fromkeys(re.findall(r'href="/read/(\d+)/"',listing))]
                if not items:
                    (b.ROOT/'batch-003-empty-page.html').write_text(listing,'utf-8')
                    print('EMPTY LISTING '+url+' bytes='+str(len(listing))+' title='+b.capture(r'<title>(.*?)</title>',listing),flush=True)
                    time.sleep(2)
                    continue
                candidates=[]
                for site_id, block in items:
                    if site_id in visited: continue
                    item = {'siteId':site_id,'group':group}
                    candidates.append(item)
                    visited.add(site_id)
                with ThreadPoolExecutor(max_workers=3) as executor:
                    for item,result in zip(candidates,executor.map(import_one,candidates)):
                        skipped[result] += 1
                        if result == 'full': plan['deferred'].append(item)
                        plan['visited'] = sorted(visited); save_plan()
                if len(progress()[0]) >= 300: return
                print('LISTING '+str(group)+' page='+str(listing_page)+' band='+str(band)+' found='+str(len(items))+' new='+str(len(candidates))+' '+json.dumps(dict(skipped)),flush=True)
    # If one genre has too few correctly parsed books, distribute remaining slots
    # among other genres, keeping each genre at no more than 50 new books.
    for item in plan['deferred']:
        if len(progress()[0]) >= 300: return
        import_one(item, relaxed=True)
    if len(progress()[0]) < 300: raise RuntimeError('Candidate pool exhausted before 300 verified books')

if __name__ == '__main__':
    try: main()
    finally:
        if plan is not None: report()
        if b.token:
            try: b.api('/auth/logout',{})
            except Exception: pass
