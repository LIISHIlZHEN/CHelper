<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import MarkdownIt from "markdown-it";

const props = defineProps<{
  url: string;
}>();

const md = new MarkdownIt({ html: true });

const loading = ref(true);
const error = ref("");
const content = ref("");

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const res = await fetch(props.url, { cache: "no-store" });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status} ${res.statusText}`);
    }
    content.value = await res.text();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

onMounted(load);

const rendered = computed(() => (content.value ? md.render(content.value) : ""));
</script>

<template>
  <div class="remote-markdown">
    <div v-if="loading" class="remote-markdown-status">正在加载更新日志...</div>
    <div v-else-if="error" class="remote-markdown-status remote-markdown-error">
      <span>更新日志加载失败：{{ error }}</span>
      <button class="remote-markdown-retry" @click="load">重试</button>
    </div>
    <div v-else class="remote-markdown-content" v-html="rendered" />
  </div>
</template>

<style scoped>
.remote-markdown-status {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  color: var(--vp-c-text-2);
  font-size: 14px;
}

.remote-markdown-error {
  color: var(--vp-c-danger-1);
}

.remote-markdown-retry {
  padding: 2px 12px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 6px;
  background: var(--vp-c-bg-alt);
  color: var(--vp-c-text-1);
  font-size: 13px;
  cursor: pointer;
}

.remote-markdown-retry:hover {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}
</style>
