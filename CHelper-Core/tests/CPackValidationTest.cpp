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

#include "CpackTestHelper.h"
#include <gtest/gtest.h>

namespace CHelper::Test {

    //非法CPack数据必须在加载/初始化阶段fail-fast，不能进入Parser导致崩溃或未定义行为

    TEST(CPackValidationTest, ValidMinimalCpack) {
        std::unique_ptr<CPack> cpack;
        EXPECT_TRUE(tryCreateCpack(makeCpackJson("[]", "[]", R"([
            {"name": ["list"], "description": "list command", "syntax": ["/list"], "node": {}}
          ])"),
                                   cpack));
        ASSERT_NE(cpack, nullptr);
        //合法的CPack必须能正常解析命令
        CommandContext context(std::shared_ptr<const CPack>(std::move(cpack)), u"list");
        EXPECT_TRUE(context.getErrorReasons().empty());
    }

    TEST(CPackValidationTest, EmptyCommandName) {
        expectCpackRejected(makeCpackJson("[]", "[]", R"([
            {"name": [], "description": "no name", "syntax": ["/list"], "node": {}}
          ])"));
    }

    TEST(CPackValidationTest, EmptySyntax) {
        //syntax为空会导致命令没有任何start node，解析时会产生空OR节点
        expectCpackRejected(makeCpackJson("[]", "[]", R"([
            {"name": ["list"], "description": "no syntax", "syntax": [], "node": {}}
          ])"));
    }

    TEST(CPackValidationTest, UnknownSyntaxToken) {
        expectCpackRejected(makeCpackJson("[]", "[]", R"([
            {"name": ["list"], "description": "unknown token", "syntax": ["/list <arg: missing>"], "node": {}}
          ])"));
    }

    TEST(CPackValidationTest, UnknownJsonKey) {
        expectCpackRejected(makeCpackJson("[]", "[]", R"([
            {"name": ["cmd"], "description": "json command", "syntax": ["/cmd <v: json>"],
             "node": {"<v: json>": {"type": "JSON", "key": "nonexistent"}}}
          ])"));
    }

    TEST(CPackValidationTest, InvalidStartNode) {
        //start指向不存在的node id，必须在初始化阶段报错，否则Parser会使用data为nullptr的节点
        expectCpackRejected(makeCpackJson(R"([
            {"id": "broken", "start": "NONEXISTENT", "node": [
              {"type": "JSON_NULL", "id": "N", "description": "null"}
            ]}
          ])",
                                          "[]", R"([
            {"name": ["cmd"], "description": "json command", "syntax": ["/cmd <v: json>"],
             "node": {"<v: json>": {"type": "JSON", "key": "broken"}}}
          ])"));
    }

    TEST(CPackValidationTest, EmptyJsonEntryValue) {
        //value为空会产生childNodes为空的OR节点
        expectCpackRejected(makeCpackJson(R"([
            {"id": "broken", "start": "P", "node": [
              {"type": "JSON_OBJECT", "id": "P", "description": "object", "data": [
                {"key": "s", "description": "empty value", "value": []}
              ]}
            ]}
          ])",
                                          "[]", R"([
            {"name": ["cmd"], "description": "json command", "syntax": ["/cmd <v: json>"],
             "node": {"<v: json>": {"type": "JSON", "key": "broken"}}}
          ])"));
    }

    TEST(CPackValidationTest, UnknownJsonEntryValueId) {
        expectCpackRejected(makeCpackJson(R"([
            {"id": "broken", "start": "P", "node": [
              {"type": "JSON_OBJECT", "id": "P", "description": "object", "data": [
                {"key": "s", "description": "unknown value", "value": ["NONEXISTENT"]}
              ]}
            ]}
          ])",
                                          "[]", R"([
            {"name": ["cmd"], "description": "json command", "syntax": ["/cmd <v: json>"],
             "node": {"<v: json>": {"type": "JSON", "key": "broken"}}}
          ])"));
    }

    TEST(CPackValidationTest, UnknownJsonListElementId) {
        expectCpackRejected(makeCpackJson(R"([
            {"id": "broken", "start": "P", "node": [
              {"type": "JSON_LIST", "id": "P", "description": "list", "data": "NONEXISTENT"}
            ]}
          ])",
                                          "[]", R"([
            {"name": ["cmd"], "description": "json command", "syntax": ["/cmd <v: json>"],
             "node": {"<v: json>": {"type": "JSON", "key": "broken"}}}
          ])"));
    }

    TEST(CPackValidationTest, EmptyRepeatNodes) {
        //repeatNodes为空会产生childNodes为空的OR节点
        expectCpackRejected(makeCpackJson("[]", R"([
            {"id": "broken", "breakNodes": [], "isEnd": [], "repeatNodes": []}
          ])",
                                          R"([
            {"name": ["cmd"], "description": "repeat command", "syntax": ["/cmd <r: repeat>"],
             "node": {"<r: repeat>": {"type": "REPEAT", "key": "broken"}}}
          ])"));
    }

    TEST(CPackValidationTest, RepeatNodesSizeMismatch) {
        expectCpackRejected(makeCpackJson("[]", R"([
            {"id": "broken", "breakNodes": [], "isEnd": [true], "repeatNodes": []}
          ])",
                                          R"([
            {"name": ["cmd"], "description": "repeat command", "syntax": ["/cmd <r: repeat>"],
             "node": {"<r: repeat>": {"type": "REPEAT", "key": "broken"}}}
          ])"));
    }

    TEST(CPackValidationTest, UnknownRepeatKey) {
        expectCpackRejected(makeCpackJson("[]", "[]", R"([
            {"name": ["cmd"], "description": "repeat command", "syntax": ["/cmd <r: repeat>"],
             "node": {"<r: repeat>": {"type": "REPEAT", "key": "nonexistent"}}}
          ])"));
    }

}// namespace CHelper::Test
