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

#include <chelper/extension/Composer.h>
#include <chelper/serialization/Serialization.h>
#include <chelper/util/JsonUtil.h>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <optional>
#include <stdexcept>

namespace CHelper::Extension {

    namespace {

        using JsonDoc = rapidjson::GenericDocument<rapidjson::UTF8<>>;
        using JsonValue = rapidjson::GenericValue<rapidjson::UTF8<>>;

        std::string normalizeRel(std::string rel) {
            for (char &ch: rel) {
                if (ch == '\\') {
                    ch = '/';
                }
            }
            while (!rel.empty() && rel.front() == '/') {
                rel.erase(rel.begin());
            }
            return rel;
        }

        bool parseDoc(const std::vector<uint8_t> &bytes, JsonDoc &doc) {
            if (bytes.empty()) {
                return false;
            }
            return JsonUtil::parseJsonWithComments(doc, reinterpret_cast<const char *>(bytes.data()), bytes.size()) && doc.IsObject();
        }

        // ASCII 大小写不敏感前缀匹配（目录书写允许 ID/ 或 id/）
        bool startsWithIgnoreCase(const std::string &s, const char *prefix) {
            const size_t n = std::strlen(prefix);
            if (s.size() < n) {
                return false;
            }
            for (size_t i = 0; i < n; ++i) {
                if (std::tolower(static_cast<unsigned char>(s[i])) != std::tolower(static_cast<unsigned char>(prefix[i]))) {
                    return false;
                }
            }
            return true;
        }

        std::string getString(const JsonValue &v, const char *key) {
            auto it = v.FindMember(key);
            return (it != v.MemberEnd() && it->value.IsString()) ? it->value.GetString() : std::string();
        }

        // 收集命令文件里的 name 别名
        std::vector<std::string> commandNamesOf(const JsonDoc &doc) {
            std::vector<std::string> names;
            auto it = doc.FindMember("name");
            if (it != doc.MemberEnd() && it->value.IsArray()) {
                for (const auto &v: it->value.GetArray()) {
                    if (v.IsString()) {
                        names.emplace_back(v.GetString());
                    }
                }
            }
            return names;
        }

        // 方块/物品大表合并（条目级，先到先得）：
        // - blockStateValues / item 条目：与已有（同命名空间 + 同名）条目冲突 → 忽略并记告警；
        //   新条目追加（打来源标记，供 Suggestion.packName 徽标）。
        // - blockPropertyDescriptions：按"方块集相交 + 属性名相同"先到先得，
        //   冲突属性忽略；其余按组追加。描述缺失的兜底与装配期约束见 composer.md §3.3。
        void appendBigIdFile(CPackBuilder &builder, const JsonValue &doc, const std::string &type,
                             const std::u16string &packName, std::vector<std::string> &warnings) {
            auto nsOf = [](const std::shared_ptr<NamespaceId> &item) -> const std::u16string & {
                if (item->idNamespace.has_value()) [[unlikely]] {
                    return item->idNamespace.value();
                }
                // 主包条目没有 idNamespace 即视为默认命名空间（与 CPack::getNamespaceId/匹配逻辑一致）
                static const std::u16string defaultNs = u"minecraft";
                return defaultNs;
            };
            auto sameEntry = [&nsOf](const std::shared_ptr<NamespaceId> &a, const std::shared_ptr<NamespaceId> &b) {
                return a->name == b->name && nsOf(a) == nsOf(b);
            };
            auto warnDup = [&warnings, &packName, &type](const NamespaceId &item) {
                std::u16string full = type == "block" ? u"block " : u"item ";
                if (item.idNamespace.has_value()) {
                    full += item.idNamespace.value() + u":";
                }
                full += item.name;
                warnings.emplace_back("拓展包 " + utf8::utf16to8(packName) + " 的 " + utf8::utf16to8(full) +
                                      " 与已有条目重复，已忽略（先到先得）");
            };

            if (type == "block") {
                BlockIds ext;
                serialization::Codec<BlockIds>::template from_json_member<JsonValue>(doc, "content", ext);
                if (!builder.blockIds) {
                    builder.blockIds = std::make_shared<BlockIds>();
                }
                BlockIds &target = *builder.blockIds;
                if (!target.blockStateValues) {
                    target.blockStateValues = std::make_shared<std::vector<std::shared_ptr<BlockId>>>();
                }
                if (ext.blockStateValues) {
                    for (auto &entry: *ext.blockStateValues) {
                        const auto &dup = std::ranges::find_if(*target.blockStateValues, [&](const auto &e) {
                            return sameEntry(e, entry);
                        });
                        if (dup != target.blockStateValues->end()) {
                            warnDup(*entry);
                            continue;
                        }
                        entry->packName = packName;
                        target.blockStateValues->push_back(std::move(entry));
                    }
                }
                // blockPropertyDescriptions.common：按属性名先到先得
                for (auto &p: ext.blockPropertyDescriptions.common) {
                    const auto &dup = std::ranges::find_if(target.blockPropertyDescriptions.common,
                                                           [&p](const BlockPropertyDescription &x) {
                                                               return x.propertyName == p.propertyName;
                                                           });
                    if (dup != target.blockPropertyDescriptions.common.end()) {
                        warnings.emplace_back("拓展包 " + utf8::utf16to8(packName) +
                                              " 的公共属性描述 " + utf8::utf16to8(p.propertyName) +
                                              " 已存在，已忽略（先到先得）");
                        continue;
                    }
                    target.blockPropertyDescriptions.common.push_back(std::move(p));
                }
                // blockPropertyDescriptions.block：按"方块集相交且属性名相同"先到先得
                for (auto &g: ext.blockPropertyDescriptions.block) {
                    PerBlockPropertyDescription kept;
                    kept.blocks = std::move(g.blocks);
                    for (auto &p: g.properties) {
                        bool collide = false;
                        for (const auto &g2: target.blockPropertyDescriptions.block) {
                            bool intersect = false;
                            for (const auto &b: g2.blocks) {
                                if (std::ranges::find(kept.blocks, b) != kept.blocks.end()) {
                                    intersect = true;
                                    break;
                                }
                            }
                            if (!intersect) {
                                continue;
                            }
                            if (std::ranges::find_if(g2.properties, [&p](const BlockPropertyDescription &x) {
                                    return x.propertyName == p.propertyName;
                                }) != g2.properties.end()) {
                                collide = true;
                                break;
                            }
                        }
                        if (collide) {
                            warnings.emplace_back("拓展包 " + utf8::utf16to8(packName) +
                                                  " 的属性描述 " + utf8::utf16to8(p.propertyName) +
                                                  " 已存在，已忽略（先到先得）");
                            continue;
                        }
                        kept.properties.push_back(std::move(p));
                    }
                    if (!kept.properties.empty()) {
                        target.blockPropertyDescriptions.block.push_back(std::move(kept));
                    }
                }
            } else {
                std::shared_ptr<std::vector<std::shared_ptr<ItemId>>> ext;
                serialization::Codec<decltype(ext)>::template from_json_member<JsonValue>(doc, "content", ext);
                if (!ext) {
                    return;
                }
                if (!builder.itemIds) {
                    builder.itemIds = std::make_shared<std::vector<std::shared_ptr<ItemId>>>();
                }
                for (auto &entry: *ext) {
                    const auto &dup = std::ranges::find_if(*builder.itemIds, [&](const auto &e) {
                        return sameEntry(e, entry);
                    });
                    if (dup != builder.itemIds->end()) {
                        warnDup(*entry);
                        continue;
                    }
                    entry->packName = packName;
                    builder.itemIds->push_back(std::move(entry));
                }
            }
        }

        // selector/*.json V1（数据化）：内置变量/参数名镜像（与 CommandNode.cpp 默认表保持同步，
        // 仅用于合成期冲突校验——内置保留变量整包拒绝、内置参数同名先到先得忽略）
        constexpr std::u16string_view builtinSelectorVariables[] = {
                u"@e", u"@a", u"@r", u"@p", u"@s", u"@n", u"@initiator"};
        constexpr std::u16string_view builtinSelectorArguments[] = {
                u"x", u"y", u"z", u"r", u"rm", u"dx", u"dy", u"dz", u"scores", u"tag", u"name",
                u"type", u"family", u"rx", u"rxm", u"ry", u"rym", u"hasitem", u"haspermission",
                u"has_property", u"l", u"lm", u"m", u"c"};
        constexpr std::u16string_view supportedSelectorValueTypes[] = {
                u"BOOLEAN", u"INTEGER", u"FLOAT", u"RELATIVE_FLOAT", u"STRING", u"RANGE",
                u"NORMAL_ID", u"NAMESPACE_ID"};

        struct LoadedSelector {
            std::string id;
            std::vector<Node::SelectorPackVariable> variables;
            std::vector<Node::SelectorPackArgument> arguments;
        };

        // 解析单个 selector/*.json 并做结构校验（内容不合法 → 整包拒绝）
        LoadedSelector parseSelectorFile(const std::vector<uint8_t> &bytes, const std::string &rel,
                                         std::vector<std::string> &warnings) {
            JsonDoc doc;
            if (!parseDoc(bytes, doc)) {
                throw std::runtime_error("invalid selector file: " + rel);
            }
            LoadedSelector out;
            const std::string id = getString(doc, "id");
            if (id.empty()) {
                throw std::runtime_error("selector file missing id: " + rel);
            }
            out.id = id;

            auto it = doc.FindMember("variables");
            if (it != doc.MemberEnd() && it->value.IsArray()) {
                for (const auto &v: it->value.GetArray()) {
                    if (!v.IsObject()) {
                        throw std::runtime_error("invalid selector variable in " + rel);
                    }
                    const std::string name = getString(v, "name");
                    if (name.empty() || name[0] != '@') {
                        throw std::runtime_error("selector variable name must start with '@': " + rel);
                    }
                    Node::SelectorPackVariable variable;
                    variable.name = utf8::utf8to16(name);
                    const std::string description = getString(v, "description");
                    if (!description.empty()) {
                        variable.description = utf8::utf8to16(description);
                    }
                    // 白名单参数目前不生效（V1 子集：自定义变量与内置变量共用全局参数表），仅告警
                    auto argIt = v.FindMember("arguments");
                    if (argIt != v.MemberEnd() && argIt->value.IsArray() && !argIt->value.GetArray().Empty()) {
                        warnings.emplace_back("selector " + rel + " 变量 " + name + " 的参数白名单暂不生效（V1 子集），已忽略");
                    }
                    out.variables.push_back(std::move(variable));
                }
            }

            auto argDefs = doc.FindMember("arguments");
            if (argDefs != doc.MemberEnd() && argDefs->value.IsArray()) {
                for (const auto &a: argDefs->value.GetArray()) {
                    if (!a.IsObject()) {
                        throw std::runtime_error("invalid selector argument in " + rel);
                    }
                    Node::SelectorPackArgument argument;
                    const std::string name = getString(a, "name");
                    if (name.empty()) {
                        throw std::runtime_error("selector argument missing name in " + rel);
                    }
                    argument.name = utf8::utf8to16(name);
                    const std::string description = getString(a, "description");
                    if (description.empty()) {
                        throw std::runtime_error("selector argument missing description in " + rel + " (" + name + ")");
                    }
                    argument.description = utf8::utf8to16(description);
                    const std::string op = getString(a, "operator");
                    argument.canUseNotEqual = op.find('!') != std::string::npos;
                    const std::string vt = getString(a, "valueType");
                    if (vt.empty()) {
                        throw std::runtime_error("selector argument missing valueType in " + rel + " (" + name + ")");
                    }
                    bool knownType = false;
                    for (const auto &t: supportedSelectorValueTypes) {
                        if (utf8::utf16to8(std::u16string(t)) == vt) {
                            knownType = true;
                            break;
                        }
                    }
                    if (!knownType) {
                        throw std::runtime_error("selector argument unsupported valueType '" + vt + "' in " + rel + " (" + name + ")");
                    }
                    argument.valueType = vt;
                    if (vt == "NORMAL_ID" || vt == "NAMESPACE_ID") {
                        const std::string key = getString(a, "key");
                        if (key.empty()) {
                            throw std::runtime_error("selector argument missing key in " + rel + " (" + name + ")");
                        }
                        argument.key = key;
                        if (a.HasMember("contents")) {
                            warnings.emplace_back(
                                    "selector " + rel + " 参数 " + name + " 的 contents 暂不支持（V1 子集仅支持 key），已忽略");
                        }
                    }
                    out.arguments.push_back(std::move(argument));
                }
            }
            return out;
        }

        // 汇总多个 selector 文件到 builder：同 id 后文件覆盖前文件；变量/参数名冲突先到先得
        void flattenSelectors(CPackBuilder &builder, std::vector<LoadedSelector> &files,
                              std::vector<std::string> &warnings) {
            std::unordered_map<std::string, size_t> lastById;
            for (size_t i = 0; i < files.size(); ++i) {
                lastById[files[i].id] = i;
            }
            std::vector<LoadedSelector> merged;
            for (size_t i = 0; i < files.size(); ++i) {
                if (lastById[files[i].id] == i) {
                    merged.push_back(std::move(files[i]));
                }
            }
            for (auto &file: merged) {
                for (auto &v: file.variables) {
                    bool reserved = false;
                    for (const auto &b: builtinSelectorVariables) {
                        if (v.name == b) {
                            reserved = true;
                            break;
                        }
                    }
                    if (reserved) {
                        throw std::runtime_error("selector variable " + utf8::utf16to8(v.name) +
                                                 " conflicts with builtin selector variable (整包拒绝)");
                    }
                    bool dup = false;
                    for (const auto &x: builder.selectorVariables) {
                        if (x.name == v.name) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        builder.selectorVariables.push_back(std::move(v));
                    }
                }
            }
            for (auto &file: merged) {
                for (auto &a: file.arguments) {
                    bool reserved = false;
                    for (const auto &b: builtinSelectorArguments) {
                        if (a.name == b) {
                            reserved = true;
                            break;
                        }
                    }
                    if (reserved) {
                        warnings.emplace_back("selector 参数 " + utf8::utf16to8(a.name) + " 与内置参数同名，已忽略（先到先得）");
                        continue;
                    }
                    bool dup = false;
                    for (const auto &x: builder.selectorArguments) {
                        if (x.name == a.name) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        builder.selectorArguments.push_back(std::move(a));
                    }
                }
            }
        }

        // id 表追加（normal/namespace）；block/item 条目级合并（见上）
        void appendIdFile(CPackBuilder &builder, const std::vector<uint8_t> &bytes,
                          const std::u16string &packName, std::vector<std::string> &warnings) {
            JsonDoc doc;
            if (!parseDoc(bytes, doc)) {
                throw std::runtime_error("invalid id file");
            }
            const std::string type = getString(doc, "type");
            const std::string id = getString(doc, "id");
            if (type == "normal") {
                std::shared_ptr<std::vector<std::shared_ptr<NormalId>>> content;
                serialization::Codec<decltype(content)>::template from_json_member<JsonValue>(doc, "content", content);
                for (auto &item: *content) {
                    item->packName = packName;
                }
                auto existing = builder.normalIds.find(id);
                if (existing == builder.normalIds.end()) {
                    builder.normalIds.emplace(id, std::move(content));
                } else {
                    existing->second->insert(existing->second->end(), content->begin(), content->end());
                }
            } else if (type == "namespace") {
                std::shared_ptr<std::vector<std::shared_ptr<NamespaceId>>> content;
                serialization::Codec<decltype(content)>::template from_json_member<JsonValue>(doc, "content", content);
                for (auto &item: *content) {
                    item->packName = packName;
                }
                auto existing = builder.namespaceIds.find(id);
                if (existing == builder.namespaceIds.end()) {
                    builder.namespaceIds.emplace(id, std::move(content));
                } else {
                    existing->second->insert(existing->second->end(), content->begin(), content->end());
                }
            } else if (type == "block" || type == "item") {
                appendBigIdFile(builder, doc, type, packName, warnings);
            } else {
                throw std::runtime_error("unknown id type: " + type);
            }
        }

        // execute 片段：把 branches 追加进对应 RepeatData 的 repeatNodes/isEnd
        void applyExecuteFragment(CPackBuilder &builder, const std::vector<uint8_t> &bytes, std::vector<std::string> &warnings) {
            JsonDoc doc;
            if (!parseDoc(bytes, doc)) {
                throw std::runtime_error("invalid extension fragment");
            }
            auto nodeIt = doc.FindMember("node");
            if (nodeIt == doc.MemberEnd() || !nodeIt->value.IsObject()) {
                throw std::runtime_error("fragment missing node");
            }
            for (auto it = nodeIt->value.MemberBegin(); it != nodeIt->value.MemberEnd(); ++it) {
                const JsonValue &v = it->value;
                if (getString(v, "type") != "REPEAT" || !v.HasMember("branches")) {
                    continue;
                }
                const std::string key = getString(v, "key");
                Node::RepeatData *target = nullptr;
                for (auto &rd: builder.repeatNodeData) {
                    if (rd.id == key) {
                        target = &rd;
                        break;
                    }
                }
                if (target == nullptr) {
                    throw std::runtime_error("fragment target repeat not found: " + key);
                }
                const JsonValue &branches = v["branches"];
                if (!branches.IsArray()) {
                    throw std::runtime_error("fragment branches must be array");
                }
                const Node::NodeCreateStage::NodeCreateStage prev = currentCreateStage;
                currentCreateStage = Node::NodeCreateStage::COMMAND_PARAM_NODE;
                for (const auto &b: branches.GetArray()) {
                    Node::FreeableNodeWithTypes branchNodes;
                    serialization::Codec<decltype(branchNodes)>::template from_json<JsonValue>(b["nodes"], branchNodes);
                    const bool isEnd = b.HasMember("isEnd") && b["isEnd"].IsBool() ? b["isEnd"].GetBool() : true;
                    target->repeatNodes.push_back(std::move(branchNodes));
                    target->isEnd.push_back(isEnd);
                }
                currentCreateStage = prev;
            }
        }

        // text/translate.json：翻译键表（对象 {键: 中文}）→ normalIds["translate"]，供翻译键片段补全
        void loadTranslateTable(CPackBuilder &builder, const std::vector<uint8_t> &bytes) {
            JsonDoc doc;
            if (!parseDoc(bytes, doc) || !doc.IsObject()) {
                throw std::runtime_error("invalid text/translate.json");
            }
            auto table = std::make_shared<std::vector<std::shared_ptr<NormalId>>>();
            table->reserve(doc.MemberCount());
            for (auto it = doc.MemberBegin(); it != doc.MemberEnd(); ++it) {
                const std::string key(it->name.GetString(), it->name.GetStringLength());
                std::optional<std::u16string> zh;
                if (it->value.IsString()) {
                    zh = utf8::utf8to16(std::string(it->value.GetString(), it->value.GetStringLength()));
                }
                table->push_back(NormalId::make(utf8::utf8to16(key), zh));
            }
            builder.normalIds.emplace("translate", std::move(table));
        }

    }// namespace

    ComposeResult compose(const SegmentData &segment,
                          const std::vector<ExtensionPackData> &packs,
                          const ComposeOptions &options) {
        (void) options;
        ComposeResult result;

        CPackBuilder builder;
        // 1) 主包段（text/translate.json 特殊处理为翻译键表，其余按文件装载）
        for (const auto &f: segment.files) {
            if (!f.bytes) {
                continue;
            }
            const std::string rel = normalizeRel(f.relPath);
            if (rel == "text/translate.json") {
                loadTranslateTable(builder, *f.bytes);
            } else {
                builder.applyFile(rel, *f.bytes);
            }
        }

        // 已注册命令别名（主包段优先）
        std::unordered_set<std::string> commandNames;
        for (const auto &cmd: builder.commands) {
            for (const auto &name: cmd.name) {
                const std::string nameUtf8 = utf8::utf16to8(name);
                commandNames.insert(nameUtf8);
                result.commandSources[nameUtf8] = u""; // 空 = 内置
            }
        }

        // 2) 拓展包（按传入顺序，先到先得）
        std::vector<LoadedSelector> selectorFiles; // selector/*.json（同 id 后装载覆盖先装载）
        for (const auto &pack: packs) {
            // 解析拓展包 manifest 名称（用于来源标注）
            std::u16string packName;
            for (const auto &f: pack.files) {
                if (normalizeRel(f.relPath) == "manifest.json" && f.bytes) {
                    JsonDoc m;
                    if (parseDoc(*f.bytes, m)) {
                        packName = utf8::utf8to16(getString(m, "name"));
                    }
                    break;
                }
            }
            for (const auto &f: pack.files) {
                if (!f.bytes) {
                    continue;
                }
                const std::string rel = normalizeRel(f.relPath);
                if (rel == "manifest.json") {
                    continue; // 拓展包 manifest 不并入 CPack（来源元数据已读）
                }
                if (startsWithIgnoreCase(rel, "command/")) {
                    JsonDoc doc;
                    if (!parseDoc(*f.bytes, doc)) {
                        throw std::runtime_error("invalid command file: " + rel);
                    }
                    const auto names = commandNamesOf(doc);
                    bool conflict = false;
                    for (const auto &n: names) {
                        if (commandNames.contains(n)) {
                            conflict = true;
                            break;
                        }
                    }
                    if (conflict) {
                        result.overriddenCommands.insert(result.overriddenCommands.end(), names.begin(), names.end());
                        continue;
                    }
                    builder.applyFile(rel, *f.bytes);
                    for (const auto &n: names) {
                        commandNames.insert(n);
                        result.commandSources[n] = packName;
                        if (!packName.empty()) {
                            builder.commandNameSources[utf8::utf8to16(n)] = packName;
                        }
                    }
                } else if (startsWithIgnoreCase(rel, "id/")) {
                    appendIdFile(builder, *f.bytes, packName, result.warnings);
                } else if (startsWithIgnoreCase(rel, "json/")) {
                    builder.applyFile(rel, *f.bytes);
                } else if (startsWithIgnoreCase(rel, "extensions/")) {
                    applyExecuteFragment(builder, *f.bytes, result.warnings);
                } else if (startsWithIgnoreCase(rel, "selector/")) {
                    selectorFiles.push_back(parseSelectorFile(*f.bytes, rel, result.warnings));
                }
                // 其它（text/ 等数据文件）忽略
            }
        }
        // selector/*.json 汇总（同 id 覆盖 + 与内置变量/参数的冲突校验在 flatten 内完成）
        flattenSelectors(builder, selectorFiles, result.warnings);

        result.cpack = builder.build();
        return result;
    }

}// namespace CHelper::Extension
