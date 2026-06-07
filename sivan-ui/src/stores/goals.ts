import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchGoals as apiFetchGoals, fetchGoalDetail as apiFetchGoalDetail } from '../api/goals'
import type { GoalItem } from '../api/goals'

export const useGoalsStore = defineStore('goals', () => {
  const goals = ref<GoalItem[]>([])
  const currentGoal = ref<GoalItem | null>(null)
  const loading = ref(false)

  async function loadGoals() {
    loading.value = true
    try {
      const res = await apiFetchGoals()
      goals.value = res.data || []
    } catch {
      goals.value = []
    } finally {
      loading.value = false
    }
  }

  async function loadGoalDetail(goalId: string) {
    try {
      const res = await apiFetchGoalDetail(goalId)
      currentGoal.value = res.data || null
      return currentGoal.value
    } catch {
      currentGoal.value = null
      return null
    }
  }

  function updateGoalInList(updated: GoalItem) {
    const idx = goals.value.findIndex(g => g.goalId === updated.goalId)
    if (idx >= 0) goals.value[idx] = updated
  }

  return {
    goals, currentGoal, loading,
    loadGoals, loadGoalDetail, updateGoalInList,
  }
})
