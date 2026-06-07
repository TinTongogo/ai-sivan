import api from './index'

export interface DailyConsumption {
  date: string
  totalInput: number
  totalOutput: number
  totalTokens: number
  level: number
}

export function fetchDailyConsumption(days = 90): Promise<DailyConsumption[]> {
  return api.get('/token-usage/daily-consumption', { params: { days } })
    .then(res => (res as any).data || [])
}
