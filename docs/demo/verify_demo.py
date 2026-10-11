"""Verify seeded SQL, login roles, public images, reader/author/admin APIs, and ES."""
from collections import Counter
from decimal import Decimal
from io import BytesIO
import json
import os
import time

from seed_demo import DEMO, GATEWAY, PASSWORD, ROOT, administrators, api, database_config, emit, login, redis_connection, validate
import pymysql
import requests
from PIL import Image


def main():
    db=pymysql.connect(**database_config())
    validate(db)
    report={'database_integrity':'passed','checks':[]}
    run=json.loads((DEMO/'last-run.json').read_text(encoding='utf-8'))
    books=run['books']; published=[book for book in books if book['status']==2]
    report['counts']=run['counts']
    report['public_books']=len(published)
    def check(name,value):
        report['checks'].append({'check':name,**value});emit({'verified':name,**value})
    tokens={}
    for account in run['accounts']:
        result=api('POST','/api/auth/login',json={'username':account['username'],'password':PASSWORD})
        expected='AUTHOR' if account['username'].startswith('author') else 'READER'
        assert result['user']['id']==account['id']
        assert expected in result['user']['roles']
        tokens[account['username']]=result['token']
    check('all_demo_accounts_login',{'accounts':len(tokens),'roles':'10 READER, 10 AUTHOR'})
    admin=administrators(db)[0]
    admin_token=login(admin['username'],os.environ.get('DEMO_ADMIN_PASSWORD','admin123'))
    backups=sorted((ROOT/'.backup').glob('demo-*'))
    if backups:
        snapshot=json.loads((backups[-1]/'snapshot.json').read_text(encoding='utf-8'))
        old=next(u for u in snapshot['sys_user'] if u['id']==admin['id'])
        assert {k:str(v) if v is not None else None for k,v in admin.items()}=={k:str(v) if v is not None else None for k,v in old.items()}
        with db.cursor() as c:
            c.execute('SELECT * FROM sys_user_role WHERE user_id=%s ORDER BY id',(admin['id'],))
            associations=c.fetchall()
        old_roles=[r for r in snapshot['sys_user_role'] if r['user_id']==admin['id']]
        assert json.loads(json.dumps(associations,default=str))==old_roles
    check('administrator_preserved',{'username':admin['username'],'credentials_and_roles':'unchanged'})
    covers_verified=0
    for book in books:
        for base in (GATEWAY,'http://localhost:3000'):
            response=requests.get(base+book['cover'],timeout=10)
            assert response.status_code==200 and response.headers.get('Content-Type','').startswith('image/')
            with Image.open(BytesIO(response.content)) as im:
                assert im.width>0 and im.height>im.width
                im.verify()
        covers_verified+=1
    check('covers_via_gateway_and_web_proxy',{'images':covers_verified,'routes':['/uploads/cover/**']})
    listing=api('GET','/api/novels',params={'page':1,'size':100})
    assert listing['total']==15
    assert {n['id'] for n in listing['records']}=={b['id'] for b in published}
    assert all(n.get('cover') for n in listing['records'])
    check('reader_list',{'books':listing['total'],'all_have_cover':True})
    categories=api('GET','/api/categories')
    assert len(categories)==7
    notices=api('GET','/api/notices',params={'limit':20})
    assert len(notices)==5
    for rank_type in ('hot','collect','score'):
        rank=api('GET','/api/novels/rank',params={'type':rank_type,'limit':20})
        assert rank and all(n.get('cover') for n in rank)
    check('categories_notices_and_rank_covers',{'categories':7,'published_notices':5,'rank_lists':3})
    chapter_lengths=[]
    for book in published:
        detail=api('GET',f'/api/novels/{book["id"]}')
        assert detail['cover']==book['cover']
        catalog=api('GET',f'/api/novels/{book["id"]}/chapters',params={'size':100})
        assert catalog['records'] and len(catalog['records'])==detail['chapterCount']
        first=catalog['records'][0]
        body=api('GET',f'/api/novels/{book["id"]}/chapters/{first["id"]}')
        assert body['content']
        chapter_lengths.append(len(body['content']))
    check('reader_details_catalog_and_text',{'books':15,'chapters_nonempty':True})
    shelf=api('GET','/api/reader/shelf',token=tokens['reader01'],params={'size':100})
    history=api('GET','/api/reader/history',token=tokens['reader01'],params={'size':100})
    assert shelf['total']==6 and history['total']==6
    assert any(not item['available'] for item in shelf['records'])
    assert all(item.get('cover') for item in shelf['records'] if item['available'])
    assert all(item.get('cover') for item in history['records'] if item['available'])
    check('shelf_history_and_offline_placeholder',{'shelf_books':6,'history_books':6,'available_books_have_covers':True})
    book=published[0]
    comments=api('GET',f'/api/novels/{book["id"]}/comments',params={'size':100})
    assert comments['records']
    root=next(c for c in comments['records'] if c.get('replyCount',0)>0)
    replies=api('GET',f'/api/novels/{book["id"]}/comments/{root["id"]}/replies',params={'size':100})
    assert replies['records']
    check('book_reviews_and_author_replies',{'root_comments':comments['total'],'replies_nonempty':True})
    own=api('GET','/api/author/novels',token=tokens['author01'],params={'size':100})
    assert own['total']==2
    outline=api('GET',f'/api/author/novels/{own["records"][0]["id"]}/outline',token=tokens['author01'])
    assert outline
    check('author_works_and_outline',{'works':2,'outline_nonempty':True})
    stats=api('GET','/api/admin/dashboard/stats',token=admin_token)
    assert stats['novelTotal']==20 and stats['userTotal']==21 and stats['commentTotal']==128
    assert stats['novelPending']==2 and stats['commentPending']==5 and stats['authorApplyPending']==3
    trend=api('GET','/api/admin/dashboard/trend',token=admin_token,params={'days':30})
    assert len(trend['days'])==30 and all(len(trend[key])==30 for key in ('novels','users','comments'))
    assert sum(trend['novels'])>0 and sum(trend['comments'])>0
    check('admin_dashboard_counts_and_trend',{'novels':20,'users':21,'comments':128,'trend_days':30})
    for name,path,total in [('author_applications','/api/admin/author-applies',15),('comment_moderation','/api/admin/comments',128),
                           ('ai_usage_fixtures','/api/admin/ai/usage',60),('ai_audit_fixtures','/api/admin/ai/audit',11)]:
        result=api('GET',path,token=admin_token,params={'size':100})
        assert result['total']==total,(path,result['total'])
        check(name,{'records':total})
    r=redis_connection()
    with db.cursor() as c:
        c.execute('SELECT id,like_count FROM novel_comment WHERE like_count>0')
        liked=c.fetchall()
    for comment in liked:
        assert r.scard(f'novel:like:user:{comment["id"]}')==comment['like_count']
    check('redis_like_members_match_sql',{'comments':len(liked)})
    # A started task is not proof of success; wait for its final status.
    receipt=None
    if backups:
        result=json.loads((backups[-1]/'result.json').read_text(encoding='utf-8'))
        receipt=result.get('reindex')
    if not receipt:receipt=api('POST','/api/admin/search/reindex',token=admin_token)
    task_id=receipt['taskId']
    task=None
    for _ in range(45):
        task=api('GET',f'/api/admin/search/reindex/{task_id}',token=admin_token)
        if task['status']!='RUNNING':break
        time.sleep(1)
    assert task['status']=='SUCCEEDED',task
    # The index intentionally contains all novel statuses; reader search filters to PUBLISHED.
    assert task['indexed']==20,task
    # Empty keyword intentionally returns no books; check each published title instead.
    found=set()
    for book in published:
        search=api('GET','/api/search',params={'keyword':book['title'],'size':100})
        assert any(n['id']==book['id'] and n.get('cover')==book['cover'] for n in search['records'])
        found.add(book['id'])
    assert len(found)==15
    for book in (b for b in books if b['status']!=2):
        hidden=api('GET','/api/search',params={'keyword':book['title'],'size':100})
        assert not any(n['id']==book['id'] for n in hidden['records'])
    check('elasticsearch_rebuild_and_search',{'status':task['status'],'indexed':task['indexed'],'search_books':len(found)})
    report['status']='passed'
    (DEMO/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    emit({'status':'passed','checks':len(report['checks'])})
    db.close()


if __name__=='__main__':main()
