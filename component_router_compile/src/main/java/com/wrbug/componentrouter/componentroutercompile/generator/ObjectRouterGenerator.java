package com.wrbug.componentrouter.componentroutercompile.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.wrbug.componentrouter.ComponentRouterInstance;
import com.wrbug.componentrouter.ComponentRouterProxy;
import com.wrbug.componentrouter.EmptyPathException;
import com.wrbug.componentrouter.annotation.ConstructorRouter;
import com.wrbug.componentrouter.annotation.ObjectRoute;
import com.wrbug.componentrouter.annotation.SingletonRouter;
import com.wrbug.componentrouter.componentroutercompile.TypeNameUtils;
import com.wrbug.componentrouter.componentroutercompile.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import static com.wrbug.componentrouter.componentroutercompile.Constant.*;

public class ObjectRouterGenerator extends ElementGenerator {


    public ObjectRouterGenerator(Filer filer, Log log) {
        super(filer, log);
    }

    @Override
    public TypeSpec onCreateTypeSpec(TypeElement element, String packageName, String className) throws EmptyPathException {
        ObjectRoute objectRoute = element.getAnnotation(ObjectRoute.class);
        String path = objectRoute.value();
        if (path.isEmpty()) {
            throw new EmptyPathException(element.toString());
        }
        ClassName routeType = ClassName.get(packageName, className);
        TypeSpec.Builder builder = TypeSpec.classBuilder(className + INSTANCE_PROXY_SUFFIX)
                .addSuperinterface(ComponentRouterInstance.class)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(ComponentRouterProxy.class, INSTANCE_COMPONENT_ROUTER_PROXY_FIELD_NAME, Modifier.PRIVATE)
                .addField(routeType, INSTANCE_FIELD_NAME, Modifier.PRIVATE);
        buildConstructor(builder, element, routeType);
        generateInterfaceMethod(builder);
        return builder.build();
    }

    private void generateInterfaceMethod(TypeSpec.Builder builder) {
        generateGetProxyMethod(builder);
        generateGetInstanceMethod(builder);
    }

    private void buildConstructor(TypeSpec.Builder builder, TypeElement element, ClassName targetClassName) {
        ClassName className = ClassName.get(PACKAGE_NAME, FINDER_CLASS_NAME);
        MethodSpec.Builder methodBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("initInstance(params)")
                .beginControlFlow("if($L!=null)", INSTANCE_FIELD_NAME)
                .addStatement("$L=($T)$T.get($L)", INSTANCE_COMPONENT_ROUTER_PROXY_FIELD_NAME, ComponentRouterProxy.class, className, INSTANCE_FIELD_NAME)
                .endControlFlow()
                .addParameter(Object[].class, "params", Modifier.FINAL).varargs();
        builder.addMethod(methodBuilder.build());
        buildInitMethod(builder, element, targetClassName);
    }

    private void buildInitMethod(TypeSpec.Builder builder, TypeElement element, ClassName targetClassName) {
        String paramsName = "params";
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("initInstance")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .addParameter(Object[].class, paramsName).varargs();
        List<? extends Element> enclosedElements = element.getEnclosedElements();
        Map<Integer, List<StringBuilder[]>> map = new TreeMap<>();
        boolean isSingleton = false;
        for (Element enclosedElement : enclosedElements) {
            SingletonRouter singletonRouter = enclosedElement.getAnnotation(SingletonRouter.class);
            if (singletonRouter != null) {
                isSingleton = true;
                singletonBuilder(methodBuilder, enclosedElement, paramsName, targetClassName);
                break;
            }
        }
        if (!isSingleton) {
            for (Element enclosedElement : enclosedElements) {
                instanceConstructorBuilder(methodBuilder, enclosedElement, map, paramsName, targetClassName);
            }
            if (!map.isEmpty()) {
                for (Map.Entry<Integer, List<StringBuilder[]>> entry : map.entrySet()) {
                    Integer key = entry.getKey();
                    List<StringBuilder[]> value = entry.getValue();
                    methodBuilder.beginControlFlow("if($L.length==$L)", paramsName, key);
                    for (StringBuilder[] builders : value) {
                        StringBuilder code = builders[0];
                        StringBuilder argBuilder = builders[1];
                        methodBuilder.beginControlFlow("if($L)", code.toString());
                        methodBuilder.addStatement("$L=new $T($L)", INSTANCE_FIELD_NAME, targetClassName, argBuilder.toString());
                        methodBuilder.addStatement("return");
                        methodBuilder.endControlFlow();
                    }
                    methodBuilder.endControlFlow();

                }
            } else {
                methodBuilder.addStatement("$L=new $T()", INSTANCE_FIELD_NAME, targetClassName);
            }
        }

        builder.addField(FieldSpec.builder(TypeName.BOOLEAN, "isSingleton", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("$L", isSingleton).addJavadoc("是否为单例，{@link com.wrbug.componentrouter.annotation.SingletonRouter}").build());
        builder.addMethod(methodBuilder.build());
    }

    private void singletonBuilder(MethodSpec.Builder methodBuilder, Element enclosedElement, String paramsName, ClassName targetClassName) {
        String str = enclosedElement.toString();
        str = str.substring(str.indexOf("(") + 1, str.indexOf(")"));
        if (str.isEmpty()) {
            methodBuilder.addStatement("$L=$T.$L()", INSTANCE_FIELD_NAME, targetClassName, enclosedElement.getSimpleName());
            return;
        }
        String[] argTypes = str.split(",");
        int len = argTypes.length;
        methodBuilder.addStatement("int paramsLen=$L==null?0:$L.length", paramsName, paramsName);
        methodBuilder.beginControlFlow("if(paramsLen==$L)", len).addStatement("boolean match=true");
        StringBuilder builder = new StringBuilder();
        StringBuilder builder1 = new StringBuilder();
        for (int i = 0; i < argTypes.length; i++) {
            methodBuilder.beginControlFlow("if($L[$L]!=null&&!$L.class.isAssignableFrom($L[$L].getClass()))", paramsName, i, argTypes[i], paramsName, i)
                    .addStatement("match=false")
                    .endControlFlow();
            if (builder.length() > 0) {
                builder.append(",\n");
                builder1.append(",");
            }
            builder.append(paramsName).append("[").append(i).append("]!=null?").append("(").append(argTypes[i]).append(")").append(paramsName).append("[").append(i).append("]:").append(TypeNameUtils.getDefaultValue(argTypes[i]));
            builder1.append(TypeNameUtils.getDefaultValue(argTypes[i]));
        }
        methodBuilder.beginControlFlow("if(match)")
                .addStatement("$L=$T.$L($L)", INSTANCE_FIELD_NAME, targetClassName, enclosedElement.getSimpleName(), builder.toString())
                .addStatement("return")
                .endControlFlow();
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("$L=$T.$L($L)", INSTANCE_FIELD_NAME, targetClassName, enclosedElement.getSimpleName(), builder1.toString());

    }

    private void instanceConstructorBuilder(MethodSpec.Builder methodBuilder, Element enclosedElement, Map<Integer, List<StringBuilder[]>> map, String paramsName, ClassName targetClassName) {
        ConstructorRouter annotation = enclosedElement.getAnnotation(ConstructorRouter.class);
        if (annotation == null) {
            return;
        }
        String str = enclosedElement.toString();
        str = str.substring(str.indexOf("(") + 1, str.indexOf(")"));
        if (str.isEmpty()) {
            methodBuilder.beginControlFlow("if($L==null||$L.length==0)", paramsName, paramsName);
            methodBuilder.addStatement("$L=new $T()", INSTANCE_FIELD_NAME, targetClassName);
            methodBuilder.addStatement("return");
            methodBuilder.endControlFlow();
            return;
        }
        String[] argTypes = str.split(",");
        int len = argTypes.length;
        if (argTypes[len - 1].endsWith("...")) {
            return;
        }
        List<StringBuilder[]> stringBuilders = map.get(len);
        if (stringBuilders == null) {
            stringBuilders = new ArrayList<>();
            map.put(len, stringBuilders);
        }
        StringBuilder[] builders = new StringBuilder[2];
        stringBuilders.add(builders);
        StringBuilder code = new StringBuilder();
        StringBuilder argBuilder = new StringBuilder();
        builders[0] = code;
        builders[1] = argBuilder;
        for (int i = 0; i < argTypes.length; i++) {
            String argType = argTypes[i];
            if (code.length() > 0) {
                code.append("\n&&");
                argBuilder.append(",");
            }
            code.append("(").append(argType).append(".class.isAssignableFrom(").append(paramsName).append("[").append(i).append("].getClass()))");
            argBuilder.append("(").append(argType).append(")").append(paramsName).append("[").append(i).append("]");
        }
    }

    private void generateGetInstanceMethod(TypeSpec.Builder builder) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("getInstance")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .returns(Object.class)
                .addStatement("return $L", INSTANCE_FIELD_NAME);
        builder.addMethod(methodBuilder.build());
    }

    private void generateGetProxyMethod(TypeSpec.Builder builder) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("getProxy")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .returns(ComponentRouterProxy.class)
                .addStatement("return $L", INSTANCE_COMPONENT_ROUTER_PROXY_FIELD_NAME);
        builder.addMethod(methodBuilder.build());
    }
}
