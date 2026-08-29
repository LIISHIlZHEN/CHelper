import { afterEach, describe, expect, it } from 'vitest'
import { getSelectionOffsets, setSelectionOffsets, type SelectionOffsets } from './EditorSelection'

function createEditor(markup: string): HTMLDivElement {
  const editor = document.createElement('div')
  editor.innerHTML = markup
  document.body.append(editor)
  return editor
}

function nativeSelectionText(): string {
  return window.getSelection()?.toString() ?? ''
}

afterEach(() => {
  window.getSelection()?.removeAllRanges()
  document.body.innerHTML = ''
})

describe('EditorSelection', () => {
  it('reads a selection spanning multiple highlighted text nodes', () => {
    const editor = createEditor('<span>hello</span><span><b> world</b></span>')
    const firstText = editor.querySelector('span')?.firstChild
    const secondText = editor.querySelector('b')?.firstChild
    expect(firstText).toBeInstanceOf(Text)
    expect(secondText).toBeInstanceOf(Text)

    const range = document.createRange()
    range.setStart(firstText as Text, 1)
    range.setEnd(secondText as Text, 3)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)

    expect(getSelectionOffsets(editor, { start: 0, end: 0 })).toEqual({ start: 1, end: 8 })
    expect(nativeSelectionText()).toBe('ello wo')
  })

  it.each([
    { start: 0, end: 11 },
    { start: 6, end: 11 },
    { start: 5, end: 5 },
  ] satisfies SelectionOffsets[])('restores offsets $start-$end', ({ start, end }) => {
    const editor = createEditor('<span>hello</span><span> world</span>')

    setSelectionOffsets(editor, start, end)

    expect(getSelectionOffsets(editor, { start: 0, end: 0 })).toEqual({ start, end })
    expect(nativeSelectionText()).toBe(editor.textContent?.slice(start, end))
  })

  it('clamps offsets and handles an empty editor', () => {
    const editor = createEditor('<span>hello</span>')
    setSelectionOffsets(editor, -10, 100)
    expect(getSelectionOffsets(editor, { start: 0, end: 0 })).toEqual({ start: 0, end: 5 })

    editor.innerHTML = ''
    setSelectionOffsets(editor, 10, -10)
    expect(getSelectionOffsets(editor, { start: 1, end: 2 })).toEqual({ start: 0, end: 0 })
  })

  it('uses the saved selection when the browser selection is outside the editor', () => {
    const editor = createEditor('<span>hello world</span>')
    const outside = document.createElement('button')
    outside.textContent = 'outside'
    document.body.append(outside)
    const range = document.createRange()
    range.selectNodeContents(outside)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)

    expect(getSelectionOffsets(editor, { start: 2, end: 7 })).toEqual({ start: 2, end: 7 })
  })

  it('preserves a saved selection after highlighted DOM reconstruction', () => {
    const editor = createEditor('<span>hello</span><span> world</span>')
    setSelectionOffsets(editor, 0, 11)
    const savedSelection = getSelectionOffsets(editor, { start: 0, end: 0 })

    editor.innerHTML = '<span style="color:red">hello</span><span> world</span>'
    setSelectionOffsets(editor, savedSelection.start, savedSelection.end)

    expect(getSelectionOffsets(editor, { start: 0, end: 0 })).toEqual({ start: 0, end: 11 })
    expect(nativeSelectionText()).toBe('hello world')
  })
})
