"""Back up and replace development demo data; never changes administrator credentials.

Default mode validates inputs and previews counts. --apply performs the authorized reset.
Credentials come from existing development configuration, not command-line arguments.
"""
from collections import Counter
from datetime import datetime, timedelta
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.demo-tools'))
import bcrypt
import pymysql
import redis
import requests
import yaml
from inspect_environment import database_config

DEMO = ROOT / 'docs/demo'
BOOKS = json.loads((DEMO / 'books.json').read_text(encoding='utf-8'))
PASSWORD = 'Demo123456'
GATEWAY = os.environ.get('DEMO_GATEWAY', 'http://localhost:8080')
PRESERVED = {'sys_role', 'sys_menu', 'sys_role_menu', 'ai_config', 'ai_prompt_template', 'ai_sensitive_word'}
EXPECTED = PRESERVED | {'sys_user', 'sys_user_role', 'novel_category', 'novel', 'novel_outline',
                       'chapter', 'chapter_content', 'novel_comment', 'user_shelf', 'read_history',
                       'author_apply', 'audit_log', 'sys_notice', 'ai_usage_log', 'ai_summary', 'ai_audit_result'}


def emit(value):
    print(json.dumps(value, ensure_ascii=True, default=str), flush=True)


def redis_connection():
    cfg = yaml.safe_load((ROOT / 'backend/novel-content/src/main/resources/application-dev.yml').read_text(encoding='utf-8'))
    r = cfg['spring']['data']['redis']
    return redis.Redis(host=r['host'], port=r['port'], password=r.get('password'), db=r.get('database', 0),
                       socket_connect_timeout=10, socket_timeout=10)


def api(method, path, token=None, **kwargs):
    headers = {'Authorization': f'Bearer {token}'} if token else {}
    response = requests.request(method, GATEWAY + path, headers=headers, timeout=30, **kwargs)
    response.raise_for_status()
    payload = response.json()
    if payload.get('code') != 200:
        raise RuntimeError(f'{path}: {payload.get("code")} {payload.get("message")}')
    return payload.get('data')


def login(username, password):
    return api('POST', '/api/auth/login', json={'username':username, 'password':password})['token']


def administrators(db):
    with db.cursor() as c:
        c.execute('SELECT DISTINCT u.* FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id '
                  'JOIN sys_role r ON r.id=ur.role_id WHERE r.role_code="ADMIN" AND r.deleted=0 '
                  'AND u.deleted=0 ORDER BY u.id')
        rows = c.fetchall()
    if not rows:
        raise RuntimeError('No administrator exists; refusing to reset.')
    return rows


def backup_database(db):
    """Consistent InnoDB snapshot plus a data-only SQL restore and JSON snapshot."""
    directory = ROOT / '.backup' / datetime.now(ZoneInfo('Asia/Shanghai')).strftime('demo-%Y%m%d-%H%M%S')
    directory.mkdir(parents=True, exist_ok=False)
    snapshot = {}
    with db.cursor() as c:
        c.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        c.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT')
        c.execute('SHOW TABLES')
        tables = [next(iter(row.values())) for row in c.fetchall()]
        if set(tables) != EXPECTED:
            raise RuntimeError('Unexpected database tables; inspect before resetting: ' + str(set(tables) ^ EXPECTED))
        for table in tables:
            c.execute(f'SELECT * FROM `{table}`')
            snapshot[table] = c.fetchall()
        with (directory / 'restore.sql').open('w', encoding='utf-8', newline='\n') as f:
            f.write('-- Data restore for the existing novel schema. Contains private account/configuration data.\n')
            f.write('USE `novel`;\nSET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\nSTART TRANSACTION;\n')
            for table in reversed(tables):
                f.write(f'DELETE FROM `{table}`;\n')
            for table, rows in snapshot.items():
                if rows:
                    columns = list(rows[0])
                    for row in rows:
                        values = ','.join(db.escape(row[col]) for col in columns)
                        f.write(f'INSERT INTO `{table}` (' + ','.join(f'`{col}`' for col in columns) + f') VALUES ({values});\n')
            f.write('COMMIT;\nSET FOREIGN_KEY_CHECKS=1;\n')
    db.commit()
    blob = json.dumps(snapshot, ensure_ascii=False, default=str, indent=2).encode('utf-8')
    (directory / 'snapshot.json').write_bytes(blob)
    (directory / 'manifest.json').write_text(json.dumps({'database':'novel', 'counts':{k:len(v) for k,v in snapshot.items()},
        'snapshot_sha256':hashlib.sha256(blob).hexdigest(),
        'restore_sha256':hashlib.sha256((directory / 'restore.sql').read_bytes()).hexdigest()}, indent=2), encoding='utf-8')
    # Make sure the written snapshot can actually be read before any deletion.
    assert json.loads((directory / 'snapshot.json').read_text(encoding='utf-8')).keys() == snapshot.keys()
    return directory, snapshot


def insert(db, table, row):
    columns = list(row)
    with db.cursor() as c:
        c.execute(f'INSERT INTO `{table}` (' + ','.join(f'`{col}`' for col in columns) + ') VALUES (' +
                  ','.join(['%s'] * len(columns)) + ')', [row[col] for col in columns])


def story(book, phase, finished):
    h, p, place, obj = (book[k] for k in ('hero', 'partner', 'setting', 'object'))
    category_details = {
        '玄幻': ('云层被一道金线划开，檐下的铜铃没有风也轻轻响了起来。', '远处的山门仍在云雾里，石阶上残留着昨夜的一场雨。'),
        '科幻': ('观测屏幕上的曲线忽然偏离了基准，设备却没有发出故障提示。', '通讯频道传来短暂的杂音，窗外的微光照亮了沉默的仪表。'),
        '历史': ('街头早市刚刚开张，推车的木轮碾过石板，留下潮湿的声响。', '茶棚的老板记得那位过客，却说不出对方为何没有留下姓名。'),
        '都市': ('早点摊的蒸汽慢慢升起，街角的公交车停了又开，生活照常继续。', '门前的旧告示被风卷起一角，熟悉的街道忽然显出陌生的细节。'),
        '悬疑': ('走廊里的灯闪了两次，记录本上有一行日期被人悄悄改动过。', '那份记录没有缺页，真正不对劲的是同一个人的两种笔迹。'),
        '武侠': ('风从渡口带来湿润的寒意，远处有人把斗笠压低，缓缓牵马而行。', '茶碗的边沿留着一道浅痕，来人并没有带着传闻里的兵器。'),
        '言情': ('窗边的光落在杯沿上，街树的影子缓缓移动，一切显得柔软而安静。', '约定的时间已经过去，两个人却都没有提起那句准备好的开场白。'),
    }
    atmosphere, detail = category_details[book['category']]
    parts = [
        ('意外的开端', [f'{h}第一次注意到{obj}，是在{place}最寻常的一个早晨。{atmosphere}',
         f'起初，那只是一件可以随手放到一边的小事。可当{h}重新检查时，细节已经与记忆不同。没有人催促，也没有人解释，疑问却在心里慢慢停了下来。',
         f'{p}走过来，听完事情的经过，并没有立刻下结论。“先把知道的写下来，再把猜测放在旁边。”这句话让原本混乱的线索有了顺序。',
         f'他们一起查看了{obj}，发现一处此前没有留意的痕迹。它并不显眼，却像是一种耐心的提醒，指向{place}里被大家忽略的角落。',
         f'{detail}过去被当成巧合的事，如今重新连在一起。{h}意识到，如果今天装作没有看见，明天也许就再没有追问的机会。',
         '出发前，两个人约好先求证，后判断。门外的光比刚才亮了一些，一段尚不知道结果的旅程，就这样从平常的一天开始。']),
        ('循着线索', [f'{h}与{p}沿着第一条线索重新走访{place}。{atmosphere}',
         f'不同的人讲着不同版本的往事，有些细节彼此矛盾，有些记忆又惊人地一致。{h}没有急着选择相信谁，而是把每一句话按时间重新排列。',
         f'一位年长的知情人认出了{obj}，却迟迟没有接过它。过了很久，对方才说，那件事并非无人记得，只是大家都以为已经有人替它作出了结论。',
         f'{p}问起当年的另一位见证者。屋里忽然安静下来，只有窗外细碎的声音。那个被略过的名字，终于第一次出现在两个人的记录里。',
         f'{detail}{h}回头检查记录，发现最重要的不是哪句话有多动听，而是哪一个具体细节能够被再次核实。',
         '他们离开时带走了一个新地址。线索没有立即变成答案，却让看似封闭的事情出现了一条可以继续走下去的路。']),
        ('另一种解释', [f'{h}在安静的午后重新整理{obj}的来历。{atmosphere}',
         '先前的推测看起来十分完整，却有一个小小的时间差无法解释。如果只把不合适的部分删掉，故事会很顺畅，事实却会被留在故事之外。',
         f'{p}拿来了新的记录，指出那处矛盾并不是疏漏，而是有人刻意留下的提示。两个人第一次意识到，他们追问的事情可能比想象中更复杂。',
         f'为了避免误会，{h}决定再次联系最初的知情人。这一次不问对方认为什么，只问他亲眼看到了什么。答案短了很多，却也清楚了很多。',
         f'{detail}重新排列之后，原本被认为无关的一件小事落到了关键的位置。那些普通人的迟疑，也终于有了可以理解的理由。',
         '傍晚时，他们划掉了记录中的一个旧判断。在旁人看来这像是退了一步，可他们知道，承认错误也能够让人更接近真正的答案。']),
        ('暂时的阻碍', [f'新的线索把{h}带回{place}，事情却没有按计划进行。{atmosphere}',
         '本来约好的见面临时取消，能够查证的记录也缺少最重要的一页。等候的人群散去之后，那个看起来稳妥的计划只剩下几行没有完成的清单。',
         f'{p}没有责怪任何人。他们坐下来，把已经证实的事实与尚未找到的证据分开。困难依然存在，但它终于不再是一团无法描述的迷雾。',
         f'{h}承认自己有些急切。自从发现{obj}以来，心里的疑问一直催着他向前走，可事情牵涉到的每一个人，都有自己的处境和顾虑。',
         f'{detail}他们决定换一种更耐心的办法：先做能够完成的小事，把答应过别人的工作认真做好，再为下一步争取可信的帮助。',
         '那天没有得到新的结论。离开之前，却有人主动追了出来，交给他们一份被保存多年的副本。真正的转机来得很轻，几乎没有声音。']),
        ('被保存的往事', [f'{h}和{p}终于打开了那份保存多年的副本。{atmosphere}',
         f'上面的文字并不华丽，记录的也只是普通人的日常。可与{obj}上的痕迹逐一对照后，那些被分散讲述的片段第一次组成了完整的前后经过。',
         '事情没有传闻里那么简单。有的人因为胆怯沉默，有的人因为误解离开，也有人一直在做补救，只是没有把自己的努力写进公开的记录。',
         f'{p}建议把副本交给相关的人一起确认。{h}点了点头：一个答案如果只能依靠某个人的坚持存在，还不足以让所有人安心。',
         f'{detail}他们为每一条结论标注来源，也为仍然无法确定的部分留下空白。那些空白提醒着两个人，诚实比一个完美的说法更重要。',
         '夜色降临时，桌上的记录已经与昨天完全不同。下一步不再是寻找谁赢谁输，而是让被遗漏的人也有机会把自己的话说完。']),
        ('必须作出的选择', [f'{h}带着新的记录站在{place}门前，终于到了作出选择的时候。{atmosphere}',
         f'如果把{obj}交出去，许多旧事都会被重新提起；如果继续保留它，眼前的平静也许还可以维持。每一种选择，都有无法回避的责任。',
         f'{p}没有替他决定，只提醒他想一想最初为什么出发。两个人并不是为了证明自己比别人聪明，而是希望具体的问题能够得到具体的回应。',
         f'{h}把记录按顺序装好，逐一联系愿意参与确认的人。他第一次认真说明哪些已经得到证实，哪些只是可能，哪些还需要更多时间。',
         f'{detail}有人表示理解，也有人仍然怀疑。{h}没有试图在一次谈话里说服所有人，只请他们先看证据，再为自己的判断负责。',
         '门被缓缓推开。那一步并没有想象中轰轰烈烈，却让等待许久的事情开始向前移动，也让两个人明白，勇气常常是愿意承担后续的过程。']),
        ('共同完成的事', [f'{place}的清晨比往日忙碌，{h}与{p}分头完成约好的工作。{atmosphere}',
         '有人整理旧记录，有人联系缺席的见证者，也有人耐心核对每一处细节。进展依旧不快，却不再依靠两个人独自支撑。每一项小事都有人接手。',
         f'{h}重新看了一遍{obj}。它不再只是带来疑问的物件，也成为许多人共同付出时间的见证。真正改变事情的，始终是愿意继续行动的人。',
         f'{p}把最后一份确认结果放到桌上，说今天已经可以完成第一阶段。他们没有庆祝得太早，只先把仍有争议的地方记下，安排下一次核对。',
         f'{detail}从前被遮住的联系逐渐清楚，过去无法开口的人也开始讲述。事情终于有了能够被其他人重新检查的依据。',
         '夕光落进屋里时，大家把整理好的结果一份份收起。没人宣布自己成了英雄，但每个人都知道，今天确实完成了一件值得认真对待的事。']),
        ('新的起点', [f'{h}再一次经过{place}，脚步比初来时慢了一些。{atmosphere}',
         f'关于{obj}的记录已经整理妥当，相关的人也都收到了可以查证的说明。曾经让人不安的疑问，有了明确的来处和能够继续追问的方向。',
         f'{p}在门口等他，手里拿着两份新抄好的清单。两个人说起这段经历，没有夸大其中的艰难，只认真记下哪些办法有用，哪些事情还可以做得更好。',
         f'{detail}那些普通的声音仍在，生活并没有因为一个答案突然变得完美。但他们已经学会，在需要的时候停下来，倾听并核实。',
         ('最后一处误会得到解释，曾经分开的道路也重新连接。故事里的每个人都找到了可以继续生活的位置，不必再把自己困在旧日的错误判断里。' if finished else
          '就在两个人准备收起清单时，一位陌生的访客敲响了门。他带来的线索与这件事相连，却指向更远的地方，也留下一个尚待回答的问题。'),
         ('这一次，他们把那件旧物安静地留在了该在的位置。门外的世界依然辽阔，已经完成的故事成为彼此的力量，下一段生活将由他们自己认真书写。' if finished else
          f'{h}与{p}相视片刻，给清单添上了新的一行。他们没有立刻出发，却已经知道，下一次的旅程仍要从一个真实而具体的问题开始。')])
    ]
    title, paragraphs = parts[phase]
    return title, '\n\n'.join(paragraphs)


def make_rows(snapshot, admin_id, covers):
    """Stable relationships, short original chapters, and internally consistent counters."""
    now = datetime.now(ZoneInfo('Asia/Shanghai')).replace(tzinfo=None, microsecond=0)
    tables = {table: [] for table in EXPECTED - PRESERVED - {'sys_user_role'}}
    tables['sys_user_role'] = []
    maxima = {table:max((row.get('id', 0) for row in rows), default=0) for table,rows in snapshot.items()}
    counters = {table:maximum + 1000 for table,maximum in maxima.items()}
    def add(table, **row):
        if table != 'chapter_content':
            counters[table] = counters.get(table, 1000) + 1
            row['id'] = counters[table]
        tables[table].append(row)
        return row.get('id', row.get('chapter_id'))
    roles = {row['role_code']:row['id'] for row in snapshot['sys_role'] if not row['deleted']}
    reader_names = ['山间听雨','橘子汽水','书海拾贝','晚星','小满','木棉','北岸','一页清风','浅墨','晨读时光']
    authors = [book['author'] for book in BOOKS[:10]]
    reader_ids, author_ids = [], []
    for kind,names,ids in [('reader',reader_names,reader_ids),('author',authors,author_ids)]:
        for index,name in enumerate(names,1):
            uid = add('sys_user', username=f'{kind}{index:02}', password=bcrypt.hashpw(PASSWORD.encode(),bcrypt.gensalt(rounds=10,prefix=b'2a')).decode(),
                      nickname=name, email=f'{kind}{index:02}@example.com', status=1, deleted=0,
                      create_time=now-timedelta(days=40-index), update_time=now-timedelta(days=index%7))
            ids.append(uid)
            add('sys_user_role',user_id=uid,role_id=roles['READER' if kind=='reader' else 'AUTHOR'],create_time=now-timedelta(days=40-index))
    categories = {}
    for i,name in enumerate(dict.fromkeys(book['category'] for book in BOOKS),1):
        categories[name] = add('novel_category',name=name,sort=i*10,status=1,deleted=0,create_time=now-timedelta(days=45),update_time=now)
    published, novel_rows, all_chapters, pending_chapters = [], {}, {}, []
    for book in BOOKS:
        number = book['number']; aid = author_ids[(number-1)%10]
        status = book.get('status',2); finished = status==2 and number in (3,4,6,7,9,10,13,14)
        created = now-timedelta(days=32-number//2)
        last = now-timedelta(hours=(number*3)%48)
        nid = add('novel',title=book['title'],cover=covers[str(number)],author_id=aid,category_id=categories[book['category']],
                  intro=book['intro'],tags=book['tags'],status=status,audit_remark=('作品简介需要补充核心冲突与人物动机，请完善后重新提交。' if status==4 else None),
                  is_finish=int(finished),word_count=0,chapter_count=0,view_count=(number*839+1275 if status in (2,3) else 0),
                  collect_count=0,score=Decimal('0.0'),last_chapter_id=None,last_update_time=last if status in (2,3) else None,
                  create_time=created,update_time=now-timedelta(hours=number%5),deleted=0)
        novel_rows[nid] = tables['novel'][-1]
        if status==2: published.append(nid)
        chapter_total = 5+(number%4)
        phase_sequences = {5:[0,1,3,5,7],6:[0,1,2,4,5,7],7:[0,1,2,3,4,5,7],8:list(range(8))}
        chapter_ids = []
        for index,phase in enumerate(phase_sequences[chapter_total],1):
            chapter_title, content = story(book,phase,finished)
            if status in (2,3):
                cs = 2 if finished or index<=chapter_total-2 else (1 if index==chapter_total-1 else 0)
            elif status==1: cs = 1 if index<=2 else 0
            elif status==4: cs = 4 if index==1 else 0
            else: cs=0
            stamp = last-timedelta(days=chapter_total-index)
            wc=len(re.sub(r'\s','',content))
            cid = add('chapter',novel_id=nid,chapter_no=index,title=chapter_title,word_count=wc,status=cs,
                      audit_remark=('请补充章节开场与前文的衔接。' if cs==4 else None),is_free=1,
                      view_count=(max(0,novel_rows[nid]['view_count']//(index+1)-index*11) if cs==2 else 0),
                      publish_time=stamp if cs==2 else None,create_time=stamp-timedelta(hours=4),update_time=stamp,deleted=0)
            add('chapter_content',chapter_id=cid,content=content,update_time=stamp)
            chapter_ids.append((cid,cs,index,chapter_title,stamp,wc))
            if cs==1: pending_chapters.append((cid,nid,book))
            if cs!=0:
                add('audit_log',target_type=2,target_id=cid,action=1,operator_id=aid,remark='提交章节审核',create_time=stamp-timedelta(hours=2))
            if cs in (2,4):
                add('audit_log',target_type=2,target_id=cid,action=2 if cs==2 else 3,operator_id=admin_id,
                    remark='章节内容通过审核' if cs==2 else '请补充章节开场与前文的衔接。',create_time=stamp-timedelta(hours=1))
        all_chapters[nid]=chapter_ids
        public=[chapter for chapter in chapter_ids if chapter[1]==2]
        novel_rows[nid].update(chapter_count=len(public),word_count=sum(chapter[5] for chapter in public),last_chapter_id=public[-1][0] if public else None,
                              last_update_time=public[-1][4] if public else None)
        root = add('novel_outline',novel_id=nid,parent_id=None,node_type=1,title='全书大纲',content=book['intro'],chapter_id=None,status=2 if finished else 1,sort=0,create_time=created,update_time=last,deleted=0)
        volumes=[]
        for vi,vt in enumerate(['第一卷：线索与相遇','第二卷：选择与回响'],1):
            volumes.append(add('novel_outline',novel_id=nid,parent_id=root,node_type=2,title=vt,
                content=f'{book["hero"]}与{book["partner"]}围绕{book["object"]}展开调查，逐渐理解{book["setting"]}的往事。',
                chapter_id=None,status=2 if finished else vi%2,sort=vi,create_time=created,update_time=last,deleted=0))
        for cid,cs,index,ct,stamp,wc in chapter_ids:
            node=add('novel_outline',novel_id=nid,parent_id=volumes[0 if index<=(chapter_total+1)//2 else 1],node_type=3,title=f'第{index}章：{ct}',
                content=f'本章围绕“{ct}”推进。人物目标：核实{book["object"]}的线索；场景：{book["setting"]}；情节结束时保留下一步行动方向。',
                chapter_id=cid,status=2 if cs==2 else 1 if cs==1 else 0,sort=index,create_time=stamp,update_time=stamp,deleted=0)
            if index in (1,3):
                add('novel_outline',novel_id=nid,parent_id=node,node_type=4,title='人物动机与伏笔',content=f'{book["partner"]}补充一个可核实的细节，让{book["hero"]}修正原先的判断。',
                    chapter_id=None,status=2 if cs==2 else 0,sort=1,create_time=stamp,update_time=stamp,deleted=0)
        if status!=0:
            add('audit_log',target_type=1,target_id=nid,action=1,operator_id=aid,remark='提交作品审核',create_time=created+timedelta(hours=1))
        if status in (2,3,4):
            add('audit_log',target_type=1,target_id=nid,action=3 if status==4 else 2,operator_id=admin_id,
                remark=novel_rows[nid]['audit_remark'] if status==4 else '作品信息完整，准予上架',create_time=created+timedelta(hours=3))
        if status==3:
            add('audit_log',target_type=1,target_id=nid,action=4,operator_id=admin_id,remark='演示下架流程：待作者补充版权与创作说明。',create_time=now-timedelta(days=1))

    # Every reader gets several books and resumable progress; reader01 also has an offline book.
    offline=next(n['id'] for n in tables['novel'] if n['status']==3)
    for index,uid in enumerate(reader_ids):
        chosen=[published[(index*2+step*3)%len(published)] for step in range(5)]
        for j,nid in enumerate(chosen):
            add('user_shelf',user_id=uid,novel_id=nid,create_time=now-timedelta(days=12-index,hours=j))
            public=[ch for ch in all_chapters[nid] if ch[1]==2]
            ch=public[(index+j)%len(public)]
            add('read_history',user_id=uid,novel_id=nid,chapter_id=ch[0],progress=(index*13+j*19)%101,
                update_time=now-timedelta(hours=index+j*4,minutes=j*7))
        if index==0:
            add('user_shelf',user_id=uid,novel_id=offline,create_time=now-timedelta(days=14))
            add('read_history',user_id=uid,novel_id=offline,chapter_id=all_chapters[offline][0][0],progress=65,update_time=now-timedelta(days=2))
    # Authors can also use reader functions, which makes the author/reader identity switch demonstrable.
    for index,uid in enumerate(author_ids):
        nid=published[(index+3)%len(published)]
        add('user_shelf',user_id=uid,novel_id=nid,create_time=now-timedelta(days=3))
        add('read_history',user_id=uid,novel_id=nid,chapter_id=all_chapters[nid][0][0],progress=32,update_time=now-timedelta(hours=index+2))

    comments = ['开篇的线索埋得很自然，人物做事的理由也能看明白。期待后面继续展开。',
                '喜欢这种有生活细节的写法，场景描写很有画面感。',
                '人物之间的对话比直接解释更有说服力，读起来比较顺。',
                '转折有铺垫，重新看第一章还能发现之前忽略的细节。',
                '整体节奏舒服，如果关键选择再写得具体一点，会更有代入感。']
    likes = {}
    for index,nid in enumerate(published):
        for j,text in enumerate(comments):
            uid=reader_ids[(index+j)%10];stamp=now-timedelta(days=5-j,hours=index)
            cid=add('novel_comment',user_id=uid,target_type=1,target_id=nid,parent_id=None,content=text,
                score=3 if j==4 and index%3==0 else 4 if (index+j)%3==0 else 5,like_count=0,status=1,
                audit_user_id=admin_id,audit_time=stamp+timedelta(minutes=20),audit_remark=None,create_time=stamp,deleted=0)
            members=[r for r in reader_ids if r!=uid][:1+(index+j)%4]
            likes[cid]=members;tables['novel_comment'][-1]['like_count']=len(members)
            if j==0:
                reply=add('novel_comment',user_id=novel_rows[nid]['author_id'],target_type=1,target_id=nid,parent_id=cid,
                    content='谢谢认真阅读！后续会继续补足人物行动的细节，也欢迎大家交流。',score=None,like_count=2,status=1,
                    audit_user_id=admin_id,audit_time=stamp+timedelta(hours=3),audit_remark=None,create_time=stamp+timedelta(hours=2),deleted=0)
                likes[reply]=reader_ids[:2]
        for j,ch in enumerate([ch for ch in all_chapters[nid] if ch[1]==2][:2]):
            cid=add('novel_comment',user_id=reader_ids[(index+j+5)%10],target_type=2,target_id=ch[0],parent_id=None,
                content='这一章的细节与后面的线索联系起来了，回头读很有意思。' if j==0 else '喜欢两个人先核实再判断的对话，人物性格立起来了。',
                score=None,like_count=1,status=1,audit_user_id=admin_id,audit_time=now-timedelta(hours=index+2),audit_remark=None,
                create_time=now-timedelta(hours=index+3),deleted=0)
            likes[cid]=[reader_ids[(index+8)%10]]
    for i in range(8):
        pending=i<5; nid=published[i%len(published)];stamp=now-timedelta(hours=i+1)
        cid=add('novel_comment',user_id=reader_ids[i],target_type=1,target_id=nid,parent_id=None,
            content='新章节读完了，期待下一次更新。' if pending else '[演示审核样例] 请加入广告交流群获取站外资源。',
            score=4 if pending else None,like_count=0,status=0 if pending else 2,
            audit_user_id=None if pending else admin_id,audit_time=None if pending else stamp+timedelta(minutes=20),
            audit_remark=None if pending else '包含引流广告，未予展示。',create_time=stamp,deleted=0)
        if not pending:
            add('audit_log',target_type=3,target_id=cid,action=3,operator_id=admin_id,remark='包含引流广告，未予展示。',create_time=stamp+timedelta(minutes=20))
    for n in tables['novel']:
        n['collect_count']=sum(1 for shelf in tables['user_shelf'] if shelf['novel_id']==n['id'])
        scores=[c['score'] for c in tables['novel_comment'] if c['target_type']==1 and c['target_id']==n['id'] and c['parent_id'] is None and c['status']==1 and c['score'] is not None]
        n['score']= (Decimal(sum(scores))/len(scores)).quantize(Decimal('0.1')) if scores else Decimal('0.0')
    for i,uid in enumerate(author_ids):
        stamp=now-timedelta(days=35-i)
        aid=add('author_apply',user_id=uid,pen_name=authors[i],intro=f'希望创作{BOOKS[i]["category"]}题材，已有作品构思和章节计划。',status=1,
            audit_user_id=admin_id,audit_time=stamp+timedelta(hours=3),audit_remark='创作计划完整，申请通过。',create_time=stamp,update_time=stamp+timedelta(hours=3),deleted=0)
        add('audit_log',target_type=4,target_id=aid,action=2,operator_id=admin_id,remark='创作计划完整，申请通过。',create_time=stamp+timedelta(hours=3))
    for i in range(5):
        rejected=i>=3;stamp=now-timedelta(days=i+1)
        aid=add('author_apply',user_id=reader_ids[i],pen_name=['望舒','小鹿','海棠','林间风','纸上远行'][i],
            intro='准备创作一部围绕城市生活与人物成长的小说，希望获得作者资格。',status=2 if rejected else 0,
            audit_user_id=admin_id if rejected else None,audit_time=stamp+timedelta(hours=2) if rejected else None,
            audit_remark='请补充原创作品简介与写作计划后重新申请。' if rejected else None,create_time=stamp,update_time=stamp,deleted=0)
        if rejected: add('audit_log',target_type=4,target_id=aid,action=3,operator_id=admin_id,remark='请补充原创作品简介与写作计划后重新申请。',create_time=stamp+timedelta(hours=2))
    notices=[('欢迎来到小说阅读平台','平台提供分类找书、排行榜、书架收藏、阅读进度与书评交流。欢迎从首页挑选一本喜欢的作品。',1,1),
             ('秋日阅读推荐','本期推荐《九霄问道》《雨巷邮局》《星河拾荒者》，探索不同题材里的世界与人物。',1,1),
             ('作者创作指南','请先完善书名、封面、分类和简介，再提交作品审核。可用大纲树规划章节，保存草稿后继续编辑。',2,1),
             ('评论区交流约定','欢迎讨论情节和写作，请尊重不同观点。涉及广告引流或与作品无关的内容将被审核处理。',2,1),
             ('系统维护提醒','计划维护时间将提前通知，阅读进度与书架数据会正常保存。',2,1),
             ('下一期征文活动草案','演示未发布通知：活动细则仍在准备中，管理员可编辑后发布。',1,0)]
    for i,(title,content,nt,status) in enumerate(notices):
        add('sys_notice',title=title,content=content,type=nt,status=status,publish_time=now-timedelta(days=i) if status else None,
            create_time=now-timedelta(days=i+2),update_time=now-timedelta(hours=i),deleted=0)
    # Historical AI rows are explicitly fixtures; seeding does not call a paid model.
    for i,uid in enumerate(author_ids):
        nid=tables['novel'][i]['id'];first=all_chapters[nid][0][0]
        for scene in range(1,7):
            stamp=now-timedelta(days=1+i%7,hours=scene)
            success=not (i%4==0 and scene==2)
            pt=140+i*17+scene*13;ct=210+i*11+scene*7
            add('ai_usage_log',user_id=uid,scene=scene,novel_id=nid,chapter_id=first,model='demo-fixture',prompt_tokens=pt,completion_tokens=ct if success else 0,
                total_tokens=pt+(ct if success else 0),duration_ms=920+i*73+scene*110,success=int(success),
                error_msg=None if success else '[演示数据] 请求超时，请稍后重试。',create_time=stamp)
        add('ai_summary',novel_id=nid,start_chapter_no=1,end_chapter_no=3,
            summary=f'[演示摘要] {BOOKS[i]["hero"]}在{BOOKS[i]["setting"]}发现{BOOKS[i]["object"]}的异常，与{BOOKS[i]["partner"]}核对记录、走访知情人。关键线索指向被遗漏的旧事，二人决定以可验证的证据推进调查。',
            create_time=now-timedelta(days=1))
    for i,(cid,nid,book) in enumerate(pending_chapters[:8]):
        add('ai_audit_result',target_type=2,target_id=cid,risk_level=i%3,hit_words=None,
            ai_comment='[演示审核结果] ' + ('未发现明显风险，等待管理员复核。' if i%3==0 else '建议人工检查措辞与情节衔接；未自动判定违规。'),
            status=0 if i<5 else 2,audit_user_id=None if i<5 else admin_id,audit_time=None if i<5 else now-timedelta(hours=2),create_time=now-timedelta(hours=3))
    for i,comment in enumerate([c for c in tables['novel_comment'] if c['status']==2]):
        add('ai_audit_result',target_type=3,target_id=comment['id'],risk_level=2,hit_words='广告交流群',
            ai_comment='[演示审核结果] 检测到站外引流信息，管理员已确认。',status=1,audit_user_id=admin_id,audit_time=now-timedelta(hours=1),create_time=now-timedelta(hours=2))
    return tables, likes


def validate(db, admin_snapshot=None):
    with db.cursor() as c:
        c.execute('SELECT COUNT(*) n FROM sys_user WHERE deleted=0')
        assert c.fetchone()['n']==21
        c.execute('SELECT role_code,COUNT(*) n FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id GROUP BY role_code')
        assert {row['role_code']:row['n'] for row in c.fetchall()}=={'ADMIN':1,'AUTHOR':10,'READER':10}
        c.execute('SELECT COUNT(*) n, SUM(cover IS NOT NULL AND cover<>"") covers FROM novel')
        assert c.fetchone()=={'n':20,'covers':Decimal(20)}
        c.execute('SELECT novel_id,COUNT(*) n FROM chapter GROUP BY novel_id')
        assert all(5<=row['n']<=8 for row in c.fetchall())
        c.execute('SELECT COUNT(*) n FROM chapter c LEFT JOIN chapter_content cc ON cc.chapter_id=c.id WHERE cc.chapter_id IS NULL')
        assert c.fetchone()['n']==0
        for table,column,target in [('novel','author_id','sys_user'),('novel','category_id','novel_category'),('chapter','novel_id','novel'),
            ('novel_outline','novel_id','novel'),('novel_comment','user_id','sys_user'),('user_shelf','user_id','sys_user'),
            ('user_shelf','novel_id','novel'),('read_history','user_id','sys_user'),('read_history','novel_id','novel'),('author_apply','user_id','sys_user'),
            ('ai_usage_log','user_id','sys_user'),('audit_log','operator_id','sys_user')]:
            c.execute(f'SELECT COUNT(*) n FROM `{table}` t LEFT JOIN `{target}` p ON p.id=t.`{column}` WHERE p.id IS NULL')
            assert c.fetchone()['n']==0,(table,column)
        c.execute('SELECT COUNT(*) n FROM read_history h JOIN chapter c ON c.id=h.chapter_id WHERE c.novel_id<>h.novel_id OR c.status<>2')
        assert c.fetchone()['n']==0
        c.execute('SELECT n.id,n.chapter_count,n.word_count,n.collect_count,n.last_chapter_id,'
                  '(SELECT COUNT(*) FROM chapter c WHERE c.novel_id=n.id AND c.status=2) actual_chapters,'
                  '(SELECT COALESCE(SUM(word_count),0) FROM chapter c WHERE c.novel_id=n.id AND c.status=2) actual_words,'
                  '(SELECT COUNT(*) FROM user_shelf s WHERE s.novel_id=n.id) actual_collects FROM novel n')
        for row in c.fetchall():
            assert row['chapter_count']==row['actual_chapters'] and row['word_count']==row['actual_words'] and row['collect_count']==row['actual_collects'],row
        if admin_snapshot:
            for admin in admin_snapshot:
                c.execute('SELECT * FROM sys_user WHERE id=%s',(admin['id'],))
                assert c.fetchone()==admin,'Administrator account was modified.'


def clear_caches(r, old_users, admin_ids, backup_dir):
    patterns=['novel:novel:*','novel:chapter:*','novel:like:user:*','novel:ai:quota:*','novel:ai:cache:*','novel:comment:limit:*','novel:novel:stat_at:*']
    for user in old_users:
        if user['id'] not in admin_ids:
            patterns.extend([f'novel:auth:token:{user["id"]}:*',f'novel:auth:refresh:{user["id"]}'])
    keys=set()
    for pattern in patterns: keys.update(r.scan_iter(match=pattern,count=200))
    import base64
    saved=[]
    for key in keys:
        dump=r.dump(key)
        if dump is not None: saved.append({'key':base64.b64encode(key).decode(),'ttl_ms':r.pttl(key),'dump':base64.b64encode(dump).decode()})
    (backup_dir/'redis-cache.json').write_text(json.dumps(saved),encoding='utf-8')
    if keys:r.delete(*keys)
    return len(keys)


def main():
    db=pymysql.connect(**database_config())
    admins=administrators(db)
    if len(admins)!=1: raise RuntimeError('This fixture expects one retained administrator; inspect multiple-admin setup first.')
    admin=admins[0]
    with db.cursor() as c:
        c.execute('SHOW TABLES');actual={next(iter(row.values())) for row in c.fetchall()}
        assert actual==EXPECTED
        for role in ('ADMIN','AUTHOR','READER'):
            c.execute('SELECT id FROM sys_role WHERE role_code=%s AND status=1 AND deleted=0',(role,))
            assert c.fetchone(),role
    cover_manifest=DEMO/'cover-urls.json'
    if not cover_manifest.exists():
        if '--apply' in sys.argv:
            raise RuntimeError('Generate and upload all 20 covers before applying the reset.')
        emit({'mode':'preflight','administrators':[{'id':a['id'],'username':a['username']} for a in admins],
              'books':20,'readers':10,'authors':10,'chapter_range':[5,8],'covers':'not_uploaded_yet'})
        db.close();return
    covers=json.loads(cover_manifest.read_text(encoding='utf-8'))
    assert set(covers)=={str(i) for i in range(1,21)}
    for path in covers.values():
        response=requests.get(GATEWAY+path,timeout=10)
        assert response.status_code==200 and response.headers.get('Content-Type','').startswith('image/'),path
    if '--apply' not in sys.argv:
        emit({'mode':'preflight','books':20,'readers':10,'authors':10,'covers_verified':20})
        db.close();return
    r=redis_connection();r.ping()
    # Obtain a real admin session before touching data, and keep its credentials unchanged.
    admin_password=os.environ.get('DEMO_ADMIN_PASSWORD','admin123')
    if not bcrypt.checkpw(admin_password.encode(),admin['password'].encode()):
        raise RuntimeError('Set DEMO_ADMIN_PASSWORD locally to the existing administrator password; it will not be changed.')
    token=login(admin['username'],admin_password)
    db.commit()
    backup_dir,snapshot=backup_database(db)
    emit({'backup':str(backup_dir),'tables_backed_up':len(snapshot)})
    rows,likes=make_rows(snapshot,admin['id'],covers)
    emit({'prepared_counts':{table:len(items) for table,items in rows.items()},'novel_statuses':dict(Counter(n['status'] for n in rows['novel']))})
    try:
        db.begin()
        with db.cursor() as c:
            # DELETE is transactional; do not TRUNCATE or reset AUTO_INCREMENT.
            for table in reversed(list(rows)):
                if table=='sys_user':c.execute('DELETE FROM sys_user WHERE id<>%s',(admin['id'],))
                elif table=='sys_user_role':c.execute('DELETE FROM sys_user_role WHERE user_id<>%s',(admin['id'],))
                else:c.execute(f'DELETE FROM `{table}`')
        for table,items in rows.items():
            for row in items:insert(db,table,row)
        validate(db,admins)
        for table in PRESERVED:
            with db.cursor() as c:
                c.execute(f'SELECT * FROM `{table}` ORDER BY id')
                assert c.fetchall()==sorted(snapshot[table],key=lambda row:row['id']),table
        db.commit()
    except Exception:
        db.rollback();raise
    removed=clear_caches(r,snapshot['sys_user'],{a['id'] for a in admins},backup_dir)
    for comment_id,members in likes.items():r.sadd(f'novel:like:user:{comment_id}',*(str(uid) for uid in members))
    report={'backup':str(backup_dir),'retained_admin':{'id':admin['id'],'username':admin['username']},
            'counts':{table:len(items) for table,items in rows.items()},'cleared_cache_keys':removed,
            'books':[{'id':n['id'],'title':n['title'],'status':n['status'],'cover':n['cover'],'author_id':n['author_id']} for n in rows['novel']],
            'accounts':[{'username':u['username'],'nickname':u['nickname'],'id':u['id']} for u in rows['sys_user']],
            'published_books':15,'chapter_range':[5,8],'demo_password':PASSWORD}
    # Private run report remains ignored; the public demo guide contains only synthetic accounts.
    (backup_dir/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
    (DEMO/'last-run.json').write_text(json.dumps({k:v for k,v in report.items() if k not in ('backup','demo_password')},ensure_ascii=False,indent=2,default=str),encoding='utf-8')
    # Rebuild ES through the application's alias-switching job, not a raw index delete.
    try:
        receipt=api('POST','/api/admin/search/reindex',token=token)
        report['reindex']=receipt
    except Exception as exc:
        report['reindex_error']=str(exc)
    (backup_dir/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
    emit({k:v for k,v in report.items() if k not in ('books','accounts','demo_password')})
    db.close()


if __name__=='__main__':main()
