package com.novel.modules.agent.bookfinder.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.exception.BusinessException;
import com.novel.modules.agent.bookfinder.BookFinderTypes.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Redis ownership is encoded in keys. Compare-and-delete prevents unlocking another run. */
@Service @RequiredArgsConstructor
public class BookFinderStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private static final Duration TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> QUOTA = new DefaultRedisScript<>(
            "local n=tonumber(redis.call('get',KEYS[1]) or '0'); if n>=tonumber(ARGV[1]) then return -1 end; "
            + "n=redis.call('incr',KEYS[1]); if n==1 then redis.call('expire',KEYS[1],172800) end; return n", Long.class);
    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>(
            "redis.call('set',KEYS[1],ARGV[1],'EX',86400); redis.call('set',KEYS[2],ARGV[2],'EX',86400); return 1",Long.class);
    private String conversationKey(long uid, String id) { return "novel:book-finder:session:"+uid+":"+id; }
    private String requestKey(long uid, String id) { return "novel:book-finder:request:"+uid+":"+id; }
    private String lockKey(long uid) { return "novel:book-finder:lock:"+uid; }

    public void lock(long uid, String token) {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey(uid), token, Duration.ofSeconds(90))))
            throw new BusinessException(409, "找书请求正在执行，请稍后再试");
    }
    public void unlock(long uid, String token) { redis.execute(RELEASE, List.of(lockKey(uid)), token); }
    public Conversation load(long uid, String id) {
        if (id == null) { var c = new Conversation(); c.setId(UUID.randomUUID().toString()); return c; }
        String raw = redis.opsForValue().get(conversationKey(uid,id));
        if (raw == null) throw new BusinessException(404, "会话不存在或已过期，请开始新对话");
        return read(raw, Conversation.class);
    }
    public void save(long uid, Conversation c) { redis.opsForValue().set(conversationKey(uid,c.getId()), write(c), TTL); }
    public void complete(long uid,String requestId,String fingerprint,Conversation state,Answer answer) {
        redis.execute(COMPLETE,List.of(conversationKey(uid,state.getId()),requestKey(uid,requestId)),
                write(state),write(new RequestRecord(fingerprint,"done",answer)));
    }
    public void delete(long uid, String id) { load(uid,id); redis.delete(conversationKey(uid,id)); }
    public RequestRecord request(long uid, String id) {
        String raw = redis.opsForValue().get(requestKey(uid,id));
        return raw == null ? null : read(raw, RequestRecord.class);
    }
    public void request(long uid, String id, RequestRecord record) { redis.opsForValue().set(requestKey(uid,id),write(record),TTL); }
    public void acquireQuota(long uid, int limit) {
        Long n = redis.execute(QUOTA, List.of(quotaKey(uid)), Integer.toString(Math.max(0,limit)));
        if (n == null || n < 0) throw new BusinessException(429, "今日找书助手额度已用完");
    }
    public void refundQuota(long uid) { redis.opsForValue().decrement(quotaKey(uid)); }
    private String quotaKey(long uid) { return "novel:book-finder:quota:"+uid+":"+LocalDate.now(); }
    public Map<String,Object> quota(long uid, int limit) {
        String raw=redis.opsForValue().get(quotaKey(uid));
        int used=raw==null?0:Integer.parseInt(raw);
        return Map.of("used",used,"limit",limit,"remaining",Math.max(0,limit-used));
    }
    public void runLog(long uid, String requestId, Object value) {
        String key="novel:book-finder:run:"+uid+":"+requestId;
        redis.opsForValue().set(key,write(value),Duration.ofDays(7));
        redis.opsForList().leftPush("novel:book-finder:runs",key);
        redis.opsForList().trim("novel:book-finder:runs",0,199);
        redis.expire("novel:book-finder:runs",Duration.ofDays(7));
    }
    public Object run(long uid,String requestId) {
        String raw=redis.opsForValue().get("novel:book-finder:run:"+uid+":"+requestId);
        return raw==null?null:read(raw,Object.class);
    }
    public List<Map<String,Object>> recentRuns() {
        var keys=redis.opsForList().range("novel:book-finder:runs",0,99);
        if(keys==null || keys.isEmpty()) return List.of();
        var result=new ArrayList<Map<String,Object>>();
        for(var key:new LinkedHashSet<>(keys)) {
            String raw=redis.opsForValue().get(key);
            if(raw!=null) {
                var row=new LinkedHashMap<String,Object>();
                row.put("request",key.substring("novel:book-finder:run:".length()));
                row.put("record",read(raw,Object.class)); result.add(row);
            }
        }
        return result;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("会话序列化失败",e); }
    }
    private <T> T read(String raw,Class<T> type) {
        try { return json.readValue(raw,type); } catch(Exception e) { throw new IllegalStateException("会话读取失败",e); }
    }
}
