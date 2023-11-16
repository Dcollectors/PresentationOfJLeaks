<script setup lang="ts">
// @ts-ignore
import _data from "@/assets/JLeaks.json"
import * as monaco from "monaco-editor"
import { ref, onMounted } from "vue"

const allKeys = ["ID", "projects", "# of commits", "UTC of create", "UTC of last modify", "# of stars", "# of issues", "# of forks",
    "# of releases", "# of Contributors", "# of requests", "about", "commit url", "UTC of buggy commit", "UTC of fix commit",
    "start line", "end line", "defect method", "change lines", "resource types", "root causes", "fixed approaches", "patch correction",
    "standard libraries", "third-party libraries", "is interprocedural", "key variable name", "key variable location", "key variable attribute",
    "defect file hash", "fix file hash", "defect file url", "fix file url"
]

const data: any = _data.slice(0, 100)

const editorContainer = ref<null | HTMLElement>(null)
let editor: ReturnType<typeof monaco.editor.create> | null = null
onMounted(() => {
    if (editorContainer.value) {
        editor = monaco.editor.create(editorContainer.value, {
            value: "",
            language: "java",
            automaticLayout: true
        })
    }
})

async function handleClickItem(entry: any) {
    const id: number = entry["ID"]
    const hash: string = entry["defect file hash"]
    console.log(id)
    const filename: string = "bug-" + id.toString() + "-" + hash
    
    const content = await fetch("/all_bug_files/" + filename + ".java")
    const s = await content.text()
    // console.log(await content.text())

    if (editor) {
        editor.getModel()?.setValue(s)

        const start: number = entry["start line"]
        const end: number = entry["end line"]
        console.log(start, end)
        editor.createDecorationsCollection([
            {
                range: new monaco.Range(start, 1, end, 1),
                options: {
                    isWholeLine: true,
                    // inlineClassName: "decoration"
                    linesDecorationsClassName: "decoration",
                }
            }
        ])

        editor.revealLine(start)
    }
}



// const myEditor = monaco.editor.create(document.getElementById("editor-container") as HTMLElement, {
//     value: "int a = 1",
//     language: "java",
//     automaticLayout: true
// })
</script>

<template>
    <div class="root">
        <div class="left">
            <table>
                <tr>
                    <th v-for="key in allKeys" class="header-cell">{{ key }}</th>
                </tr>
                <tbody>

                </tbody>
                <tr
                    v-for="item in data"
                    class="row"
                    @click="handleClickItem(item)"
                >
                    <td
                        v-for="key in allKeys"
                        class="cell"
                        :title="item[key]"
                    >{{ (item as any)[key] }}</td>
                </tr>
            </table>
        </div>

        <div class="right">
            <div class="editor-container" ref="editorContainer"></div>
        </div>
    </div>
</template>

<style lang="scss">
.decoration {
    background-color: red;
    width: 5px !important;
    margin-left: 3px;
}
</style>

<style lang="scss" scoped>
table {
    border: none;
}

.root {
    padding: 24px 128px;
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
}

.left {
    // max-height: 50vh;
    // max-width: 50%;
    width: 50%;
    height: 100%;
    overflow: scroll;
    background-color: #ffffff;
}

.right {
    // max-height: 50vh;
    // width: 300px;
    width: 50%;
    height: 100%;
    // overflow: scroll;
}

.row {
    max-height: 32px;
    // text-overflow: ellipsis;
    transition: 300ms;

    &:hover {
        background-color: #12345611;
    }
}

.cell {
    max-height: 32px;
    height: 32px;
    text-overflow: ellipsis;
    text-wrap: nowrap;
    max-width: 100px;
    overflow:hidden;
    font-size: 0.8rem;
    padding: 0 4px;
    text-align: center;
    cursor: pointer;
}

.header-cell {
    height: 32px;
    text-overflow: ellipsis;
    font-size: 0.8rem;
    font-weight: bold;
}

.editor-container {
    height: 100%;
    width: 100%;
}
</style>