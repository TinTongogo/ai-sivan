import api from './index'

export interface GroupItem {
  projectId: string
  name: string
  shortId?: string
  description?: string
  localPath?: string | null
  localPathAuto?: boolean
  archived?: boolean
  archivedAt?: string
  undeletable?: boolean
  createdAt?: string
}

export function fetchGroups(): Promise<GroupItem[]> {
  return api.get('/groups').then(res => res.data)
}

export function createGroup(name: string): Promise<GroupItem> {
  return api.post('/groups', { name }).then(res => res.data)
}

export function renameGroup(id: string, name: string): Promise<GroupItem> {
  return api.put(`/groups/${id}`, { name }).then(res => res.data)
}

export function deleteGroup(id: string, removeFiles?: boolean): Promise<void> {
  return api.delete(`/groups/${id}`, { params: { removeFiles: removeFiles ?? false } }).then(res => res.data)
}

export function archiveGroup(id: string): Promise<GroupItem> {
  return api.post(`/groups/${id}/archive`).then(res => res.data)
}

export function unarchiveGroup(id: string): Promise<GroupItem> {
  return api.post(`/groups/${id}/unarchive`).then(res => res.data)
}

export function openProjectDirectory(id: string): Promise<void> {
  return api.post(`/groups/${id}/open-directory`).then(res => res.data)
}
