/**
 * It is part of CHelper. CHelper is a command helper for Minecraft Bedrock Edition.
 * Copyright (C) 2026  Yancey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <chelper/CHelperCore.h>
#include <emscripten/emscripten.h>

namespace {

    // 用于向JS返回数据的缓冲区，JS侧是单线程的，可以安全复用
    std::vector<std::uint8_t> buffer;

    uint8_t *alignPointer(uint8_t *pointer) {
        return pointer + (reinterpret_cast<size_t>(pointer) % 4);
    }

    // 布局: [4字节长度][u16字符串]
    const uint8_t *writeU16String(const std::u16string &string) {
        buffer.resize((reinterpret_cast<size_t>(buffer.data()) % 4) + 4 + string.size() * 2);
        uint8_t *pointer = alignPointer(buffer.data());
        *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(string.size());
        pointer += 4;
        memcpy(pointer, string.data(), string.size() * 2);
        return buffer.data();
    }

    // 布局: [4字节数量]([4字节start][4字节end][4字节长度][u16字符串])*
    const uint8_t *writeErrorReasons(const std::vector<std::shared_ptr<CHelper::ErrorReason>> &errorReasons) {
        size_t size = (reinterpret_cast<size_t>(buffer.data()) % 4) + 4;
        for (const auto &item: errorReasons) {
            size = size + 12 + item->errorReason.size() * 2;
        }
        buffer.resize(size);
        uint8_t *pointer = alignPointer(buffer.data());
        *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(errorReasons.size());
        pointer += 4;
        for (const auto &item: errorReasons) {
            *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(item->start);
            pointer += 4;
            *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(item->end);
            pointer += 4;
            *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(item->errorReason.size());
            pointer += 4;
            memcpy(pointer, item->errorReason.data(), item->errorReason.size() * 2);
            pointer += item->errorReason.size() * 2;
        }
        return buffer.data();
    }

    // 布局: [4字节name长度][4字节description长度][u16 name][u16 description]
    uint8_t *writeSuggestion(uint8_t *pointer, const CHelper::AutoSuggestion::Suggestion &suggestion) {
        const std::u16string &name = suggestion.content->name;
        const std::optional<std::u16string> &description = suggestion.content->description;
        *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(name.size());
        pointer += 4;
        *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(description.has_value() ? description->size() : 0);
        pointer += 4;
        memcpy(pointer, name.data(), name.size() * 2);
        pointer += name.size() * 2;
        if (description.has_value()) {
            memcpy(pointer, description->data(), description->size() * 2);
            pointer += description->size() * 2;
        }
        return pointer;
    }

    size_t getSuggestionBytes(const CHelper::AutoSuggestion::Suggestion &suggestion) {
        size_t size = 8;
        size += suggestion.content->name.size() * 2;
        if (suggestion.content->description.has_value()) {
            size += suggestion.content->description->size() * 2;
        }
        return size;
    }

    // 布局: [4字节数量]([4字节name长度][4字节description长度][u16 name][u16 description])*
    const uint8_t *writeSuggestions(const std::vector<CHelper::AutoSuggestion::Suggestion> &suggestions) {
        size_t size = (reinterpret_cast<size_t>(buffer.data()) % 4) + 4;
        for (const auto &item: suggestions) {
            size += getSuggestionBytes(item);
        }
        buffer.resize(size);
        uint8_t *pointer = alignPointer(buffer.data());
        *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(suggestions.size());
        pointer += 4;
        for (const auto &item: suggestions) {
            pointer = writeSuggestion(pointer, item);
        }
        return buffer.data();
    }

    // 布局: [4字节光标位置][4字节长度][u16字符串]
    const uint8_t *writeSuggestionClickResult(const std::pair<std::u16string, size_t> &result) {
        buffer.resize((reinterpret_cast<size_t>(buffer.data()) % 4) + 8 + result.first.size() * 2);
        uint8_t *pointer = alignPointer(buffer.data());
        *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(result.second);
        pointer += 4;
        *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(result.first.size());
        pointer += 4;
        memcpy(pointer, result.first.data(), result.first.size() * 2);
        return buffer.data();
    }

    // 布局: [4字节数量][u8]*
    const uint8_t *writeSyntaxTokens(const std::vector<CHelper::SyntaxHighlight::SyntaxTokenType::SyntaxTokenType> &tokenTypes) {
        buffer.resize((reinterpret_cast<size_t>(buffer.data()) % 4) + 4 + tokenTypes.size());
        uint8_t *pointer = alignPointer(buffer.data());
        *reinterpret_cast<uint32_t *>(pointer) = static_cast<uint32_t>(tokenTypes.size());
        pointer += 4;
        memcpy(pointer, tokenTypes.data(), tokenTypes.size());
        return buffer.data();
    }

}// namespace

extern "C" {

EMSCRIPTEN_KEEPALIVE CHelper::CHelperCore *init(const char *cpackPtr, size_t cpackLength) {
    return CHelper::CHelperCore::create([&cpackPtr, &cpackLength]() -> std::unique_ptr<CHelper::CPack> {
        std::string str = std::string(cpackPtr, cpackLength);
        std::istringstream iss(str);
        return CHelper::CPack::createByBinary(iss);
    });
}

EMSCRIPTEN_KEEPALIVE void release(const CHelper::CHelperCore *core) {
    delete core;
}

// 和CHelperCore不同，CommandContext没有可变状态，
// 所有操作都由调用方传入位置参数，因此可以把同一个CommandContext
// 交给多个线程同时读取，也可以创建多个CommandContext并行工作

EMSCRIPTEN_KEEPALIVE CHelper::CommandContext *createCommandContext(const CHelper::CHelperCore *core, const char16_t *command) {
    if (core == nullptr) [[unlikely]] {
        return nullptr;
    }
    try {
        return core->createContext(command);
    } catch (...) {
        return nullptr;
    }
}

EMSCRIPTEN_KEEPALIVE void releaseCommandContext(CHelper::CommandContext *context) {
    CHelper::CHelperCore::deleteContext(context);
}

EMSCRIPTEN_KEEPALIVE const uint8_t *contextGetCommand(const CHelper::CommandContext *context) {
    if (context == nullptr) [[unlikely]] {
        return nullptr;
    }
    return writeU16String(context->getCommand());
}

EMSCRIPTEN_KEEPALIVE const uint8_t *contextGetStructure(const CHelper::CommandContext *context) {
    if (context == nullptr) [[unlikely]] {
        return nullptr;
    }
    return writeU16String(context->getStructure());
}

EMSCRIPTEN_KEEPALIVE const uint8_t *contextGetParamHint(const CHelper::CommandContext *context, size_t index) {
    if (context == nullptr) [[unlikely]] {
        return nullptr;
    }
    return writeU16String(context->getParamHint(index));
}

EMSCRIPTEN_KEEPALIVE const uint8_t *contextGetErrorReasons(const CHelper::CommandContext *context) {
    if (context == nullptr) [[unlikely]] {
        return nullptr;
    }
    return writeErrorReasons(context->getErrorReasons());
}

EMSCRIPTEN_KEEPALIVE size_t contextGetSuggestionSize(const CHelper::CommandContext *context, size_t index) {
    if (context == nullptr) [[unlikely]] {
        return 0;
    }
    return context->getSuggestions(index).size();
}

EMSCRIPTEN_KEEPALIVE const uint8_t *contextGetSuggestion(const CHelper::CommandContext *context, size_t index, size_t which) {
    if (context == nullptr) [[unlikely]] {
        return nullptr;
    }
    std::vector<CHelper::AutoSuggestion::Suggestion> suggestions = context->getSuggestions(index);
    if (which >= suggestions.size()) {
        return nullptr;
    }
    buffer.resize((reinterpret_cast<size_t>(buffer.data()) % 4) + getSuggestionBytes(suggestions[which]));
    writeSuggestion(alignPointer(buffer.data()), suggestions[which]);
    return buffer.data();
}

EMSCRIPTEN_KEEPALIVE const uint8_t *contextGetAllSuggestions(const CHelper::CommandContext *context, size_t index) {
    if (context == nullptr) [[unlikely]] {
        return nullptr;
    }
    return writeSuggestions(context->getSuggestions(index));
}

EMSCRIPTEN_KEEPALIVE const uint8_t *contextApplySuggestion(const CHelper::CommandContext *context, size_t index, size_t which) {
    if (context == nullptr) [[unlikely]] {
        return nullptr;
    }
    auto result = context->applySuggestion(index, which);
    if (!result.has_value()) {
        return nullptr;
    }
    return writeSuggestionClickResult(result.value());
}

EMSCRIPTEN_KEEPALIVE const uint8_t *contextGetSyntaxTokens(const CHelper::CommandContext *context) {
    if (context == nullptr) [[unlikely]] {
        return nullptr;
    }
    return writeSyntaxTokens(context->getSyntaxResult().tokenTypes);
}

EMSCRIPTEN_KEEPALIVE size_t contextGetNodeCount(const CHelper::CommandContext *context) {
    if (context == nullptr) [[unlikely]] {
        return 0;
    }
    return context->getNodeCount();
}
}
