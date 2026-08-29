import os
import shutil
import subprocess
import sys


def ensure_download_emsdk(toolchain_dir: str):
    emsdk_path = os.path.join(toolchain_dir, "emsdk")
    if not os.path.exists(emsdk_path):
        subprocess.run(
            ["git", "clone", "https://github.com/emscripten-core/emsdk"],
            cwd=toolchain_dir,
            check=True,
        )
    else:
        subprocess.run(["git", "pull"], cwd=emsdk_path, check=True)
    subprocess.run(
        ["python", "./emsdk.py", "install", "latest"], cwd=emsdk_path, check=True
    )
    subprocess.run(
        ["python", "./emsdk.py", "activate", "latest"], cwd=emsdk_path, check=True
    )


def build_web_core(toolchain_dir: str):
    emsdk_path = os.path.join(toolchain_dir, "emsdk")
    build_directory = "./build/web_core"
    subprocess.run(
        [
            "cmake",
            "-S",
            "./CHelper-Core",
            "-D",
            "CMAKE_BUILD_TYPE=MinSizeRel",
            "-D",
            f"CMAKE_TOOLCHAIN_FILE={emsdk_path}/upstream/emscripten/cmake/Modules/Platform/Emscripten.cmake",
            "-B",
            build_directory,
            "-G",
            "Ninja",
        ],
        check=True,
    )
    subprocess.run(
        ["cmake", "--build", build_directory, "--target", "CHelperWeb"],
        check=True,
    )
    # Link with em++ so Emscripten includes the C++ runtime required by static libraries.
    subprocess.run(
        [
            "python",
            os.path.join(emsdk_path, "upstream", "emscripten", "em++.py"),
            f"{build_directory}/libCHelperWeb.a",
            f"{build_directory}/libCHelperNoFilesystemCore.a",
            f"{build_directory}/3rdparty/fmt/libfmt.a",
            f"{build_directory}/3rdparty/spdlog/libspdlog.a",
            f"{build_directory}/3rdparty/xxHash/cmake_unofficial/libxxhash.a",
            "-Os",
            "-o",
            f"{build_directory}/libCHelperWeb.js",
            "-s",
            "FILESYSTEM=0",
            "-s",
            "DISABLE_EXCEPTION_CATCHING=1",
            "-s",
            "ALLOW_MEMORY_GROWTH",
            "-s",
            'ENVIRONMENT=["web"]',
            "-s",
            "EXPORTED_FUNCTIONS=['_init','_release','_createCommandContext','_releaseCommandContext','_contextGetCommand','_contextGetStructure','_contextGetParamHint','_contextGetErrorReasons','_contextGetSuggestionSize','_contextGetSuggestion','_contextGetAllSuggestions','_contextApplySuggestion','_contextGetSyntaxTokens','_contextGetNodeCount','_malloc','_free']",
            "-s",
            "WASM=1",
            "-s",
            "EXPORTED_RUNTIME_METHODS=[]",
        ],
        check=True,
    )
    shutil.copyfile(
        os.path.join(build_directory, "libCHelperWeb.wasm"),
        os.path.join(".", "CHelper-Web", "src", "assets", "libCHelperWeb.wasm"),
    )
    shutil.copyfile(
        os.path.join(build_directory, "libCHelperWeb.js"),
        os.path.join(".", "CHelper-Web", "src", "core", "libCHelperWeb.js"),
    )
    with open(os.path.join(build_directory, "libCHelperWeb.js"), "r") as fp:
        content = fp.read()
        content = "import wasmUrl from '@/assets/libCHelperWeb.wasm?url'\n\n" + content
        content = content.replace('locateFile("libCHelperWeb.wasm")', "wasmUrl;")
        content = content.replace(
            "var wasmExports;createWasm()",
            "var wasmExports;export var createWasmFuture = createWasm()",
        )
        content += """
function alignPtr(ptr) {
  return ptr + (ptr % 4)
}

// 读取 [4字节长度][u16字符串]
function readString(ptr) {
  const length = HEAPU32[ptr >> 2]
  ptr += 4
  let result = ''
  for (let i = 0; i < length; i++) {
    result += String.fromCharCode(HEAPU16[ptr >> 1])
    ptr += 2
  }
  return result
}

// 写入 utf16 编码并以0结尾的字符串，返回起始指针
function writeString(content) {
  const ptr = _malloc((content.length + 1) * 2)
  const start = ptr / 2
  const end = start + content.length
  let i = start
  while (i < end) {
    HEAPU16[i] = content.charCodeAt(i - start)
    ++i
  }
  HEAPU16[i] = 0
  return ptr
}

// 读取 [4字节长度][u16字符串]，并返回字符串后面的位置
function readStringAndAdvance(ptr) {
  const length = HEAPU32[ptr >> 2]
  const result = readString(ptr)
  return {
    value: result,
    next: ptr + 4 + length * 2,
  }
}

// 读取 [4字节name长度][4字节description长度][u16 name][u16 description]
function readSuggestion(ptr, which) {
  const nameLength = HEAPU32[ptr >> 2]
  ptr += 4
  const descriptionLength = HEAPU32[ptr >> 2]
  ptr += 4
  let title = ''
  for (let i = 0; i < nameLength; i++) {
    title += String.fromCharCode(HEAPU16[ptr >> 1])
    ptr += 2
  }
  let description = ''
  for (let i = 0; i < descriptionLength; i++) {
    description += String.fromCharCode(HEAPU16[ptr >> 1])
    ptr += 2
  }
  return {
    id: which,
    title,
    description,
  }
}

// 读取 [4字节数量]([4字节name长度][4字节description长度][u16 name][u16 description])*
function readSuggestions(ptr) {
  ptr = alignPtr(ptr)
  const length = HEAPU32[ptr >> 2]
  ptr += 4
  const suggestions = []
  for (let i = 0; i < length; i++) {
    ptr = alignPtr(ptr)
    const nameLength = HEAPU32[ptr >> 2]
    const descriptionLength = HEAPU32[(ptr + 4) >> 2]
    suggestions.push(readSuggestion(ptr, i))
    ptr += 8 + nameLength * 2 + descriptionLength * 2
  }
  return suggestions
}

// 软件内核，负责持有资源包并通过createContext创建命令上下文
// 所有和命令相关的功能都在CommandContext上执行
export class CHelperCore {
  constructor(cpack) {
    const cpackPtr = _malloc(cpack.byteLength)
    HEAP8.set(cpack, cpackPtr)
    this._corePtr = _init(cpackPtr, cpack.byteLength)
    _free(cpackPtr)
    if (this._corePtr === 0) {
      throw 'fail to init CHelper core'
    }
  }

  release() {
    _release(this._corePtr)
    this._corePtr = 0
  }

  // 把命令文本解析成AST，生成独立的命令上下文
  // 适用于多线程并行的场景：可以创建任意多个CommandContext并行使用
  createContext(command) {
    return new CommandContext(this._corePtr, command)
  }
}

// 读取 [4字节光标位置][4字节长度][u16字符串]
function readClickSuggestionResult(ptr) {
  if (ptr === 0) {
    return null
  }
  ptr = alignPtr(ptr)
  const cursorPosition = HEAPU32[ptr >> 2]
  const text = readString(ptr + 4)
  return {
    cursorPosition,
    newText: text,
  }
}

// 读取 [4字节数量][u8]*
function readSyntaxTokens(ptr) {
  if (ptr === 0) {
    return null
  }
  ptr = alignPtr(ptr)
  const length = HEAPU32[ptr >> 2]
  ptr += 4
  const syntaxTokens = []
  for (let i = 0; i < length; i++) {
    syntaxTokens.push(HEAPU8[ptr])
    ptr += 1
  }
  return syntaxTokens
}

// 命令上下文，持有某条命令解析好的AST
// 和CHelperCore不同，CommandContext没有可变状态，
// 所有操作都通过参数传入位置，因此可以把同一个CommandContext
// 交给多个线程同时读取，也可以创建多个CommandContext并行工作
export class CommandContext {
  // corePtr 是CHelperCore对应的指针，command 是命令文本
  constructor(corePtr, command) {
    const ptr = writeString(command)
    this._contextPtr = _createCommandContext(corePtr, ptr)
    _free(ptr)
    if (this._contextPtr === 0) {
      throw 'fail to create CommandContext'
    }
  }

  release() {
    _releaseCommandContext(this._contextPtr)
    this._contextPtr = 0
  }

  // 获取这个上下文对应的命令文本
  getCommand() {
    const ptr = _contextGetCommand(this._contextPtr)
    if (ptr === 0) {
      return ''
    }
    return readString(alignPtr(ptr))
  }

  // 获取命令结构
  getStructure() {
    const ptr = _contextGetStructure(this._contextPtr)
    if (ptr === 0) {
      return ''
    }
    return readString(alignPtr(ptr))
  }

  // 获取指定位置的参数注释
  getParamHint(index) {
    const ptr = _contextGetParamHint(this._contextPtr, index)
    if (ptr === 0) {
      return ''
    }
    return readString(alignPtr(ptr))
  }

  // 获取命令的错误原因
  getErrorReasons() {
    let ptr = _contextGetErrorReasons(this._contextPtr)
    if (ptr === 0) {
      return []
    }
    ptr = alignPtr(ptr)
    const length = HEAPU32[ptr >> 2]
    ptr += 4
    const errorReasons = []
    for (let i = 0; i < length; i++) {
      const start = HEAPU32[ptr >> 2]
      ptr += 4
      const end = HEAPU32[ptr >> 2]
      ptr += 4
      const errorReason = readStringAndAdvance(ptr)
      ptr = errorReason.next
      errorReasons.push({
        start,
        end,
        errorReason: errorReason.value,
      })
    }
    return errorReasons
  }

  // 获取指定位置的补全提示数量
  getSuggestionSize(index) {
    return _contextGetSuggestionSize(this._contextPtr, index)
  }

  // 获取指定位置的其中一个补全提示
  getSuggestion(index, which) {
    const ptr = _contextGetSuggestion(this._contextPtr, index, which)
    if (ptr === 0) {
      return null
    }
    return readSuggestion(alignPtr(ptr), which)
  }

  // 获取指定位置的所有补全提示
  getAllSuggestions(index) {
    const ptr = _contextGetAllSuggestions(this._contextPtr, index)
    if (ptr === 0) {
      return []
    }
    return readSuggestions(ptr)
  }

  // 把指定位置的其中一个补全提示应用到命令文本
  // 不会修改自身的状态，同样的操作可以重复执行
  applySuggestion(index, which) {
    return readClickSuggestionResult(_contextApplySuggestion(this._contextPtr, index, which))
  }

  // 获取每个字符的token类型，用于语法高亮
  getSyntaxTokens() {
    return readSyntaxTokens(_contextGetSyntaxTokens(this._contextPtr))
  }

  // 获取最佳解析路径中已经匹配的命令语义节点数量
  getNodeCount() {
    return _contextGetNodeCount(this._contextPtr)
  }
}
"""
    with open(
        os.path.join(".", "CHelper-Web", "src", "core", "libCHelperWeb.js"), "w"
    ) as fp:
        fp.write(content)


if __name__ == "__main__":
    # check toolchain
    if (
        subprocess.run(
            ["node", "-v"],
            capture_output=True,
            check=False,
        ).returncode
        != 0
    ):
        print("please download nodejs")
        sys.exit(-1)
    if (
        subprocess.run(
            ["cmake", "--version"],
            capture_output=True,
            check=False,
        ).returncode
        != 0
    ):
        print("please download cmake")
        sys.exit(-1)
    if (
        subprocess.run(
            ["ninja", "--version"],
            capture_output=True,
            check=False,
        ).returncode
        != 0
    ):
        print("please download ninja")
        sys.exit(-1)
    toolchain_dir = os.path.join(os.getcwd(), "toolchain")
    os.makedirs(toolchain_dir, exist_ok=True)
    ensure_download_emsdk(toolchain_dir)

    # build web core
    print("building web core...")
    build_web_core(toolchain_dir)
