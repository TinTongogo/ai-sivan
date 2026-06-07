import api from './index'

export interface FileUploadResult {
  fileId: string
  fileName: string
  mimeType: string
  fileSize: number
}

export interface Attachment {
  fileId: string
  fileName: string
  mimeType: string
  fileSize: number
  type: 'image' | 'file'
}

/**
 * 上传文件到服务器，返回完整文件元信息。
 */
export async function uploadFile(file: File): Promise<FileUploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  const res = await api.post('/files', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return res.data
}

/** 判断 mimeType 是否为图片类型 */
export function isImageMime(mimeType: string): boolean {
  return mimeType.startsWith('image/')
}

/** 根据文件扩展名获取图标标识 */
export function getFileIcon(mimeType: string): string {
  if (mimeType.includes('pdf')) return 'pdf'
  if (mimeType.includes('word') || mimeType.includes('document')) return 'doc'
  if (mimeType.includes('spreadsheet') || mimeType.includes('excel')) return 'xls'
  if (mimeType.includes('zip') || mimeType.includes('rar') || mimeType.includes('tar')) return 'archive'
  if (mimeType.includes('text/')) return 'text'
  if (mimeType.includes('json') || mimeType.includes('xml')) return 'code'
  return 'file'
}
