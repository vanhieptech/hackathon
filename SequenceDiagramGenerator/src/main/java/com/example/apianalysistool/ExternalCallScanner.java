package com.example.apianalysistool;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class ExternalCallScanner {

    private static final Set<String> HTTP_CLIENT_CLASSES = new HashSet<>(Arrays.asList(
            "java/net/HttpURLConnection",
            "org/apache/http/client/HttpClient",
            "okhttp3/OkHttpClient"
    ));

    public List<ExternalCallInfo> findExternalCalls(List<ClassNode> classes) {
        List<ExternalCallInfo> externalCalls = new ArrayList<>();
        for (ClassNode classNode : classes) {
            for (MethodNode method : classNode.methods) {
                externalCalls.addAll(scanMethodForExternalCalls(classNode, method));
            }
        }
        return externalCalls;
    }

    private List<ExternalCallInfo> scanMethodForExternalCalls(ClassNode classNode, MethodNode method) {
        List<ExternalCallInfo> calls = new ArrayList<>();
        InsnList instructions = method.instructions;
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode insn = instructions.get(i);
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode methodInsn = (MethodInsnNode) insn;
                if (HTTP_CLIENT_CLASSES.contains(methodInsn.owner)) {
                    ExternalCallInfo callInfo = extractExternalCallInfo(classNode, method, methodInsn);
                    if (callInfo != null) {
                        calls.add(callInfo);
                    }
                }
            }
        }
        return calls;
    }

    private ExternalCallInfo extractExternalCallInfo(ClassNode classNode, MethodNode method, MethodInsnNode methodInsn) {
        String url = findUrlArgument(method.instructions, methodInsn);
        String httpMethod = inferHttpMethod(methodInsn);
        if (url != null && httpMethod != null) {
            Type[] argumentTypes = Type.getArgumentTypes(methodInsn.desc);
            String[] parameters = Arrays.stream(argumentTypes)
                    .map(Type::getClassName)
                    .toArray(String[]::new);
            String purpose = classNode.name + "." + method.name;
            return new ExternalCallInfo(url, httpMethod, parameters, purpose);
        }
        return null;
    }

    private String findUrlArgument(InsnList instructions, MethodInsnNode methodInsn) {
        // This is a simplified implementation. In a real scenario, you'd need to do
        // more sophisticated data flow analysis to track the URL argument.
        for (int i = instructions.indexOf(methodInsn) - 1; i >= 0; i--) {
            AbstractInsnNode insn = instructions.get(i);
            if (insn instanceof LdcInsnNode) {
                LdcInsnNode ldcInsn = (LdcInsnNode) insn;
                if (ldcInsn.cst instanceof String && ((String) ldcInsn.cst).startsWith("http")) {
                    return (String) ldcInsn.cst;
                }
            }
        }
        return null;
    }

    private String inferHttpMethod(MethodInsnNode methodInsn) {
        String methodName = methodInsn.name.toLowerCase();
        if (methodName.contains("get")) return "GET";
        if (methodName.contains("post")) return "POST";
        if (methodName.contains("put")) return "PUT";
        if (methodName.contains("delete")) return "DELETE";
        if (methodName.contains("patch")) return "PATCH";
        return "UNKNOWN";
    }
}