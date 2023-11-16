<script setup lang="ts">
defineEmits(["click"])

</script>

<template>
    <button class="button" @click="$emit('click')">
        <slot></slot>
    </button>
</template>

<style lang="scss" scoped>
$color: #123456;

.button {
    outline: none;
    background-color: #00000011;
    padding: 12px 24px;
    font-size: 1.7rem;
    border: none;
    transition: 300ms;
    letter-spacing: 6px;
    font-family: "consolas";
    border-radius: 3px;
    cursor: pointer;
    color: white;

    &:hover {
        box-shadow: 0px 0px 80px $color, 0px 0px 40px $color, 0px 0px 20px $color, 0px 0px 5px $color;
        background-color: $color;
        color: white;
    }
}
</style>