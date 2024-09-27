package com.example.apianalysistool;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class SequenceDiagramGenerator {

  private final Set<String> processedMethods = new HashSet<>();
  private final List<String> orderedParticipants = new ArrayList<>();
  private final Map<String, String> implToInterfaceMap = new HashMap<>();

  public void generateSequenceDiagram(List<ClassNode> allClasses, List<APIInfo> apis,
      List<ExternalCallInfo> externalCalls) {
    StringBuilder sb = new StringBuilder();
    sb.append("@startuml\n");
    sb.append("skinparam sequenceArrowThickness 2\n");
    sb.append("skinparam roundcorner 20\n");
    sb.append("skinparam maxmessagesize 60\n\n");

    mapImplementationsToInterfaces(allClasses);
    appendParticipants(sb, allClasses, externalCalls);

    for (APIInfo api : apis) {
      generateSequenceForAPI(sb, api, allClasses);
    }

    // Scan for all injected services and their methods
    for (ClassNode classNode : allClasses) {
      scanInjectedServices(sb, classNode, allClasses);
    }

    sb.append("@enduml");

    try (FileWriter writer = new FileWriter("sequence_diagram.puml")) {
      writer.write(sb.toString());
    } catch (IOException e) {
      System.out.println("Error generating sequence diagram: " + e.getMessage());
    }
  }

  private void mapImplementationsToInterfaces(List<ClassNode> allClasses) {
    for (ClassNode classNode : allClasses) {
      if (classNode.interfaces != null && !classNode.interfaces.isEmpty()) {
        for (String interfaceName : classNode.interfaces) {
          implToInterfaceMap.put(classNode.name, interfaceName);
        }
      }
    }
  }

  private void appendParticipants(StringBuilder sb, List<ClassNode> allClasses, List<ExternalCallInfo> externalCalls) {
    Set<String> controllers = new HashSet<>();
    Set<String> services = new HashSet<>();
    Set<String> repositories = new HashSet<>();
    Set<String> externalServices = new HashSet<>();

    for (ClassNode classNode : allClasses) {
      String className = getSimpleClassName(classNode.name);
      if (className.endsWith("Controller")) {
        controllers.add(className);
      } else if (className.endsWith("Service") || className.endsWith("ServiceImpl")) {
        services.add(getInterfaceName(classNode.name));
      } else if (className.endsWith("Repository")) {
        repositories.add(className);
      }
    }

    for (ExternalCallInfo externalCall : externalCalls) {
      externalServices.add(getExternalServiceName(externalCall.getUrl()));
    }

    orderedParticipants.add("Client");
    sb.append("actor Client\n");

    orderedParticipants.addAll(controllers);
    for (String controller : controllers) {
      sb.append("participant ").append(controller).append("\n");
    }

    orderedParticipants.addAll(services);
    for (String service : services) {
      sb.append("participant ").append(service).append("\n");
    }

    orderedParticipants.addAll(externalServices);
    for (String externalService : externalServices) {
      sb.append("participant ").append(externalService).append("\n");
    }

    orderedParticipants.addAll(repositories);
    for (String repository : repositories) {
      String databaseName = getDatabaseName(repository);
      orderedParticipants.add(databaseName);
      sb.append("database ").append(databaseName).append("\n");
    }

    sb.append("\n");
  }

  private String getInterfaceName(String className) {
    String interfaceName = implToInterfaceMap.get(className);
    return interfaceName != null ? getSimpleClassName(interfaceName) : getSimpleClassName(className);
  }

  private String getExternalServiceName(String url) {
    try {
      java.net.URL parsedUrl = new java.net.URL(url);
      return parsedUrl.getHost();
    } catch (java.net.MalformedURLException e) {
      return "ExternalService";
    }
  }

  private String getDatabaseName(String repositoryName) {
    return repositoryName.replace("Repository", "DB");
  }

  private void generateSequenceForAPI(StringBuilder sb, APIInfo api, List<ClassNode> allClasses) {
    try {
      sb.append("== ").append(api.getMethodName()).append(" ==\n");
      String className = getSimpleClassName(getClassName(api.getMethodName()));
      sb.append("Client -> ").append(className).append(" : ")
          .append(api.getHttpMethod()).append(" ").append(api.getPath()).append("\n");
      sb.append("activate ").append(className).append("\n");

      ClassNode controllerClass = findClassByName(allClasses, className);
      if (controllerClass != null) {
        MethodNode method = findMethodByName(controllerClass, getMethodName(api.getMethodName()));
        if (method != null) {
          processedMethods.clear();
          processMethod(sb, method, allClasses, 1, controllerClass.name);
        } else {
          sb.append("note over ").append(className).append(" : Method not found\n");
        }
      } else {
        sb.append("note over ").append(className).append(" : Class not found\n");
      }

      sb.append(className).append(" --> Client : HTTP Response (")
          .append(api.getReturnType()).append(")\n");
      sb.append("deactivate ").append(className).append("\n\n");
    } catch (Exception e) {
      sb.append("note over Client : Error processing API: ").append(api.getMethodName())
          .append("\n").append(e.getMessage()).append("\n");
      System.err.println("Error processing API: " + api.getMethodName());
      e.printStackTrace();
    }
  }

  private void scanInjectedServices(StringBuilder sb, ClassNode classNode, List<ClassNode> allClasses) {
    for (MethodNode method : classNode.methods) {
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof FieldInsnNode) {
          FieldInsnNode fieldInsn = (FieldInsnNode) insn;
          String serviceClassName = getSimpleClassName(fieldInsn.owner);
          if (serviceClassName.endsWith("Service") || serviceClassName.endsWith("Client")) {
            // Generate flow for the injected service methods
            sb.append(getSimpleClassName(classNode.name)).append(" -> ").append(serviceClassName)
                .append(" : call method\n");
            sb.append("activate ").append(serviceClassName).append("\n");

            // Find the class for the injected service
            ClassNode serviceClassNode = findClassByName(allClasses, fieldInsn.owner);
            if (serviceClassNode != null) {
              // Assuming the service has a method to call
              MethodNode serviceMethod = findMethodByName(serviceClassNode, "methodName"); // Replace with actual method
                                                                                           // name
              if (serviceMethod != null) {
                processMethod(sb, serviceMethod, allClasses, 1, fieldInsn.owner);
              } else {
                sb.append("note over ").append(serviceClassName).append(" : Method not found\n");
              }
            } else {
              sb.append("note over ").append(serviceClassName).append(" : Class not found\n");
            }

            sb.append(serviceClassName).append(" --> ").append(getSimpleClassName(classNode.name))
                .append(" : return data\n");
            sb.append("deactivate ").append(serviceClassName).append("\n");
          }
        }
      }
    }
  }

  private void processMethod(StringBuilder sb, MethodNode method, List<ClassNode> allClasses, int depth,
      String callerClass) {
    if (depth > 5 || processedMethods.contains(callerClass + "." + method.name))
      return;
    processedMethods.add(callerClass + "." + method.name);

    for (AbstractInsnNode insn : method.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String className = getSimpleClassName(methodInsn.owner);
        String methodName = methodInsn.name;
        String returnType = Type.getReturnType(methodInsn.desc).getClassName();

        if (className.endsWith("Service") || className.endsWith("Repository") || className.contains("Client")) {
          sb.append(getInterfaceName(callerClass)).append(" -> ").append(className).append(" : ")
              .append(methodName).append("\n");
          sb.append("activate ").append(className).append("\n");

          ClassNode targetClass = findImplementationClass(allClasses, methodInsn.owner);
          if (targetClass != null) {
            MethodNode targetMethod = findMethodByName(targetClass, methodName);
            if (targetMethod != null) {
              processMethod(sb, targetMethod, allClasses, depth + 1, targetClass.name);
            }
          }

          if (className.equals("AuthorServiceClient")) {
            // Generate specific flow for AuthorServiceClient
            sb.append(className).append(" -> ").append(getExternalServiceName("http://localhost:8081")).append(" : ")
                .append(methodName).append(" > /api/authors/{id}\n");
            sb.append(getExternalServiceName("http://localhost:8081")).append(" --> ").append(className)
                .append(" : return AuthorDTO\n");
          } else if (className.endsWith("Repository")) {
            String databaseName = getDatabaseName(className);
            sb.append(className).append(" -> ").append(databaseName).append(" : execute query\n");
            sb.append(databaseName).append(" --> ").append(className).append(" : return data\n");
          } else if (className.contains("Client")) {
            String externalServiceName = getExternalServiceName("http://example.com"); // Replace with actual URL if
                                                                                       // available
            sb.append(className).append(" -> ").append(externalServiceName).append(" : API call\n");
            sb.append(externalServiceName).append(" --> ").append(className).append(" : return data\n");
          }

          sb.append(className).append(" --> ").append(getInterfaceName(callerClass)).append(" : return ")
              .append(returnType).append("\n");
          sb.append("deactivate ").append(className).append("\n");
        }
      }
    }
  }

  private ClassNode findImplementationClass(List<ClassNode> allClasses, String interfaceName) {
    for (ClassNode classNode : allClasses) {
      if (classNode.interfaces.contains(interfaceName)) {
        return classNode;
      }
    }
    return findClassByName(allClasses, interfaceName);
  }

  private ClassNode findClassByName(List<ClassNode> classes, String name) {
    return classes.stream()
        .filter(c -> getSimpleClassName(c.name).equals(name))
        .findFirst()
        .orElse(null);
  }

  private MethodNode findMethodByName(ClassNode classNode, String name) {
    return classNode.methods.stream()
        .filter(m -> m.name.equals(name))
        .findFirst()
        .orElse(null);
  }

  private String getClassName(String fullMethodName) {
    int lastDotIndex = fullMethodName.lastIndexOf('.');
    if (lastDotIndex == -1) {
      return fullMethodName;
    }
    return fullMethodName.substring(0, lastDotIndex);
  }

  private String getMethodName(String fullMethodName) {
    int lastDotIndex = fullMethodName.lastIndexOf('.');
    if (lastDotIndex == -1) {
      return fullMethodName;
    }
    return fullMethodName.substring(lastDotIndex + 1);
  }

  private String getSimpleClassName(String fullClassName) {
    int lastSlashIndex = fullClassName.lastIndexOf('/');
    if (lastSlashIndex == -1) {
      return fullClassName;
    }
    return fullClassName.substring(lastSlashIndex + 1);
  }
}