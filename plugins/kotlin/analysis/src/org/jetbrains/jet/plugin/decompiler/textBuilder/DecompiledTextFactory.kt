/*
 * Copyright 2010-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.plugin.decompiler.textBuilder

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jet.lang.descriptors.*
import org.jetbrains.jet.lang.resolve.MemberComparator
import org.jetbrains.jet.lang.resolve.kotlin.KotlinBinaryClassCache
import org.jetbrains.jet.lang.resolve.kotlin.header.KotlinClassHeader
import org.jetbrains.jet.lang.resolve.name.FqName
import org.jetbrains.jet.renderer.DescriptorRenderer
import org.jetbrains.jet.renderer.DescriptorRendererBuilder
import java.util.*
import org.jetbrains.jet.lang.resolve.DescriptorUtils.isEnumEntry
import org.jetbrains.jet.lang.resolve.DescriptorUtils.isSyntheticClassObject
import org.jetbrains.jet.lang.types.error.MissingDependencyErrorClass
import org.jetbrains.jet.lang.resolve.dataClassUtils.isComponentLike
import org.jetbrains.jet.plugin.util.IdeDescriptorRenderers
import org.jetbrains.jet.lang.types.isFlexible
import org.jetbrains.jet.lang.resolve.java.JvmAbi
import org.jetbrains.jet.lang.resolve.kotlin.header.isCompatiblePackageFacadeKind
import org.jetbrains.jet.lang.resolve.kotlin.header.isCompatibleClassKind

private val FILE_ABI_VERSION_MARKER: String = "FILE_ABI"
private val CURRENT_ABI_VERSION_MARKER: String = "CURRENT_ABI"

public val INCOMPATIBLE_ABI_VERSION_GENERAL_COMMENT: String = "// This class file was compiled with different version of Kotlin compiler and can't be decompiled."
public val INCOMPATIBLE_ABI_VERSION_COMMENT: String =
        "$INCOMPATIBLE_ABI_VERSION_GENERAL_COMMENT\n" +
        "//\n" +
        "// Current compiler ABI version is $CURRENT_ABI_VERSION_MARKER\n" +
        "// File ABI version is $FILE_ABI_VERSION_MARKER"

public fun buildDecompiledText(
        classFile: VirtualFile,
        resolver: ResolverForDecompiler = DeserializerForDecompiler(classFile)
): DecompiledText {
    val kotlinClass = KotlinBinaryClassCache.getKotlinBinaryClass(classFile)
    assert(kotlinClass != null) { "Decompiled data factory shouldn't be called on an unsupported file: " + classFile }
    val classId = kotlinClass!!.getClassId()
    val classHeader = kotlinClass.getClassHeader()
    val packageFqName = classId.getPackageFqName()

    return when {
        !classHeader.isCompatibleAbiVersion -> {
            DecompiledText(
                    INCOMPATIBLE_ABI_VERSION_COMMENT
                            .replaceAll(CURRENT_ABI_VERSION_MARKER, JvmAbi.VERSION.toString())
                            .replaceAll(FILE_ABI_VERSION_MARKER, classHeader.version.toString()),
                    mapOf())
        }
        classHeader.isCompatiblePackageFacadeKind() ->
            buildDecompiledText(packageFqName, ArrayList(resolver.resolveDeclarationsInPackage(packageFqName)))
        classHeader.isCompatibleClassKind() ->
            buildDecompiledText(packageFqName, listOf(resolver.resolveTopLevelClass(classId)).filterNotNull())
        else ->
            throw UnsupportedOperationException("Unknown header kind: ${classHeader.kind} ${classHeader.isCompatibleAbiVersion}")
    }
}

private val DECOMPILED_COMMENT = "/* compiled code */"
private val FLEXIBLE_TYPE_COMMENT = "/* platform type */"

public val descriptorRendererForDecompiler: DescriptorRenderer = DescriptorRendererBuilder()
        .setWithDefinedIn(false)
        .setClassWithPrimaryConstructor(true)
        .setTypeNormalizer(IdeDescriptorRenderers.APPROXIMATE_FLEXIBLE_TYPES)
        .build()

//TODO: should use more accurate way to identify descriptors
public fun descriptorToKey(descriptor: DeclarationDescriptor): String {
    return descriptorRendererForDecompiler.render(descriptor)
}

public data class DecompiledText(public val text: String, public val renderedDescriptorsToRange: Map<String, TextRange>)

private fun buildDecompiledText(packageFqName: FqName, descriptors: List<DeclarationDescriptor>): DecompiledText {
    val builder = StringBuilder()
    val renderedDescriptorsToRange = HashMap<String, TextRange>()

    fun appendDecompiledTextAndPackageName() {
        builder.append("// IntelliJ API Decompiler stub source generated from a class file\n" + "// Implementation of methods is not available")
        builder.append("\n\n")
        if (!packageFqName.isRoot()) {
            builder.append("package ").append(packageFqName).append("\n\n")
        }
    }

    fun saveDescriptorToRange(descriptor: DeclarationDescriptor, startOffset: Int, endOffset: Int) {
        renderedDescriptorsToRange[descriptorToKey(descriptor)] = TextRange(startOffset, endOffset)
    }

    fun appendDescriptor(descriptor: DeclarationDescriptor, indent: String) {
        if (descriptor is MissingDependencyErrorClass) {
            throw IllegalStateException("${descriptor.javaClass.getSimpleName()} cannot be rendered. FqName: ${descriptor.fullFqName}")
        }
        val startOffset = builder.length()
        val header = if (isEnumEntry(descriptor))
            descriptor.getName().asString()
        else
            descriptorRendererForDecompiler.render(descriptor).replace("= ...", "= " + DECOMPILED_COMMENT)
        builder.append(header)
        var endOffset = builder.length()

        if (descriptor is CallableDescriptor) {
            //NOTE: assuming that only return types can be flexible
            if (descriptor.getReturnType().isFlexible()) {
                builder.append(" ").append(FLEXIBLE_TYPE_COMMENT)
            }
        }

        if (descriptor is FunctionDescriptor || descriptor is PropertyDescriptor) {
            if ((descriptor as MemberDescriptor).getModality() != Modality.ABSTRACT) {
                if (descriptor is FunctionDescriptor) {
                    builder.append(" { ").append(DECOMPILED_COMMENT).append(" }")
                }
                else {
                    // descriptor instanceof PropertyDescriptor
                    builder.append(" ").append(DECOMPILED_COMMENT)
                }
                endOffset = builder.length()
            }
        }
        else
            if (descriptor is ClassDescriptor && !isEnumEntry(descriptor)) {
                builder.append(" {\n")
                var firstPassed = false
                val subindent = indent + "    "
                val classObject = descriptor.getClassObjectDescriptor()
                if (classObject != null && !isSyntheticClassObject(classObject)) {
                    firstPassed = true
                    builder.append(subindent)
                    appendDescriptor(classObject, subindent)
                }
                for (member in descriptor.getDefaultType().getMemberScope().getDescriptors()) {
                    if (member.getContainingDeclaration() != descriptor) {
                        continue
                    }
                    if (member is CallableMemberDescriptor
                            && member.getKind() != CallableMemberDescriptor.Kind.DECLARATION
                            && !isComponentLike(member.getName())) {
                        continue
                    }

                    if (firstPassed) {
                        builder.append("\n")
                    }
                    else {
                        firstPassed = true
                    }
                    builder.append(subindent)
                    appendDescriptor(member, subindent)
                }
                builder.append(indent).append("}")
                endOffset = builder.length()
            }

        builder.append("\n")
        saveDescriptorToRange(descriptor, startOffset, endOffset)

        if (descriptor is ClassDescriptor) {
            val primaryConstructor = descriptor.getUnsubstitutedPrimaryConstructor()
            if (primaryConstructor != null) {
                saveDescriptorToRange(primaryConstructor, startOffset, endOffset)
            }
        }
    }

    appendDecompiledTextAndPackageName()
    for (member in descriptors) {
        appendDescriptor(member, "")
        builder.append("\n")
    }

    return DecompiledText(builder.toString(), renderedDescriptorsToRange)
}
