/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.File;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.LocalVariableInfo;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Headless bytecode Mixin & RenderPearl SPIR-V shader/pipeline verifier using Java 25's {@link java.lang.classfile} API
 * and Minecraft 26.3's {@link PipelineBuilder} / {@link Shaderc}.
 */
public final class MixinVerifier {
    private static final Map<String, byte[]> CLASS_BYTES = new HashMap<>();
    private static final Map<String, ClassModel> MODEL_CACHE = new HashMap<>();
    private static final List<String> ERRORS = new ArrayList<>();

    private static ClassModel getModel(String internalName) {
        return MODEL_CACHE.computeIfAbsent(internalName, k -> {
            byte[] b = CLASS_BYTES.get(k);
            return b != null ? ClassFile.of().parse(b) : null;
        });
    }

    private static void loadJar(Path jarPath) throws Exception {
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry je = entries.nextElement();
                if (je.getName().endsWith(".class") && !je.getName().startsWith("META-INF/")) {
                    String name = je.getName().substring(0, je.getName().length() - 6);
                    try (InputStream is = jf.getInputStream(je)) {
                        CLASS_BYTES.putIfAbsent(name, is.readAllBytes());
                    }
                }
            }
        }
    }

    private static void loadDir(Path dirPath) throws Exception {
        try (var stream = Files.walk(dirPath)) {
            for (Path cf : stream.filter(f -> f.toString().endsWith(".class")).toList()) {
                byte[] b = Files.readAllBytes(cf);
                ClassModel cm = ClassFile.of().parse(b);
                CLASS_BYTES.putIfAbsent(cm.thisClass().asInternalName(), b);
            }
        }
    }

    private static Map<String, AnnotationValue> getAnnotationMap(Annotation ann) {
        Map<String, AnnotationValue> map = new HashMap<>();
        for (AnnotationElement el : ann.elements()) {
            map.put(el.name().stringValue(), el.value());
        }
        return map;
    }

    private static List<String> getStrings(AnnotationValue ev) {
        if (ev == null) return List.of();
        if (ev instanceof AnnotationValue.OfString s) return List.of(s.stringValue());
        if (ev instanceof AnnotationValue.OfArray a) {
            List<String> res = new ArrayList<>();
            for (AnnotationValue v : a.values()) {
                if (v instanceof AnnotationValue.OfString s) res.add(s.stringValue());
                else if (v instanceof AnnotationValue.OfClass c) res.add(c.classSymbol().descriptorString());
            }
            return res;
        }
        if (ev instanceof AnnotationValue.OfClass c) return List.of(c.classSymbol().descriptorString());
        return List.of();
    }

    private static List<Annotation> getAllAnnotations(AttributedElement el) {
        List<Annotation> list = new ArrayList<>();
        el.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> list.addAll(a.annotations()));
        el.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> list.addAll(a.annotations()));
        return list;
    }

    private static List<MethodModel> findMethodsInHierarchy(String owner, String spec) {
        ClassModel cm = getModel(owner);
        if (cm == null) return List.of();
        String name = spec;
        String desc = null;
        int paren = spec.indexOf('(');
        if (paren >= 0) {
            name = spec.substring(0, paren);
            desc = spec.substring(paren);
        }
        if (name.startsWith("L") && name.contains(";")) {
            name = name.substring(name.indexOf(';') + 1);
        }
        if (name.endsWith("*")) {
            name = name.substring(0, name.length() - 1);
        }
        List<MethodModel> matches = new ArrayList<>();
        for (MethodModel mm : cm.methods()) {
            String mName = mm.methodName().stringValue();
            String mDesc = mm.methodType().stringValue();
            boolean nameMatch = mName.equals(name) || (spec.contains("*") && mName.startsWith(name));
            if (nameMatch && (desc == null || mDesc.equals(desc) || mDesc.startsWith(desc))) {
                matches.add(mm);
            }
        }
        if (matches.isEmpty()) {
            if (cm.superclass().isPresent()) {
                matches.addAll(findMethodsInHierarchy(cm.superclass().get().asInternalName(), spec));
            }
            for (ClassEntry iface : cm.interfaces()) {
                matches.addAll(findMethodsInHierarchy(iface.asInternalName(), spec));
            }
        }
        return matches;
    }

    private static FieldModel findFieldModelInHierarchy(String owner, String name, String desc) {
        ClassModel cm = getModel(owner);
        if (cm == null) return null;
        for (FieldModel fm : cm.fields()) {
            if (fm.fieldName().stringValue().equals(name) && (desc == null || fm.fieldType().stringValue().equals(desc))) {
                return fm;
            }
        }
        if (cm.superclass().isPresent()) {
            FieldModel found = findFieldModelInHierarchy(cm.superclass().get().asInternalName(), name, desc);
            if (found != null) return found;
        }
        for (ClassEntry iface : cm.interfaces()) {
            FieldModel found = findFieldModelInHierarchy(iface.asInternalName(), name, desc);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean findFieldInHierarchy(String owner, String name, String desc) {
        return findFieldModelInHierarchy(owner, name, desc) != null;
    }

    private static boolean checkAtTargetInMethod(MethodModel mm, String atValue, String atTarget) {
        if (atTarget == null || atTarget.isEmpty()) return true;
        if (mm.code().isEmpty()) return true;
        for (CodeElement ce : mm.code().get()) {
            if (ce instanceof InvokeInstruction inv) {
                String full1 = "L" + inv.owner().asInternalName() + ";" + inv.name().stringValue() + inv.type().stringValue();
                String full2 = inv.owner().asInternalName() + "." + inv.name().stringValue() + inv.type().stringValue();
                if (full1.equals(atTarget) || full2.equals(atTarget) || full1.startsWith(atTarget)) return true;
            } else if (ce instanceof FieldInstruction fi) {
                String full1 = "L" + fi.owner().asInternalName() + ";" + fi.name().stringValue() + ":" + fi.type().stringValue();
                String full2 = fi.owner().asInternalName() + "." + fi.name().stringValue() + ":" + fi.type().stringValue();
                if (full1.equals(atTarget) || full2.equals(atTarget)) return true;
            } else if (ce instanceof NewObjectInstruction noi) {
                String c1 = "L" + noi.className().asInternalName() + ";";
                String c2 = noi.className().asInternalName();
                if (atTarget.equals(c1) || atTarget.equals(c2) || atTarget.startsWith(c1)) return true;
                if (atTarget.startsWith("(") && atTarget.endsWith(c1)) return true;
            }
        }
        return false;
    }

    private static List<String> parseParamTypes(String methodDesc) {
        List<String> params = new ArrayList<>();
        int i = 1;
        while (i < methodDesc.length() && methodDesc.charAt(i) != ')') {
            int start = i;
            while (methodDesc.charAt(i) == '[') i++;
            if (methodDesc.charAt(i) == 'L') {
                i = methodDesc.indexOf(';', i) + 1;
            } else {
                i++;
            }
            params.add(methodDesc.substring(start, i));
        }
        return params;
    }

    private static void auditMixinClass(Path cf) throws Exception {
        ClassModel cm = ClassFile.of().parse(cf);
        String mixinName = cm.thisClass().asInternalName();
        List<String> targets = new ArrayList<>();
        for (Annotation ann : getAllAnnotations(cm)) {
            if (ann.className().stringValue().equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                Map<String, AnnotationValue> map = getAnnotationMap(ann);
                for (String desc : getStrings(map.get("value"))) {
                    if (desc.startsWith("L") && desc.endsWith(";")) {
                        targets.add(desc.substring(1, desc.length() - 1));
                    }
                }
                for (String t : getStrings(map.get("targets"))) {
                    targets.add(t.replace('.', '/'));
                }
            }
        }
        if (targets.isEmpty()) return;

        boolean isInterfaceMixin = cm.flags().has(AccessFlag.INTERFACE);
        for (String target : targets) {
            ClassModel tcm = getModel(target);
            if (tcm == null) {
                ERRORS.add("[MISSING TARGET CLASS] " + mixinName + " -> " + target);
                continue;
            }
            for (FieldModel fm : cm.fields()) {
                for (Annotation ann : getAllAnnotations(fm)) {
                    if (ann.className().stringValue().equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                        String fName = fm.fieldName().stringValue();
                        String fDesc = fm.fieldType().stringValue();
                        FieldModel tfm = findFieldModelInHierarchy(target, fName, fDesc);
                        if (tfm == null) {
                            ERRORS.add("[MISSING @Shadow FIELD] " + mixinName + " in " + target + ": " + fName + " " + fDesc);
                        } else if (tfm.flags().has(AccessFlag.STATIC) != fm.flags().has(AccessFlag.STATIC)) {
                            ERRORS.add("[MISMATCHED @Shadow FIELD STATIC] " + mixinName + "#" + fName + " in " + target);
                        }
                    }
                }
            }

            Set<String> satisfiedGroups = new HashSet<>();
            for (MethodModel mm : cm.methods()) {
                String groupName = null;
                for (Annotation ann : getAllAnnotations(mm)) {
                    if (ann.className().stringValue().equals("Lorg/spongepowered/asm/mixin/injection/Group;")) {
                        List<String> gNames = getStrings(getAnnotationMap(ann).get("name"));
                        if (!gNames.isEmpty()) groupName = gNames.get(0);
                    }
                }
                if (groupName == null) continue;
                for (Annotation ann : getAllAnnotations(mm)) {
                    String annType = ann.className().stringValue();
                    if (annType.startsWith("Lorg/spongepowered/asm/mixin/injection/") || annType.startsWith("Lcom/llamalad7/mixinextras/injector/")) {
                        Map<String, AnnotationValue> map = getAnnotationMap(ann);
                        for (AnnotationValue v : map.get("at") instanceof AnnotationValue.OfArray arr ? arr.values() : (map.get("at") != null ? List.of(map.get("at")) : List.<AnnotationValue>of())) {
                            if (v instanceof AnnotationValue.OfAnnotation oa) {
                                Map<String, AnnotationValue> atMap = getAnnotationMap(oa.annotation());
                                List<String> atVals = getStrings(atMap.get("value"));
                                List<String> atTargets = getStrings(atMap.get("target"));
                                String atValue = atVals.isEmpty() ? "" : atVals.get(0);
                                String atTarget = atTargets.isEmpty() ? "" : atTargets.get(0);
                                for (String spec : getStrings(map.get("method"))) {
                                    for (MethodModel tm : findMethodsInHierarchy(target, spec)) {
                                        if (checkAtTargetInMethod(tm, atValue, atTarget)) {
                                            satisfiedGroups.add(groupName);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            for (MethodModel mm : cm.methods()) {
                String methodGroup = null;
                for (Annotation ann : getAllAnnotations(mm)) {
                    if (ann.className().stringValue().equals("Lorg/spongepowered/asm/mixin/injection/Group;")) {
                        List<String> gNames = getStrings(getAnnotationMap(ann).get("name"));
                        if (!gNames.isEmpty()) methodGroup = gNames.get(0);
                    }
                }
                for (Annotation ann : getAllAnnotations(mm)) {
                    String annType = ann.className().stringValue();
                    Map<String, AnnotationValue> map = getAnnotationMap(ann);
                    if (annType.equals("Lorg/spongepowered/asm/mixin/Shadow;") || annType.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                        String mName = mm.methodName().stringValue();
                        String mDesc = mm.methodType().stringValue();
                        List<MethodModel> shadowTargets = findMethodsInHierarchy(target, mName + mDesc);
                        if (shadowTargets.isEmpty()) {
                            ERRORS.add("[MISSING " + (annType.contains("Overwrite") ? "@Overwrite" : "@Shadow") + " METHOD] " + mixinName + " in " + target + ": " + mName + mDesc);
                        } else if (!isInterfaceMixin) {
                            boolean handlerStatic = mm.flags().has(AccessFlag.STATIC);
                            boolean anyMatch = false;
                            for (MethodModel tm : shadowTargets) {
                                if (tm.flags().has(AccessFlag.STATIC) == handlerStatic) {
                                    anyMatch = true;
                                    break;
                                }
                            }
                            if (!anyMatch) {
                                ERRORS.add("[MISMATCHED @Shadow/@Overwrite STATIC] " + mixinName + "#" + mName + mDesc + " in " + target);
                            }
                        }
                    } else if (annType.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                        String mName = mm.methodName().stringValue();
                        List<String> val = getStrings(map.get("value"));
                        String fName = val.isEmpty() ? "" : val.get(0);
                        if (fName.isEmpty()) {
                            String stripped = mName.contains("$") ? mName.substring(mName.indexOf('$') + 1) : mName;
                            if (stripped.startsWith("get") || stripped.startsWith("set")) stripped = stripped.substring(3);
                            else if (stripped.startsWith("is")) stripped = stripped.substring(2);
                            if (!stripped.isEmpty()) {
                                fName = Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
                            }
                        }
                        if (!fName.isEmpty() && !findFieldInHierarchy(target, fName, null) && !findFieldInHierarchy(target, fName.toUpperCase(Locale.ROOT), null)) {
                            ERRORS.add("[MISSING @Accessor FIELD] " + mixinName + "#" + mName + " in " + target + ": field=\"" + fName + "\"");
                        }
                    } else if (annType.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")) {
                        String mName = mm.methodName().stringValue();
                        List<String> val = getStrings(map.get("value"));
                        String iName = val.isEmpty() ? "" : val.get(0);
                        if (iName.isEmpty()) {
                            String stripped = mName.contains("$") ? mName.substring(mName.indexOf('$') + 1) : mName;
                            if (stripped.startsWith("invoke") || stripped.startsWith("call")) {
                                int prefixLen = stripped.startsWith("invoke") ? 6 : 4;
                                stripped = stripped.substring(prefixLen);
                                if (!stripped.isEmpty()) {
                                    iName = Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
                                }
                            }
                        }
                        if (!iName.isEmpty() && findMethodsInHierarchy(target, iName + mm.methodType().stringValue()).isEmpty()) {
                            ERRORS.add("[MISSING @Invoker METHOD] " + mixinName + "#" + mName + " in " + target + ": " + iName + mm.methodType().stringValue());
                        }
                    } else if (annType.startsWith("Lorg/spongepowered/asm/mixin/injection/") || annType.startsWith("Lcom/llamalad7/mixinextras/injector/")) {
                        if (map.get("require") instanceof AnnotationValue.OfInt oi && oi.intValue() == 0) {
                            continue;
                        }
                        List<String> methodSpecs = getStrings(map.get("method"));
                        for (String spec : methodSpecs) {
                            List<MethodModel> matchedMethods = findMethodsInHierarchy(target, spec);
                            if (matchedMethods.isEmpty()) {
                                if (methodGroup != null && satisfiedGroups.contains(methodGroup)) {
                                    continue;
                                }
                                ERRORS.add("[MISSING INJECT TARGET METHOD] " + mixinName + "#" + mm.methodName().stringValue() + " -> " + target + " method=\"" + spec + "\"");
                            } else {
                                if (!isInterfaceMixin) {
                                    boolean handlerStatic = mm.flags().has(AccessFlag.STATIC);
                                    for (MethodModel tm : matchedMethods) {
                                        String tmName = tm.methodName().stringValue();
                                        if (tmName.equals("<init>") || tmName.equals("<clinit>")) continue;
                                        if (tm.flags().has(AccessFlag.STATIC) != handlerStatic) {
                                            ERRORS.add("[MISMATCHED INJECT STATIC MODIFIER] " + mixinName + "#" + mm.methodName().stringValue()
                                                + " (static=" + handlerStatic + ") -> " + target + "#" + tmName + tm.methodType().stringValue()
                                                + " (static=" + tm.flags().has(AccessFlag.STATIC) + ")");
                                        }
                                    }
                                }
                                mm.findAttribute(Attributes.runtimeVisibleParameterAnnotations()).ifPresent(pa -> {
                                    List<List<Annotation>> paramAnns = pa.parameterAnnotations();
                                    List<String> hParams = parseParamTypes(mm.methodType().stringValue());
                                    for (int pi = 0; pi < paramAnns.size() && pi < hParams.size(); pi++) {
                                        for (Annotation pann : paramAnns.get(pi)) {
                                            if (pann.className().stringValue().equals("Lcom/llamalad7/mixinextras/sugar/Local;")) {
                                                Map<String, AnnotationValue> lmap = getAnnotationMap(pann);
                                                List<String> names = getStrings(lmap.get("name"));
                                                if (!names.isEmpty()) {
                                                    String expectedName = names.get(0);
                                                    boolean foundLocal = false;
                                                    for (MethodModel tm : matchedMethods) {
                                                        if (tm.code().isPresent()) {
                                                            var lvt = tm.code().get().findAttribute(Attributes.localVariableTable());
                                                            if (lvt.isPresent()) {
                                                                for (LocalVariableInfo lvi : lvt.get().localVariables()) {
                                                                    if (lvi.name().stringValue().equals(expectedName)) {
                                                                        foundLocal = true;
                                                                        break;
                                                                    }
                                                                }
                                                            } else {
                                                                foundLocal = true;
                                                            }
                                                        }
                                                    }
                                                    if (!foundLocal) {
                                                        ERRORS.add("[MISSING @Local NAME] " + mixinName + "#" + mm.methodName().stringValue() + " in " + target + "." + spec + " -> @Local(name=\"" + expectedName + "\")");
                                                    }
                                                }
                                            }
                                        }
                                    }
                                });
                                if (annType.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                                    List<String> hParams = parseParamTypes(mm.methodType().stringValue());
                                    int ciIdx = -1;
                                    for (int i = 0; i < hParams.size(); i++) {
                                        if (hParams.get(i).startsWith("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo")) {
                                            ciIdx = i;
                                            break;
                                        }
                                    }
                                    if (ciIdx > 0) {
                                        List<String> prefixParams = hParams.subList(0, ciIdx);
                                        boolean sigOk = false;
                                        for (MethodModel tm : matchedMethods) {
                                            List<String> tParams = parseParamTypes(tm.methodType().stringValue());
                                            if (tParams.size() >= prefixParams.size() && tParams.subList(0, prefixParams.size()).equals(prefixParams)) {
                                                sigOk = true;
                                                break;
                                            }
                                        }
                                        if (!sigOk) {
                                            ERRORS.add("[MISMATCHED @Inject PARAMS] " + mixinName + "#" + mm.methodName().stringValue() + mm.methodType().stringValue() + " vs " + target + "." + spec + " -> " + matchedMethods.get(0).methodType().stringValue());
                                        }
                                    }
                                }
                                List<Annotation> atAnns = new ArrayList<>();
                                AnnotationValue atVal = map.get("at");
                                if (atVal instanceof AnnotationValue.OfAnnotation oa) atAnns.add(oa.annotation());
                                else if (atVal instanceof AnnotationValue.OfArray arr) {
                                    for (AnnotationValue v : arr.values()) {
                                        if (v instanceof AnnotationValue.OfAnnotation oa) atAnns.add(oa.annotation());
                                    }
                                }
                                for (Annotation atAnn : atAnns) {
                                    Map<String, AnnotationValue> atMap = getAnnotationMap(atAnn);
                                    List<String> atVals = getStrings(atMap.get("value"));
                                    List<String> atTargets = getStrings(atMap.get("target"));
                                    String atValue = atVals.isEmpty() ? "" : atVals.get(0);
                                    String atTarget = atTargets.isEmpty() ? "" : atTargets.get(0);
                                    if (!atTarget.isEmpty() && (atValue.equals("INVOKE") || atValue.equals("FIELD") || atValue.equals("NEW") || atValue.equals("INVOKE_ASSIGN"))) {
                                        boolean foundInAny = false;
                                        for (MethodModel tm : matchedMethods) {
                                            if (checkAtTargetInMethod(tm, atValue, atTarget)) {
                                                foundInAny = true;
                                                break;
                                            }
                                        }
                                        if (!foundInAny) {
                                            if (methodGroup != null && satisfiedGroups.contains(methodGroup)) {
                                                continue;
                                            }
                                            ERRORS.add("[MISSING @At TARGET] " + mixinName + "#" + mm.methodName().stringValue() + " in " + target + "." + spec + " -> @At(" + atValue + ", target=\"" + atTarget + "\")");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static int verifyShadersAndPipelines(Path resourcesDir) throws Exception {
        Path shadersRoot = resourcesDir.resolve("assets/meteor-client/shaders");
        if (!Files.isDirectory(shadersRoot)) {
            ERRORS.add("[MISSING SHADERS DIR] " + shadersRoot);
            return 0;
        }

        int shaderCount = 0;
        long compiler = Shaderc.shaderc_compiler_initialize();
        try (var stream = Files.walk(shadersRoot)) {
            for (Path p : stream.filter(Files::isRegularFile).sorted().toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".vert") && !name.endsWith(".frag")) continue;
                shaderCount++;
                String src = Files.readString(p);
                int kind = name.endsWith(".vert") ? Shaderc.shaderc_glsl_vertex_shader : Shaderc.shaderc_glsl_fragment_shader;
                long opts = Shaderc.shaderc_compile_options_initialize();
                Shaderc.shaderc_compile_options_set_target_env(opts, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_1);
                Shaderc.shaderc_compile_options_set_auto_bind_uniforms(opts, true);
                Shaderc.shaderc_compile_options_set_preserve_bindings(opts, false);
                Shaderc.shaderc_compile_options_set_generate_debug_info(opts);
                Shaderc.shaderc_compile_options_set_optimization_level(opts, 0);
                long res = Shaderc.shaderc_compile_into_spv(compiler, src, kind, p.toString(), "main", opts);
                int status = Shaderc.shaderc_result_get_compilation_status(res);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    ERRORS.add("[SHADER COMPILE ERROR] " + shadersRoot.relativize(p) + ": " + Shaderc.shaderc_result_get_error_message(res).trim());
                }
                Shaderc.shaderc_result_release(res);
                Shaderc.shaderc_compile_options_release(opts);
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }

        Field pipelinesField = MeteorRenderPipelines.class.getDeclaredField("PIPELINES");
        pipelinesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<RenderPipeline> pipelines = (List<RenderPipeline>) pipelinesField.get(null);

        ShaderSource shaderSource = new ShaderSource() {
            @Override
            public String getShader(Identifier id, ShaderType type) {
                try {
                    Path p = resourcesDir.resolve("assets").resolve(id.getNamespace()).resolve(id.getPath());
                    return Files.exists(p) ? Files.readString(p) : null;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public CachedIncludeSource getInclude(Identifier id) {
                return null;
            }

            @Override
            public void close() {
            }
        };

        DeviceInfo deviceInfo = new DeviceInfo(
            "Headless",
            "Headless",
            "1.0",
            false,
            "Headless",
            1.0f,
            null,
            new DeviceFeatures(false, false, false, false, false, false, false, false),
            Set.of(),
            new HintsAndWorkarounds(false, false, false, false),
            null
        );
        java.lang.reflect.InvocationHandler handler = (proxy, method, methodArgs) -> {
            if ("getDeviceInfo".equals(method.getName())) return deviceInfo;
            return null;
        };
        GpuDevice dummyDevice = (GpuDevice) Proxy.newProxyInstance(
            MixinVerifier.class.getClassLoader(),
            new Class<?>[]{GpuDevice.class},
            handler
        );
        GpuDeviceBackend dummyBackend = (GpuDeviceBackend) Proxy.newProxyInstance(
            MixinVerifier.class.getClassLoader(),
            new Class<?>[]{GpuDeviceBackend.class},
            handler
        );
        Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        deviceField.set(null, dummyDevice);

        try (PipelineBuilder pb = new PipelineBuilder(dummyBackend)) {
            Method genMethod = PipelineBuilder.class.getDeclaredMethod(
                "generateBackendCreateInfo",
                RenderPipeline.class,
                ShaderSource.class,
                ReferenceArrayList.class,
                Object2IntOpenHashMap.class
            );
            genMethod.setAccessible(true);

            Set<Identifier> seenLocations = new HashSet<>();
            for (RenderPipeline rp : pipelines) {
                if (!seenLocations.add(rp.getLocation())) {
                    ERRORS.add("[DUPLICATE PIPELINE LOCATION] " + rp.getLocation());
                }
                boolean expectsDepth = rp.getLocation().getPath().endsWith("_depth");
                if (rp.wantsDepthTexture() != expectsDepth) {
                    ERRORS.add("[PIPELINE DEPTH MISMATCH] " + rp.getLocation() + " wantsDepthTexture=" + rp.wantsDepthTexture() + " (expected " + expectsDepth + ")");
                } else if (expectsDepth && rp.getDepthStencilState().depthTest() != CompareOp.GREATER_THAN_OR_EQUAL) {
                    ERRORS.add("[PIPELINE REVERSE-Z DEPTH OP MISMATCH] " + rp.getLocation() + " depthTest=" + rp.getDepthStencilState().depthTest() + " (expected GREATER_THAN_OR_EQUAL)");
                }
                ReferenceArrayList<?> shaders = new ReferenceArrayList<>();
                Object2IntOpenHashMap<String> uniformIndices = new Object2IntOpenHashMap<>();
                try {
                    Object createInfo = genMethod.invoke(pb, rp, shaderSource, shaders, uniformIndices);
                    if (createInfo == null) {
                        ERRORS.add("[PIPELINE COMPILE ERROR] Failed to compile RenderPipeline " + rp.getLocation());
                    }
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    ERRORS.add("[PIPELINE COMPILE ERROR] " + rp.getLocation() + ": " + cause.getMessage());
                }
            }
        }
        return pipelines.size();
    }

    public static void main(String[] args) throws Exception {
        List<Path> mixinDirs = new ArrayList<>();
        Path resourcesDir = null;
        for (int i = 0; i < args.length; i++) {
            if ("--classpath".equals(args[i]) && i + 1 < args.length) {
                for (String entry : args[++i].split(File.pathSeparator)) {
                    if (entry.isBlank()) continue;
                    Path p = Path.of(entry);
                    if (Files.isRegularFile(p) && entry.endsWith(".jar")) {
                        loadJar(p);
                    } else if (Files.isDirectory(p)) {
                        loadDir(p);
                    }
                }
            } else if ("--mixins".equals(args[i]) && i + 1 < args.length) {
                Path p = Path.of(args[++i]);
                if (Files.isDirectory(p)) {
                    loadDir(p);
                    mixinDirs.add(p);
                }
            } else if ("--resources".equals(args[i]) && i + 1 < args.length) {
                resourcesDir = Path.of(args[++i]);
            }
        }

        int mixinFilesChecked = 0;
        for (Path dir : mixinDirs) {
            try (var stream = Files.walk(dir)) {
                for (Path cf : stream.filter(f -> f.toString().endsWith(".class")).toList()) {
                    auditMixinClass(cf);
                    mixinFilesChecked++;
                }
            }
        }

        int pipelinesChecked = 0;
        if (resourcesDir != null) {
            pipelinesChecked = verifyShadersAndPipelines(resourcesDir);
        }

        if (!ERRORS.isEmpty()) {
            System.err.println("Mixin/Shader verification failed with " + ERRORS.size() + " error(s):");
            for (String err : ERRORS) {
                System.err.println("  " + err);
            }
            System.exit(1);
        }
        System.out.println("MixinVerifier: verified " + mixinFilesChecked + " class files against " + CLASS_BYTES.size()
            + " classpath classes and " + pipelinesChecked + " RenderPearl SPIR-V pipelines (0 errors).");
    }
}
