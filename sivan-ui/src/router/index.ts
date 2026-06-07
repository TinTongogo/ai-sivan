import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AppLayout from '../components/layout/AppLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/login/index.vue'),
    },
    {
      path: '/',
      component: AppLayout,
      redirect: '/conversations',
      children: [
        { path: 'agents', name: 'Agents', component: () => import('../views/agents/index.vue') },
        { path: 'skills', name: 'Skills', component: () => import('../views/skills/index.vue') },
        { path: 'knowledge-bases', name: 'KnowledgeBases', component: () => import('../views/knowledge-bases/index.vue') },
        { path: 'conversations', name: 'Conversations', component: () => import('../views/conversations/index.vue') },
        { path: 'memories', name: 'Memories', component: () => import('../views/memories/index.vue') },
        { path: 'patterns', name: 'Patterns', component: () => import('../views/patterns/index.vue') },
        { path: 'routing-decisions', name: 'RoutingDecisions', component: () => import('../views/routing-decisions/index.vue') },
        { path: 'token-usage', name: 'TokenUsage', component: () => import('../views/token-usage/index.vue') },
        // Squad 路由（全链路重构后）
        { path: 'squads', name: 'Squads', component: () => import('../views/squads/SquadHome.vue') },
        { path: 'squads/orchestration', name: 'SquadOrchestration', component: () => import('../views/squads/SquadOrchestration.vue') },
        { path: 'squads/executions/:execId', name: 'SquadExecutionDetail', component: () => import('../views/squad-executions/ExecutionMonitor.vue') },
        { path: '/:pathMatch(.*)*', redirect: '/conversations' },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (to.name === 'Login') return true
  const auth = useAuthStore()
  if (!auth.isLoggedIn) return '/login'
})

export default router
