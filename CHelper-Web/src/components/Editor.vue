<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { EditorValue } from '../types'

const props = defineProps<{
  modelValue: EditorValue
  syntaxTokens: number[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: EditorValue): void
}>()

const editorRef = ref<HTMLDivElement | null>(null)

let isComposing = false
// 上次应用语法高亮时的文本与 token；两者都未变化时跳过重建，避免破坏选区
let highlightedText: string | null = null
let highlightedTokens: number[] | null = null
// selectionchange 的节流句柄
let selectionSyncHandle = 0

function getText(): string {
  const innerText = editorRef.value?.innerText ?? ''
  // 内容被删空后浏览器会留下一个 <br>，innerText 为换行符
  return innerText === '\n' ? '' : innerText
}

function setText(text: string): void {
  if (editorRef.value) {
    editorRef.value.innerText = text
  }
}

function getCursorPosition(): number {
  const editor = editorRef.value
  if (!editor || !editor.contains(document.activeElement)) {
    // 编辑器未聚焦时无法读取光标，沿用上次的值
    return props.modelValue.cursorPosition
  }
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0) {
    return 0
  }
  const range = selection.getRangeAt(0)
  const preRange = document.createRange()
  preRange.selectNodeContents(editor)
  preRange.setEnd(range.startContainer, range.startOffset)
  return preRange.toString().length
}

function setCursorPosition(cursorPosition: number): void {
  const editor = editorRef.value
  const selection = window.getSelection()
  if (!editor || !selection) {
    return
  }
  const range = document.createRange()
  let charCount = 0
  let foundNode: Node | null = null
  const findNode = (node: Node): boolean => {
    if (node.nodeType === Node.TEXT_NODE) {
      if (charCount + (node as Text).length >= cursorPosition) {
        foundNode = node
        return true
      }
      charCount += (node as Text).length
    } else {
      for (const child of node.childNodes) {
        if (findNode(child)) {
          return true
        }
      }
    }
    return false
  }
  findNode(editor)
  if (foundNode) {
    const offset = Math.max(0, cursorPosition - charCount)
    range.setStart(foundNode, offset)
    range.collapse(true)
    selection.removeAllRanges()
    selection.addRange(range)
  }
}

function updateModelValue(): void {
  if (isComposing) {
    return
  }
  const value: EditorValue = {
    text: getText(),
    cursorPosition: getCursorPosition(),
  }
  if (
    props.modelValue.text !== value.text ||
    props.modelValue.cursorPosition !== value.cursorPosition
  ) {
    emit('update:modelValue', value)
  }
}

const TOKEN_COLORS: Record<number, string> = {
  1: '#4fad63', // boolean
  2: '#4fad63', // float
  3: '#4fad63', // integer
  4: '#4fad63', // symbol
  5: '#d4ac0d', // id
  6: '#07c160', // target selector
  7: '#9f20a7', // command
  8: '#836c0a', // bracket1
  9: '#9f20a7', // bracket2
  10: '#4571e1', // bracket3
  11: '#d95a53', // string
  12: '#0fa0c8', // null
  13: '#0fa0c8', // range
  14: '#0fa0c8', // literal
}

function escapeHtml(character: string): string {
  switch (character) {
    case '&':
      return '&amp;'
    case '<':
      return '&lt;'
    case '>':
      return '&gt;'
    case '"':
      return '&quot;'
    default:
      return character
  }
}

function sameTokens(a: number[], b: number[]): boolean {
  if (a.length !== b.length) {
    return false
  }
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) {
      return false
    }
  }
  return true
}

function applyHighlight(): void {
  const editor = editorRef.value
  if (!editor || props.syntaxTokens.length === 0) {
    return
  }
  const text = getText()
  if (!text) {
    editor.innerHTML = ''
    highlightedText = ''
    highlightedTokens = null
    return
  }
  // 文本和 token 都没有变化（例如只是光标移动）时不需要重建 DOM，避免破坏选区。
  // 注意：切换分支时文本可能不变但 token 不同，此时仍需重建。
  if (
    text === highlightedText &&
    highlightedTokens !== null &&
    sameTokens(props.syntaxTokens, highlightedTokens)
  ) {
    return
  }
  const colors: (string | null)[] = new Array(text.length).fill(null)
  for (let i = 0; i < props.syntaxTokens.length && i < text.length; i++) {
    colors[i] = TOKEN_COLORS[props.syntaxTokens[i]] ?? '#000000'
  }
  const cursorPosition = getCursorPosition()
  let html = ''
  for (let i = 0; i < text.length; i++) {
    html += `<span style="color:${colors[i]}">${escapeHtml(text[i])}</span>`
  }
  editor.innerHTML = html
  highlightedText = text
  highlightedTokens = props.syntaxTokens.slice()
  setCursorPosition(cursorPosition)
}

/** 当前选区是否位于编辑器内部。 */
function isSelectionInEditor(): boolean {
  const editor = editorRef.value
  if (!editor) {
    return false
  }
  const selection = window.getSelection()
  return (
    selection !== null &&
    selection.rangeCount > 0 &&
    editor.contains(selection.getRangeAt(0).startContainer)
  )
}

/** 聚焦编辑器并全选其内容。 */
function selectAllInEditor(): void {
  const editor = editorRef.value
  if (!editor) {
    return
  }
  editor.focus()
  const range = document.createRange()
  range.selectNodeContents(editor)
  const selection = window.getSelection()
  if (selection) {
    selection.removeAllRanges()
    selection.addRange(range)
  }
}

/**
 * 修复：编辑器失焦后按 Backspace/Delete 无法删除文本的问题。
 * 该问题源于失焦时（例如点击了补全提示或页面其它区域）Backspace 的默认
 * 删除动作不会作用于编辑器内容，因此文本删不掉。
 */
function onDocumentKeyDownCapture(event: KeyboardEvent): void {
  // Ctrl/Cmd + A：始终全选编辑器内容，避免失焦时全选的其实是整个页面
  if (event.key === 'a' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    selectAllInEditor()
    updateModelValue()
    return
  }
  // Backspace / Delete：若选区在编辑器内但编辑器已失焦，先把焦点还回编辑器，
  // 让浏览器的默认删除动作生效
  if (
    (event.key === 'Backspace' || event.key === 'Delete') &&
    isSelectionInEditor() &&
    !editorRef.value?.contains(document.activeElement)
  ) {
    editorRef.value?.focus()
  }
}

function onDocumentSelectionChange(): void {
  // 光标/选区的移动（鼠标点击、方向键、Ctrl+A 等）不会触发 input 事件，
  // 用 selectionchange 同步光标位置（取代原来的 100ms 轮询）
  if (selectionSyncHandle === 0) {
    selectionSyncHandle = window.setTimeout(() => {
      selectionSyncHandle = 0
      updateModelValue()
    }, 0)
  }
}

function onEditorInput(): void {
  updateModelValue()
}

function onCompositionStart(): void {
  isComposing = true
}

function onCompositionEnd(): void {
  isComposing = false
  updateModelValue()
}

function onEditorKeyDown(event: KeyboardEvent): void {
  if (event.key === 'Enter') {
    event.preventDefault()
  }
}

onMounted(() => {
  const editor = editorRef.value
  if (editor) {
    editor.addEventListener('input', onEditorInput)
    editor.addEventListener('compositionstart', onCompositionStart)
    editor.addEventListener('compositionend', onCompositionEnd)
    editor.addEventListener('keydown', onEditorKeyDown)
  }
  document.addEventListener('selectionchange', onDocumentSelectionChange)
  document.addEventListener('keydown', onDocumentKeyDownCapture, true)
})

onBeforeUnmount(() => {
  const editor = editorRef.value
  if (editor) {
    editor.removeEventListener('input', onEditorInput)
    editor.removeEventListener('compositionstart', onCompositionStart)
    editor.removeEventListener('compositionend', onCompositionEnd)
    editor.removeEventListener('keydown', onEditorKeyDown)
  }
  document.removeEventListener('selectionchange', onDocumentSelectionChange)
  document.removeEventListener('keydown', onDocumentKeyDownCapture, true)
  if (selectionSyncHandle !== 0) {
    clearTimeout(selectionSyncHandle)
  }
})

// 父组件发起的变化（如点击补全提示、切换分支后重新同步）才回写编辑器，
// 用户正在输入时不会反过来覆盖 DOM
watch(
  () => props.modelValue,
  (newValue) => {
    const domText = getText()
    if (newValue.text !== domText) {
      setText(newValue.text)
      setCursorPosition(newValue.cursorPosition)
    } else if (newValue.cursorPosition !== getCursorPosition()) {
      setCursorPosition(newValue.cursorPosition)
    }
  },
)

watch(
  () => props.syntaxTokens,
  () => {
    applyHighlight()
  },
)
</script>

<template>
  <div ref="editorRef" class="editor" contenteditable="true" spellcheck="false"></div>
</template>

<style scoped>
.editor {
  margin: 0 5px 0 5px;
  width: calc(100vw - 140px);
  height: auto;
  color: black;
  text-align: left;
  background-color: white;
  padding: 10px;
  border: 0;
  border-radius: 5px;
  outline: 1px solid lightgrey;
  overflow-x: auto;
  white-space: pre;
  word-wrap: normal;
}

.editor:focus {
  background-color: #ffffff;
  outline: 2px solid #007bff;
}

.editor span {
  display: inline;
}
</style>
