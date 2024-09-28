package com.example;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

public class ExternalCallScanner {

  private static final Logger logger = LoggerFactory.getLogger(ExternalCallScanner.class);

  private static final Set<String> HTTP_CLIENT_CLASSES = new HashSet<>(Arrays.asList(
      "java/net/HttpURLConnection",
      "org/apache/http/client/HttpClient",
      "okhttp3/OkHttpClient",
      "org/springframework/web/client/RestTemplate",
      "org/springframework/web/reactive/function/client/WebClient",
      "com/squareup/okhttp/OkHttpClient",
      "retrofit2/Retrofit",
      "io/micronaut/http/client/HttpClient",
      "javax/ws/rs/client/Client",
      "org/asynchttpclient/AsyncHttpClient",
      "com/github/kevinsawicki/http/HttpRequest",
      "kong/unirest/Unirest",
      "io/vertx/core/http/HttpClient",
      "com/mashape/unirest/http/Unirest",
      "org/eclipse/jetty/client/HttpClient",
      "com/ning/http/client/AsyncHttpClient",
      "org/glassfish/jersey/client/JerseyClient",
      "feign/Feign"));

  public List<ExternalCallInfo> findExternalCalls(List<ClassNode> allClasses) {
    logger.info("Starting to scan for external calls in {} classes", allClasses.size());
    List<ExternalCallInfo> externalCalls = new ArrayList<>();

    for (ClassNode classNode : allClasses) {
      logger.info("Scanning class: {}", classNode.name);
      for (MethodNode method : classNode.methods) {
        logger.info("Scanning method: {}.{}", classNode.name, method.name);
        for (AbstractInsnNode insn : method.instructions) {
          if (insn instanceof MethodInsnNode) {
            MethodInsnNode methodInsn = (MethodInsnNode) insn;
            String owner = methodInsn.owner.replace('/', '.');
            logger.trace("Examining method call: {}.{}", owner, methodInsn.name);

            if (HTTP_CLIENT_CLASSES.contains(owner) || isWebClientCall(owner, methodInsn.name)) {
              logger.info("Found potential external call in {}.{}", classNode.name, method.name);
              String url = extractUrlFromHttpCall(method, methodInsn);
              String httpMethod = extractHttpMethodFromCall(methodInsn);

              if (url != null && httpMethod != null) {
                List<String> parameters = extractParameters(methodInsn);
                String purpose = classNode.name + "." + method.name;
                String responseType = getReturnType(methodInsn.desc);
                ExternalCallInfo callInfo = new ExternalCallInfo(url, httpMethod, purpose, responseType, parameters);
                externalCalls.add(callInfo);
                logger.info("Added external call: {}", callInfo);
              } else {
                logger.warn("Could not extract URL or HTTP method for call in {}.{}", classNode.name, method.name);
              }
            }
          }
        }
      }
    }

    logger.info("Finished scanning. Found {} external calls", externalCalls.size());
    return externalCalls;
  }

  private List<String> extractParameters(MethodInsnNode methodInsn) {
    List<String> parameters = new ArrayList<>();
    Type[] argumentTypes = Type.getArgumentTypes(methodInsn.desc);
    for (Type argType : argumentTypes) {
      parameters.add(argType.getClassName());
    }
    logger.info("Extracted parameters: {}", parameters);
    return parameters;
  }

  private String getReturnType(String desc) {
    String returnType = Type.getReturnType(desc).getClassName();
    logger.info("Extracted return type: {}", returnType);
    return returnType;
  }

  private boolean isWebClientCall(String owner, String methodName) {
    boolean isWebClient = owner.contains("WebClient") ||
        (owner.endsWith("Client") && (methodName.startsWith("get") || methodName.startsWith("post") ||
            methodName.startsWith("put") || methodName.startsWith("delete")));
    logger.info("Checking if {}.{} is a WebClient call: {}", owner, methodName, isWebClient);
    return isWebClient;
  }

  private String extractUrlFromHttpCall(MethodNode method, MethodInsnNode methodInsn) {
    logger.info("Attempting to extract URL from method call: {}.{}", methodInsn.owner, methodInsn.name);
    String url = null;
    AbstractInsnNode currentInsn = methodInsn.getPrevious();
    int depth = 0;

    while (currentInsn != null && depth < 10) {
      if (currentInsn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) currentInsn;
        if (ldcInsn.cst instanceof String) {
          String potentialUrl = (String) ldcInsn.cst;
          if (isValidUrl(potentialUrl)) {
            url = potentialUrl;
            logger.info("Found valid URL: {}", url);
            break;
          }
        }
      } else if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode prevMethodInsn = (MethodInsnNode) currentInsn;
        if (prevMethodInsn.name.equals("uri") || prevMethodInsn.name.equals("baseUrl")) {
          logger.info("Found uri or baseUrl method, attempting to extract URL");
          url = extractUrlFromUriMethod(method, prevMethodInsn);
          if (url != null) {
            break;
          }
        }
      }
      currentInsn = currentInsn.getPrevious();
      depth++;
    }

    if (url == null) {
      logger.warn("Could not extract URL from method call");
    }
    return url;
  }

  private String extractUrlFromUriMethod(MethodNode method, MethodInsnNode uriMethodInsn) {
    logger.info("Attempting to extract URL from uri method: {}", uriMethodInsn.name);
    AbstractInsnNode currentInsn = uriMethodInsn.getPrevious();
    int depth = 0;

    while (currentInsn != null && depth < 5) {
      if (currentInsn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) currentInsn;
        if (ldcInsn.cst instanceof String) {
          String potentialUrl = (String) ldcInsn.cst;
          if (isValidUrl(potentialUrl)) {
            logger.info("Found valid URL from uri method: {}", potentialUrl);
            return potentialUrl;
          }
        }
      }
      currentInsn = currentInsn.getPrevious();
      depth++;
    }

    logger.warn("Could not extract URL from uri method");
    return null;
  }

  private boolean isValidUrl(String url) {
    try {
      new URL(url);
      logger.info("Valid URL: {}", url);
      return true;
    } catch (MalformedURLException e) {
      logger.info("Invalid URL: {}", url);
      return false;
    }
  }

  private String extractHttpMethodFromCall(MethodInsnNode methodInsn) {
    logger.info("Extracting HTTP method from call: {}.{}", methodInsn.owner, methodInsn.name);
    String methodName = methodInsn.name.toLowerCase();

    if (methodName.equals("get") || methodName.startsWith("get")) {
      return "GET";
    } else if (methodName.equals("post") || methodName.startsWith("post")) {
      return "POST";
    } else if (methodName.equals("put") || methodName.startsWith("put")) {
      return "PUT";
    } else if (methodName.equals("delete") || methodName.startsWith("delete")) {
      return "DELETE";
    } else if (methodName.equals("patch") || methodName.startsWith("patch")) {
      return "PATCH";
    } else if (methodName.equals("head") || methodName.startsWith("head")) {
      return "HEAD";
    } else if (methodName.equals("options") || methodName.startsWith("options")) {
      return "OPTIONS";
    } else if (methodName.equals("trace") || methodName.startsWith("trace")) {
      return "TRACE";
    } else if (methodName.equals("exchange")) {
      logger.info("Found exchange method, attempting to extract HTTP method from arguments");
      return extractHttpMethodFromExchangeMethod(methodInsn);
    }

    logger.warn("Could not determine HTTP method from call: {}.{}", methodInsn.owner, methodInsn.name);
    return null;
  }

  private String extractHttpMethodFromExchangeMethod(MethodInsnNode exchangeMethodInsn) {
    AbstractInsnNode currentInsn = exchangeMethodInsn.getPrevious();
    int depth = 0;

    while (currentInsn != null && depth < 5) {
      if (currentInsn instanceof FieldInsnNode) {
        FieldInsnNode fieldInsn = (FieldInsnNode) currentInsn;
        if (fieldInsn.owner.endsWith("HttpMethod")) {
          logger.info("Extracted HTTP method from exchange method: {}", fieldInsn.name);
          return fieldInsn.name;
        }
      }
      currentInsn = currentInsn.getPrevious();
      depth++;
    }

    logger.warn("Could not extract HTTP method from exchange method");
    return null;
  }
}