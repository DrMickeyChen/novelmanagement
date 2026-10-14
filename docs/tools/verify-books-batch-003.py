"""Read-only final API verification for the third import batch; refresh local reports."""
import importlib.util
import json
import os
import urllib.parse
from collections import Counter
from pathlib import Path

spec=importlib.util.spec_from_file_location('batch',Path(__file__).with_name('import-books-batch-003.py'))
batch=importlib.util.module_from_spec(spec);spec.loader.exec_module(batch)
b=batch.b
b.token=b.api('/auth/login',{'username':os.environ.get('NOVEL_ADMIN_USER','admin'),
                            'password':os.environ.get('NOVEL_ADMIN_PASSWORD','admin123')})['token']
try:
    records=[r for r in b.records if r.get('batch')==3 and r.get('status')=='COMPLETED' and r.get('readerVerified')]
    plan=json.loads(batch.PLAN.read_text('utf-8'))
    assert len(records)==300
    ids=[str(r['novelId']) for r in records]
    assert len(set(ids))==300 and not set(ids).intersection(str(i) for i in plan['baselineIds'])
    assert len({r['title'] for r in records})==300
    for i,record in enumerate(records,1):
        assert Path(record['file']).is_file() and Path(record['coverFile']).is_file()
        detail=b.api('/novels/'+str(record['novelId']))
        assert detail['wordCount']==record['wordCount']
        assert detail['tags']==record['options']['tags']
        assert record.get('metadataReviewed')
        batch.verify(record)
        if i%25==0: print('VERIFIED '+str(i)+'/300',flush=True)
    counts=Counter(r['category'] for r in records)
    assert set(counts)==set(batch.TARGETS)
    search=[]
    for category in counts:
        record=next(r for r in reversed(records) if r['category']==category)
        result=b.api('/search?'+urllib.parse.urlencode({'keyword':record['title'],'size':100}))
        visible=any(str(r['id'])==str(record['novelId']) for r in result.get('records',[]))
        search.append({'category':category,'title':record['title'],'searchVisible':visible})
        assert visible, 'Search index missing '+record['title']
    batch.plan=plan
    batch.report()
    report=json.loads(batch.REPORT.read_text('utf-8'))
    report.update(finalVerified=True,searchSamples=search,
                  databaseTotal=b.api('/admin/novels?size=1')['total'],
                  readerVisibleTotal=b.api('/novels?size=1')['total'],
                  actualMinWords=min(r['wordCount'] for r in records),
                  actualMaxWords=max(r['wordCount'] for r in records))
    batch.REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    text='# 第三批小说导入\n\n新增 300 本，全部按外部导入来源上架。\n\n'
    text+='实际字数范围：'+str(report['actualMinWords'])+'～'+str(report['actualMaxWords'])+' 字。\n\n'
    text+='| 分类 | 新增数量 |\n| --- | ---: |\n'
    text+=''.join('| '+category+' | '+str(count)+' |\n' for category,count in counts.items())
    text+='\n- 数字子目录：来源网站分组，包含 ZIP、原始 TXT 与整理后的 UTF-8 TXT。\n'
    text+='- `../covers/`：下载的源站封面；系统使用本地上传地址，部分作品使用源站默认封面。\n'
    text+='- `../batch-003-verification.json`：300 本成功清单、分类标签、作者、字数、目录数量和访问核验。\n'
    text+='- `../manifest.json`：历次导入以及跳过、失败的完整记录。跳过和失败的候选不计入这 300 本。\n'
    text+='\n已逐本检查读者详情、目录、首末章和本地封面，并抽查每类一部的站内搜索。旧书未删除。\n'
    (b.ROOT/'batch-003'/'README.md').write_text(text,'utf-8')
    print(json.dumps({k:report[k] for k in ['completed','categories','databaseTotal','readerVisibleTotal','actualMinWords','actualMaxWords','finalVerified']},ensure_ascii=False),flush=True)
finally:
    b.api('/auth/logout',{})
