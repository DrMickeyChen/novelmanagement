import request from '@/utils/request'

export function previewTxt(file, encoding, mode) {
  const form = new FormData()
  form.append('file', file)
  form.append('encoding', encoding)
  form.append('mode', mode)
  return request.post('/admin/novels/imports/preview', form, { timeout: 180000 })
}
export const confirmTxt = (id, options) => request.post(`/admin/novels/imports/${id}/confirm`, options)
export const getTxtTask = (id) => request.get(`/admin/novels/imports/${id}`)
export const discardTxt = (id) => request.delete(`/admin/novels/imports/${id}`)
