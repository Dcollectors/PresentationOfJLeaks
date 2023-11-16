<script setup lang="ts">
import { Marked } from "marked"
import { markedHighlight } from "marked-highlight"
import hljs from "highlight.js"
import readme from "@/assets/README.md?raw"

const marked = new Marked(
  markedHighlight({
    langPrefix: "hljs language-",
    highlight(code, lang) {
      const language = hljs.getLanguage(lang) ? lang : 'plaintext';
      // console.log(lang);
      return hljs.highlight(code, { language }).value;
    }
  })
)

const html = marked.parse(readme);
</script>

<template>
  <div class="about">
    <div v-html="html" class="markdown"></div>
  </div>
</template>

<style lang="scss">
.about {
  padding: 24px 128px;
  // max-width: 800px;
  display: flex;
  justify-content: center;
}

.markdown {
  width: 800px;
  overflow: auto;
  background-color: #ffffffaa;
  padding: 64px 64px;
  // overflow:;
}
</style>
