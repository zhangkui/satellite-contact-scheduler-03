import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/gantt' },
    { path: '/gantt', name: 'gantt', component: () => import('@/views/GanttView.vue'),
      meta: { title: '窗口甘特图' } },
    { path: '/workbench', name: 'workbench', component: () => import('@/views/WorkbenchView.vue'),
      meta: { title: '排程工作台' } },
    { path: '/versions', name: 'versions', component: () => import('@/views/VersionsView.vue'),
      meta: { title: '版本管理与对比' } },
    { path: '/publish/:id', name: 'publish', component: () => import('@/views/PublishView.vue'),
      meta: { title: '发布确认' } },
    { path: '/resources', name: 'resources', component: () => import('@/views/ResourcesView.vue'),
      meta: { title: '资源管理' } }
  ]
})

export default router
