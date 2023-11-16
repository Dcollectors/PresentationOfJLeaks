<script setup lang="ts">
import MyButton from "@/components/MyButton.vue"

function onClickStart() {
  // console.log("click")
  window.open("https://github.com/Dcollectors/JLeaks", "_blank")
}
</script>

<template>
  <main style="height: 100%">
    <div class="content">
      <h1 class="title">JLeaks: A Featured Resource Leak Repository Collected From Hundreds of Open-Source Java Projects</h1>
      <h2 class="title2">JLeaks is a resource leaks repository collected from real-world projects which facilitates in-depth researches and evaluation of defect-related algorithms. Each defect in Leaks includes four aspects key information: project information, defect information, code characteristics, and file information.</h2>
    
      <div class="button-div">
        <MyButton @click="onClickStart">GITHUB</MyButton>
      </div>
    </div>
  </main>
</template>

<style lang="scss" scoped>
.content {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 0 128px;
}

.title {
  font-size: 3rem;
  color: white;
}

.title2 {
  padding-top: 32px;
  color: white;
}

.button-div {
  padding-top: 128px;
}
</style>