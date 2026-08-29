/**
 * 编辑器与 App 之间通过 modelValue 同步的编辑状态。
 */
export interface EditorValue {
  text: string
  cursorPosition: number
}
