<template>
  <el-container style="height: 100vh">
    <el-aside width="210px" class="aside">
      <div class="logo">🛰️ 地面站排程</div>
      <el-menu :default-active="$route.path" router class="menu" background-color="#001529"
               text-color="#c0c4cc" active-text-color="#ffffff">
        <el-menu-item index="/gantt">
          <el-icon><DataLine /></el-icon><span>窗口甘特图</span>
        </el-menu-item>
        <el-menu-item index="/workbench">
          <el-icon><SetUp /></el-icon><span>排程工作台</span>
        </el-menu-item>
        <el-menu-item index="/versions">
          <el-icon><Files /></el-icon><span>版本管理与对比</span>
        </el-menu-item>
        <el-menu-item index="/resources">
          <el-icon><Setting /></el-icon><span>资源管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="filters">
          <el-date-picker v-model="store.filterDate" type="date" value-format="YYYY-MM-DD"
                          :clearable="false" size="default" @change="onFilterChange" />
          <el-select v-model="store.filterStationId" placeholder="全部地面站" clearable
                     style="width: 150px" @change="onFilterChange">
            <el-option v-for="s in store.stations" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <el-select v-model="store.filterSatelliteId" placeholder="全部卫星" clearable
                     style="width: 170px" @change="onFilterChange">
            <el-option v-for="s in store.satellites" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <el-radio-group v-model="store.displayTz" size="default">
            <el-radio-button value="UTC">UTC</el-radio-button>
            <el-radio-button value="LOCAL">站址本地</el-radio-button>
          </el-radio-group>
        </div>
        <div class="title">{{ $route.meta.title }}</div>
      </el-header>
      <el-main>
        <router-view :key="routeKey" />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useResourceStore } from '@/stores/resource'

const store = useResourceStore()
const route = useRoute()

const routeKey = computed(() => route.path)

function onFilterChange() {
  // 各页面通过 :key 变化自动重载
}

onMounted(() => store.loadAll())
</script>

<style scoped>
.aside {
  background: #001529;
  color: #fff;
}
.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  letter-spacing: 1px;
}
.menu {
  border-right: none;
}
.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 20px;
}
.filters {
  display: flex;
  gap: 10px;
  align-items: center;
}
.title {
  font-weight: 600;
  font-size: 16px;
  color: #303133;
}
</style>
