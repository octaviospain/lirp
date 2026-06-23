/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
 *                                                                            *
 *     This program is free software: you can redistribute it and/or modify   *
 *     it under the terms of the GNU General Public License as published by   *
 *     the Free Software Foundation, either version 3 of the License, or      *
 *     (at your option) any later version.                                    *
 *                                                                            *
 *     This program is distributed in the hope that it will be useful,        *
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of         *
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
 *     GNU General Public License for more details.                           *
 *                                                                            *
 *     You should have received a copy of the GNU General Public License      *
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>. *
 ******************************************************************************/

package net.transgressoft.lirp.ksp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the polymorphic aggregate arm regex and parsing utilities
 * ([buildArmRegex], [armScalarFromPath], [isValidArmLabel]).
 */
class PolymorphicArmParsingTest : StringSpec({

    val armRegex = buildArmRegex()

    "buildArmRegex matches arm with simple types and label" {
        val text = """arm<K, E>("myLabel") { scalar }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[1] shouldBe "K"
        match.groupValues[2] shouldBe "E"
        match.groupValues[3] shouldBe "myLabel"
        match.groupValues[4] shouldBe ""
        match.groupValues[5] shouldBe "scalar"
    }

    "buildArmRegex matches arm with dotted type parameters" {
        val text = """arm<java.util.UUID, com.example.Item>("itemRef") { itemId }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[1] shouldBe "java.util.UUID"
        match.groupValues[2] shouldBe "com.example.Item"
        match.groupValues[3] shouldBe "itemRef"
        match.groupValues[5] shouldBe "itemId"
    }

    "buildArmRegex matches arm with positional CascadeAction" {
        val text = """arm<String, Item>("tag", CascadeAction.CASCADE) { tagName }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[3] shouldBe "tag"
        match.groupValues[4] shouldBe "CASCADE"
        match.groupValues[5] shouldBe "tagName"
    }

    "buildArmRegex matches arm with named onDelete CascadeAction" {
        val text = """arm<UUID, Playlist>("holder", onDelete = CascadeAction.RESTRICT) { holder }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[3] shouldBe "holder"
        match.groupValues[4] shouldBe "RESTRICT"
        match.groupValues[5] shouldBe "holder"
    }

    "buildArmRegex matches arm with this-prefixed scalar" {
        val text = """arm<Int, Widget>("ref") { this.widgetId }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[5] shouldBe "this.widgetId"
    }

    "buildArmRegex matches arm with dotted scalar path" {
        val text = """arm<String, Config>("prop") { holder.configValue }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[5] shouldBe "holder.configValue"
    }

    "buildArmRegex tolerates whitespace variations" {
        val text = """arm   <   K   ,   E   >   (   "label"   )   {   scalar   }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[1] shouldBe "K"
        match.groupValues[2] shouldBe "E"
        match.groupValues[3] shouldBe "label"
        match.groupValues[5] shouldBe "scalar"
    }

    "buildArmRegex captures empty CascadeAction group when not present" {
        val text = """arm<K, E>("label") { scalar }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[4] shouldBe ""
    }

    "buildArmRegex matches arm with SET_NULL cascade" {
        val text = """arm<UUID, Parent>("child", CascadeAction.SET_NULL) { childId }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[4] shouldBe "SET_NULL"
    }

    "buildArmRegex matches arm with DETACH cascade" {
        val text = """arm<Int, Orphan>("ref", onDelete = CascadeAction.DETACH) { refId }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[4] shouldBe "DETACH"
    }

    "buildArmRegex matches arm with label containing hyphens and underscores" {
        val text = """arm<K, E>("my_label-123") { scalar }"""
        val match = armRegex.find(text)
        match.shouldNotBeNull()
        match.groupValues[3] shouldBe "my_label-123"
    }

    "armScalarFromPath strips this prefix" {
        armScalarFromPath("this.audioItemId") shouldBe "audioItemId"
    }

    "armScalarFromPath extracts last segment from dotted path" {
        armScalarFromPath("holder.config.itemId") shouldBe "itemId"
    }

    "armScalarFromPath returns single identifier unchanged" {
        armScalarFromPath("scalar") shouldBe "scalar"
    }

    "armScalarFromPath strips this and extracts last segment" {
        armScalarFromPath("this.holder.itemId") shouldBe "itemId"
    }

    "isValidArmLabel accepts simple identifier" {
        isValidArmLabel("myLabel") shouldBe true
    }

    "isValidArmLabel accepts identifier with underscores" {
        isValidArmLabel("_my_label_123") shouldBe true
    }

    "isValidArmLabel accepts identifier starting with underscore" {
        isValidArmLabel("_private") shouldBe true
    }

    "isValidArmLabel rejects identifier with hyphens" {
        isValidArmLabel("my-label") shouldBe false
    }

    "isValidArmLabel rejects identifier starting with digit" {
        isValidArmLabel("123label") shouldBe false
    }

    "isValidArmLabel rejects empty string" {
        isValidArmLabel("") shouldBe false
    }

    "isValidArmLabel rejects identifier with spaces" {
        isValidArmLabel("my label") shouldBe false
    }

    "buildArmRegex does not match arm without type parameters" {
        val text = """arm("label") { scalar }"""
        val match = armRegex.find(text)
        match shouldBe null
    }

    "buildArmRegex does not match arm without quoted label" {
        val text = """arm<K, E>(label) { scalar }"""
        val match = armRegex.find(text)
        match shouldBe null
    }

    "buildArmRegex does not match incomplete arm call" {
        val text = """arm<K, E>("label"""
        val match = armRegex.find(text)
        match shouldBe null
    }

    "buildArmRegex matches multiple arms in sequence" {
        val text = """
            arm<K1, E1>("first") { firstScalar }
            arm<K2, E2>("second") { secondScalar }
        """
        val matches = armRegex.findAll(text).toList()
        matches.size shouldBe 2
        matches[0].groupValues[3] shouldBe "first"
        matches[1].groupValues[3] shouldBe "second"
    }
})