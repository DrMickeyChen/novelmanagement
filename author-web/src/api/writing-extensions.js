import request from '@/utils/request'
export const getAssistantSettings = novel => request.get(`/ai/writing/settings/novels/${novel}`)
export const saveAssistantSettings = (novel, body) => request.put(`/ai/writing/settings/novels/${novel}`, body)
export const updateTaskTools = (id, body) => request.put(`/ai/writing/tasks/${id}/tools`, body)
export const getToolTask = id => request.get(`/ai/writing/tasks/${id}`)
const assets = novel => `/ai/writing/assets/novels/${novel}`
const extensions = novel => `/ai/writing/extensions/novels/${novel}`
const mcp = novel => `/ai/writing/mcp/novels/${novel}`
export const getAssets = novel => request.get(assets(novel))
export const getSkills = novel => request.get(`${assets(novel)}/skills`)
export const saveAsset = (novel, id, body) => id ? request.put(`${assets(novel)}/${id}`, body) : request.post(assets(novel), body)
export const deleteAsset = (novel, row) => request.delete(`${assets(novel)}/${row.id}`, { params: { expectedVersion: row.version } })
export const assetHistory = (novel, id) => request.get(`${assets(novel)}/${id}/history`)
export function importSkill(novel, file, preview = false) { const form = new FormData(); form.append('file', file); return request.post(`${assets(novel)}/import${preview ? '/preview' : ''}`, form) }
export const analyzeSample = (novel, id) => request.post(`${extensions(novel)}/samples/${id}/analyze`, { acceptCost: true })
export const getAnalysis = (novel, id) => request.get(`${extensions(novel)}/jobs/${id}`)
export const getAnalyses = novel => request.get(`${extensions(novel)}/jobs`)
export const getSync = novel => request.get(`${extensions(novel)}/sync`)
export const saveSync = (novel, body) => request.put(`${extensions(novel)}/sync`, body)
export const enqueueSync = novel => request.post(`${extensions(novel)}/sync`)
export const getSyncEvents = novel => request.get(`${extensions(novel)}/events`)
export const retrySync = (novel, id) => request.post(`${extensions(novel)}/events/${id}/retry`)
export const getRecaps = novel => request.get(`${extensions(novel)}/recaps`)
export const getMcp = novel => request.get(mcp(novel))
export const saveMcp = (novel, id, body) => id ? request.put(`${mcp(novel)}/${id}`, body) : request.post(mcp(novel), body)
export const deleteMcp = (novel, id) => request.delete(`${mcp(novel)}/${id}`)
export const discoverMcp = (novel, id) => request.post(`${mcp(novel)}/${id}/discover`,null,{timeout:60000})
export const grantMcp = (novel, id, body) => request.post(`${mcp(novel)}/${id}/grant`, body)
export const revisions = id => request.get(`/ai/writing/tasks/${id}/revisions`)
export const branch = (id, body) => request.post(`/ai/writing/tasks/${id}/branches`, body, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
export const mergePreview = (id, body) => request.post(`/ai/writing/tasks/${id}/merge-preview`, body)
export const parseJson = value => typeof value === 'string' ? JSON.parse(value || '{}') : value || {}
