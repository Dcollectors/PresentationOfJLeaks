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
      <h1 class="title">Effitiently Trimming the Fat</h1>
      <h2 class="title2">Lorem ipsum dolor sit amet consectetur adipisicing elit. Sunt culpa vitae, accusamus placeat ipsa velit quasi, magni itaque in quae doloremque quisquam molestiae ea consequatur quo maiores ipsum id eius?</h2>
    
      <div class="button-div">
        <MyButton @click="onClickStart">Github</MyButton>
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