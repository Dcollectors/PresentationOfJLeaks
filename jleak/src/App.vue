<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import back from "@/assets/back.avif"

function handleClickGithub() {
  window.open("https://github.com/Dcollectors/JLeaks", "_blank")
}

const rootStyle = {
  background: `url(${back}) no-repeat center center fixed`
}
</script>

<template>
  <div class="root" :style="rootStyle">
    <div class="header">
      <div class="link" @click="$router.push({name: 'home'})">Home</div>
      <div class="link" @click="$router.push({name: 'issue'})">Issue Reports</div>
      <div class="link" @click="$router.push({name: 'about'})">Abount</div>
      <div class="link" @click="handleClickGithub">Github</div>
    </div>
    <div class="fake-header"></div>

    <div class="router-view">
      <RouterView></RouterView>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.root {
  // background-image: url("back.avif") no-repeat center center fixed;
  // background: url("back.avif") no-repeat center center fixed;
  // background-size: cover;
}

.header {
  height: 64px;
  width: 100%;
  background-color: #000000aa;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  position: fixed;
  // gap: 24px;
  // padding-right: 24px;
}

.fake-header {
  height: 64px;
}

.link {
  cursor: pointer;
  height: 100%;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: 300ms;
  color: white;

  &:hover {
    color: white;
    background-color: #123456;
  }
}

.router-view {
  height: calc(100vh - 64px);
  max-height: calc(100vh - 64px);
  overflow: auto;
}
</style>
