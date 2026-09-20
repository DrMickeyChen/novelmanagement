/**
 * 连通性检测接口 —— 对应后端的 PingController。
 *
 * 目录约定（§四）：每个业务模块一个文件，novel.js / chapter.js / comment.js ...
 * 组件里不直接写 axios，都从这里走，接口路径只在一处出现。
 */
import request from '@/utils/request'

/** 应用存活。不碰数据库 */
export function ping() {
  return request.get('/ping')
}

/** MySQL 连通性。返回里能看到种子数据里的 admin */
export function pingDb() {
  return request.get('/ping/db')
}

/** Redis 连通性。返回 serializationOk 字段 */
export function pingRedis() {
  return request.get('/ping/redis')
}
