"""Reconcile this batch's metadata and verify reader access and locally hosted covers."""
import os, json, re, urllib.request
from collections import Counter
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('batch', Path(__file__).with_name('import-authorized-books.py'))
batch = importlib.util.module_from_spec(spec); spec.loader.exec_module(batch)
batch.token = batch.api('/auth/login', {'username':os.environ.get('NOVEL_ADMIN_USER','admin'), 'password':os.environ['NOVEL_ADMIN_PASSWORD']})['token']

try:
    for record in batch.records:
        if record.get('status') in ['QUEUED','IMPORTING'] and record.get('taskId'):
            task = batch.api('/admin/novels/imports/' + record['taskId'])
            record.update(status=task['status'],novelId=task.get('novelId'))
    batch.save()
    plan_path = batch.ROOT / 'batch-002.json'
    plan = json.loads(plan_path.read_text('utf-8')) if os.environ.get('NOVEL_VERIFY_BATCH') == '2' else None
    selected = [r for r in batch.records if r.get('novelId') and r['novelId'] > max(plan['baselineIds'],default=0)] if plan else batch.records
    categories = {c['name']:c['id'] for c in batch.api('/admin/categories') if c['status']==1}
    batch.reconcile(categories, selected)
    # Source-provided metadata occasionally describes another work.
    for record in selected:
        if record.get('status')!='COMPLETED': continue
        changes = {}
        clean_intro=re.sub(r'#[^\s{]+\{[^}]*\}', '', record.get('intro',''))
        clean_intro=re.sub(r'\s+', ' ',clean_intro).strip()
        if clean_intro!=record.get('intro',''): changes['intro']=clean_intro
        if record['title']=='射雕英雄传':
            changes['intro']='南宋时期，郭靖与黄蓉行走江湖，在武林恩怨与家国危机中成长。故事围绕师承、门派纷争、侠义与保家卫国展开。'
        if record['title']=='娇妻出轨之谜': changes['tags']='都市,婚姻,家庭,悬疑'
        if record['title']=='朝鲜战争': changes['tags']='历史,军事,战争,纪实'
        if changes:
            novel_id=str(record['novelId']); detail=batch.api('/admin/novels/'+novel_id)
            batch.api('/admin/novels/'+novel_id+'/status', {'status':3,'remark':'Correct source metadata'},method='PUT')
            options={k:detail.get(k) for k in ['title','intro','tags','isFinish','categoryId','cover']}; options.update(changes)
            batch.api('/author/novels/'+novel_id,options,method='PUT')
            batch.api('/admin/novels/'+novel_id+'/status',{'status':2,'remark':'Metadata correction completed'},method='PUT')
            record['options'].update(changes)
            if 'intro' in changes: record['intro']=changes['intro']
            if 'tags' in changes: record['tags']=changes['tags'].split(',')
    batch.save()
    completed = [r for r in batch.records if r.get('status')=='COMPLETED']
    verification=[]
    for record in completed:
        novel_id=str(record['novelId'])
        detail=batch.api('/novels/'+novel_id)
        catalog=batch.api('/novels/'+novel_id+'/catalog')
        assert detail['title']==record['title'] and detail['authorName']==record['originalAuthor']
        assert detail['sourceType']=='EXTERNAL' and detail['status']==2
        assert detail['categoryId']==record['options']['categoryId']
        assert len(catalog)==record['chapterCount']
        assert detail['wordCount']==record['wordCount']
        assert detail.get('cover','').startswith('/uploads/cover/')
        for chapter in [catalog[0],catalog[len(catalog)//2],catalog[-1]]:
            body=batch.api('/novels/'+novel_id+'/chapters/'+str(chapter['id']))
            assert isinstance(body.get('content'),str)
        with urllib.request.urlopen('http://localhost:8080'+detail['cover']) as image:
            assert image.status==200 and image.headers.get('Content-Type','').startswith('image/')
            signature=image.read(16)
            assert signature.startswith((b'\xff\xd8',b'\x89PNG',b'RIFF'))
        result={'title':record['title'],'novelId':record['novelId'],'category':detail['categoryName'],'chapters':len(catalog),'wordCount':detail['wordCount'],'cover':detail['cover'],'readerVerified':True}
        verification.append(result)
        print('VERIFIED '+record['title']+' | '+detail['categoryName'],flush=True)
    counts=Counter(r['category'] for r in verification)
    report={'completed':len(verification),'categories':dict(counts),'wordCount':sum(r['wordCount'] for r in verification),'chapters':sum(r['chapters'] for r in verification),'books':verification}
    (batch.ROOT/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    for duplicate in ['其他','同人']:
        if duplicate in categories:
            matches=batch.api('/admin/novels?categoryId='+str(categories[duplicate])+'&size=1')
            if matches['total']==0: batch.api('/admin/categories/'+str(categories[duplicate]),method='DELETE')
    (batch.ROOT/'README.md').write_text('# 本地小说测试书库\n\n本批次完成 '+str(len(verification))+' 部导入。\n\n- `manifest.json`：来源、下载、标签、分类、封面、任务和失败记录。\n- `verification.json`：读者接口核验结果与成功作品清单。\n- 数字子目录：ZIP、原始 TXT 与补齐标题换行的 UTF-8 TXT。\n- `covers/`：下载的原始封面；系统使用上传后返回的 `/uploads/cover/…` 地址。\n\n解析异常的旧版本已下架，未计入成功数量。源站默认封面按默认图保存。下载资料未加入 Git。\n','utf-8')
    print(json.dumps({k:report[k] for k in ['completed','categories','wordCount','chapters']},ensure_ascii=False),flush=True)
finally:
    batch.api('/auth/logout',{})
