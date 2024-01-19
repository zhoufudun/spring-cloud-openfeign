/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import feign.Contract;
import feign.Feign;
import feign.MethodMetadata;
import feign.Param;
import feign.Request;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.openfeign.AnnotatedParameterProcessor;
import org.springframework.cloud.openfeign.CollectionFormat;
import org.springframework.cloud.openfeign.annotation.MatrixVariableParameterProcessor;
import org.springframework.cloud.openfeign.annotation.PathVariableParameterProcessor;
import org.springframework.cloud.openfeign.annotation.QueryMapParameterProcessor;
import org.springframework.cloud.openfeign.annotation.RequestHeaderParameterProcessor;
import org.springframework.cloud.openfeign.annotation.RequestParamParameterProcessor;
import org.springframework.cloud.openfeign.annotation.RequestPartParameterProcessor;
import org.springframework.cloud.openfeign.encoding.HttpEncoding;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;
import static org.springframework.cloud.openfeign.support.FeignUtils.addTemplateParameter;
import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;

/**
 * @author Spencer Gibb
 * @author Abhijit Sarkar
 * @author Halvdan Hoem Grelland
 * @author Aram Peres
 * @author Olga Maciaszek-Sharma
 * @author Aaron Whiteside
 * @author Artyom Romanenko
 * @author Darren Foong
 * @author Ram Anaswara
 */
public class SpringMvcContract extends Contract.BaseContract implements ResourceLoaderAware {

	private static final Log LOG = LogFactory.getLog(SpringMvcContract.class);

	private static final String ACCEPT = "Accept";

	private static final String CONTENT_TYPE = "Content-Type";

	private static final TypeDescriptor STRING_TYPE_DESCRIPTOR = TypeDescriptor.valueOf(String.class);

	private static final TypeDescriptor ITERABLE_TYPE_DESCRIPTOR = TypeDescriptor.valueOf(Iterable.class);

	private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

	private final Map<Class<? extends Annotation>, AnnotatedParameterProcessor> annotatedArgumentProcessors;
	/**
	 * key= BeanUrlClientNoProtocol#getHello()
	 * value= public abstract org.springframework.cloud.openfeign.FeignHttpClientUrlTests$Hello org.springframework.cloud.openfeign.FeignHttpClientUrlTests$BeanUrlClientNoProtocol.getHello()
	 */
	private final Map<String, Method> processedMethods = new HashMap<>();

	private final ConversionService conversionService;

	private final ConvertingExpanderFactory convertingExpanderFactory;

	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	private boolean decodeSlash;

	public SpringMvcContract() {
		this(Collections.emptyList());
	}

	public SpringMvcContract(List<AnnotatedParameterProcessor> annotatedParameterProcessors) {
		this(annotatedParameterProcessors, new DefaultConversionService());
	}

	public SpringMvcContract(List<AnnotatedParameterProcessor> annotatedParameterProcessors,
			ConversionService conversionService) {
		this(annotatedParameterProcessors, conversionService, true);
	}

	public SpringMvcContract(List<AnnotatedParameterProcessor> annotatedParameterProcessors,
			ConversionService conversionService, boolean decodeSlash) {
		Assert.notNull(annotatedParameterProcessors, "Parameter processors can not be null.");
		Assert.notNull(conversionService, "ConversionService can not be null.");

		List<AnnotatedParameterProcessor> processors = getDefaultAnnotatedArgumentsProcessors();
		processors.addAll(annotatedParameterProcessors);

		annotatedArgumentProcessors = toAnnotatedArgumentProcessorMap(processors);
		this.conversionService = conversionService;
		convertingExpanderFactory = new ConvertingExpanderFactory(conversionService);
		this.decodeSlash = decodeSlash;
	}

	private static TypeDescriptor createTypeDescriptor(Method method, int paramIndex) {
		Parameter parameter = method.getParameters()[paramIndex];
		MethodParameter methodParameter = MethodParameter.forParameter(parameter);
		TypeDescriptor typeDescriptor = new TypeDescriptor(methodParameter);

		// Feign applies the Param.Expander to each element of an Iterable, so in those
		// cases we need to provide a TypeDescriptor of the element.
		if (typeDescriptor.isAssignableTo(ITERABLE_TYPE_DESCRIPTOR)) {
			TypeDescriptor elementTypeDescriptor = getElementTypeDescriptor(typeDescriptor);

			checkState(elementTypeDescriptor != null,
					"Could not resolve element type of Iterable type %s. Not declared?", typeDescriptor);

			typeDescriptor = elementTypeDescriptor;
		}
		return typeDescriptor;
	}

	private static TypeDescriptor getElementTypeDescriptor(TypeDescriptor typeDescriptor) {
		TypeDescriptor elementTypeDescriptor = typeDescriptor.getElementTypeDescriptor();
		// that means it's not a collection but it is iterable, gh-135
		if (elementTypeDescriptor == null && Iterable.class.isAssignableFrom(typeDescriptor.getType())) {
			ResolvableType type = typeDescriptor.getResolvableType().as(Iterable.class).getGeneric(0);
			if (type.resolve() == null) {
				return null;
			}
			return new TypeDescriptor(type, null, typeDescriptor.getAnnotations());
		}
		return elementTypeDescriptor;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	/**
	 * 通过 findMergedAnnotation 方法查找类上的合并注解 @RequestMapping。
	 * 如果找到 @RequestMapping 注解，表示在 @FeignClient 接口上使用了不允许的注解，将抛出异常并输出错误信息
	 *
	 * @param data metadata collected so far relating to the current java method.
	 * @param clz the class to process
	 */
	@Override
	protected void processAnnotationOnClass(MethodMetadata data, Class<?> clz) { // clz=interface org.springframework.cloud.openfeign.FeignHttpClientUrlTests$BeanUrlClientNoProtocol
			// 查找类上的合并注解 @RequestMapping
		RequestMapping classAnnotation = findMergedAnnotation(clz, RequestMapping.class); // @FeignClient从不支持RequestMapping
		// 如果找到 @RequestMapping 注解，抛出异常，不允许在 @FeignClient 接口上使用 @RequestMapping 注解
		if (classAnnotation != null) {
			LOG.error("无法处理类：" + clz.getName()
				+ "。@FeignClient 接口上不允许使用 @RequestMapping 注解。");
			throw new IllegalArgumentException("@FeignClient 接口上不允许使用 @RequestMapping 注解");
		}
	}

	@Override
	public MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
		processedMethods.put(Feign.configKey(targetType, method), method);
		return super.parseAndValidateMetadata(targetType, method);
	}

	/**
	 * 简要解释：
	 * 该方法用于处理在 Feign 客户端接口方法上的注解，包括 CollectionFormat、RequestMapping 等。
	 * 解析集合格式、HTTP 方法、路径、produces、consumes、headers 等属性，并将解析结果存储在 MethodMetadata 中。
	 * 最终生成 Feign 客户端方法的模板信息
	 *
	 * @param data metadata collected so far relating to the current java method.
	 * @param methodAnnotation annotations present on the current method annotation.
	 * @param method method currently being processed.
	 */
	@Override
	protected void processAnnotationOnMethod(MethodMetadata data, Annotation methodAnnotation, Method method) {
		// 如果注解类型是 CollectionFormat，处理集合格式
		if (CollectionFormat.class.isInstance(methodAnnotation)) { // @org.springframework.web.bind.annotation.GetMapping(path=[], headers=[], name=, produces=[], params=[], value=[/hello], consumes=[])
			CollectionFormat collectionFormat = findMergedAnnotation(method, CollectionFormat.class);
			data.template().collectionFormat(collectionFormat.value());
		}

		// 如果注解不是 RequestMapping，且注解类型上没有 RequestMapping 注解，则直接返回
		if (!RequestMapping.class.isInstance(methodAnnotation)
			&& !methodAnnotation.annotationType().isAnnotationPresent(RequestMapping.class)) {
			return;
		}

		// 查找方法上合并的 RequestMapping 注解
		RequestMapping methodMapping = findMergedAnnotation(method, RequestMapping.class);

		// 解析 HTTP 方法
		RequestMethod[] methods = methodMapping.method();
		if (methods.length == 0) {
			methods = new RequestMethod[]{RequestMethod.GET};
		}
		checkOne(method, methods, "method");
		data.template().method(Request.HttpMethod.valueOf(methods[0].name()));

		// 解析路径
		checkAtMostOne(method, methodMapping.value(), "value");
		if (methodMapping.value().length > 0) {
			String pathValue = emptyToNull(methodMapping.value()[0]);
			if (pathValue != null) {
				pathValue = resolve(pathValue); // /hello

				// 如果方法上的路径值存在，将其添加到模板路径中
				if (!pathValue.startsWith("/") && !data.template().path().endsWith("/")) {
					pathValue = "/" + pathValue; // /hello
				}

				data.template().uri(pathValue, true);

				// 处理是否解码斜杠的配置
				if (data.template().decodeSlash() != decodeSlash) {
					data.template().decodeSlash(decodeSlash);
				}
			}
		}

		// 解析 produces 属性
		parseProduces(data, method, methodMapping);

		// 解析 consumes 属性
		parseConsumes(data, method, methodMapping);

		// 解析 headers 属性
		parseHeaders(data, method, methodMapping);

		// 初始化索引到扩展器的映射
		data.indexToExpander(new LinkedHashMap<>());
	}

	/**
	 * 简要解释：
	 *
	 * resolve 方法用于解析字符串中的占位符。
	 * 首先，检查字符串是否非空且包含文本。
	 * 然后，检查资源加载器是否是可配置的应用上下文类型。
	 * 如果满足条件，使用应用上下文的环境对象解析占位符，返回解析后的值。
	 * 如果不满足解析条件，直接返回原始值
	 * @param value
	 * @return
	 */
	private String resolve(String value) {
		// 检查字符串是否非空且包含文本
		if (StringUtils.hasText(value) && resourceLoader instanceof ConfigurableApplicationContext) {
			// 如果字符串非空且资源加载器是可配置的应用上下文类型，则使用环境对象解析占位符
			return ((ConfigurableApplicationContext) resourceLoader).getEnvironment().resolvePlaceholders(value);
		}
		// 如果不满足解析条件，直接返回原始值
		return value;
	}

	private void checkAtMostOne(Method method, Object[] values, String fieldName) {
		checkState(values != null && (values.length == 0 || values.length == 1),
				"Method %s can only contain at most 1 %s field. Found: %s", method.getName(), fieldName,
				values == null ? null : Arrays.asList(values));
	}

	private void checkOne(Method method, Object[] values, String fieldName) {
		checkState(values != null && values.length == 1, "Method %s can only contain 1 %s field. Found: %s",
				method.getName(), fieldName, values == null ? null : Arrays.asList(values));
	}

	/**
	 * 简要解释：
	 *
	 * 该方法用于处理方法参数上的注解，包括处理参数注解的合成、调用对应的处理器进行处理等。
	 * 遍历参数上的注解，获取注解类型对应的处理器，并调用处理器进行处理。
	 * 在处理注解时，会对参数注解进行合成处理，处理 @AliasFor，并在缺少 String #value() 时回退到参数名称。
	 * 如果不是上传文件类型，存在 HTTP 相关的注解，并且参数索引对应的扩展器为空，则尝试获取对应的 Param.Expander，并将其添加到索引中
	 *
	 * @param data metadata collected so far relating to the current java method.
	 * @param annotations annotations present on the current parameter annotation.
	 * @param paramIndex if you find a name in {@code annotations}, call
	 *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
	 * @return
	 */
	@Override
	protected boolean processAnnotationsOnParameter(MethodMetadata data, Annotation[] annotations, int paramIndex) {
		boolean isHttpAnnotation = false;

		// 创建 AnnotatedParameterContext 对象，用于处理参数上的注解
		AnnotatedParameterProcessor.AnnotatedParameterContext context = new SimpleAnnotatedParameterContext(data,
			paramIndex);

		// 获取当前方法
		Method method = processedMethods.get(data.configKey());

		// 遍历参数上的注解
		for (Annotation parameterAnnotation : annotations) {
			// 获取参数注解对应的 AnnotatedParameterProcessor 处理器
			AnnotatedParameterProcessor processor = annotatedArgumentProcessors
				.get(parameterAnnotation.annotationType());

			// 如果处理器存在，则处理参数注解
			if (processor != null) {
				Annotation processParameterAnnotation;
				// 合成处理参数注解，处理 @AliasFor，并在缺少 String #value() 时回退到参数名称
				processParameterAnnotation = synthesizeWithMethodParameterNameAsFallbackValue(parameterAnnotation,
					method, paramIndex);
				isHttpAnnotation |= processor.processArgument(context, processParameterAnnotation, method);
			}
		}

		// 如果不是上传文件类型，且存在 HTTP 相关的注解，并且参数索引对应的扩展器为空
		if (!isMultipartFormData(data) && isHttpAnnotation && data.indexToExpander().get(paramIndex) == null) {
			// 创建参数类型描述符
			TypeDescriptor typeDescriptor = createTypeDescriptor(method, paramIndex);

			// 判断是否可以进行类型转换为字符串
			if (conversionService.canConvert(typeDescriptor, STRING_TYPE_DESCRIPTOR)) {
				// 获取对应的 Param.Expander，并将其添加到索引中
				Param.Expander expander = convertingExpanderFactory.getExpander(typeDescriptor);
				if (expander != null) {
					data.indexToExpander().put(paramIndex, expander);
				}
			}
		}
		return isHttpAnnotation;
	}


	private void parseProduces(MethodMetadata md, Method method, RequestMapping annotation) {
		String[] serverProduces = annotation.produces();
		String clientAccepts = serverProduces.length == 0 ? null : emptyToNull(serverProduces[0]);
		if (clientAccepts != null) {
			md.template().header(ACCEPT, clientAccepts);
		}
	}

	private void parseConsumes(MethodMetadata md, Method method, RequestMapping annotation) {
		String[] serverConsumes = annotation.consumes();
		String clientProduces = serverConsumes.length == 0 ? null : emptyToNull(serverConsumes[0]);
		if (clientProduces != null) {
			md.template().header(CONTENT_TYPE, clientProduces);
		}
	}

	private void parseHeaders(MethodMetadata md, Method method, RequestMapping annotation) {
		// TODO: only supports one header value per key
		if (annotation.headers() != null && annotation.headers().length > 0) {
			for (String header : annotation.headers()) {
				int index = header.indexOf('=');
				if (!header.contains("!=") && index >= 0) {
					md.template().header(resolve(header.substring(0, index)),
							resolve(header.substring(index + 1).trim()));
				}
			}
		}
	}

	private Map<Class<? extends Annotation>, AnnotatedParameterProcessor> toAnnotatedArgumentProcessorMap(
			List<AnnotatedParameterProcessor> processors) {
		Map<Class<? extends Annotation>, AnnotatedParameterProcessor> result = new HashMap<>();
		for (AnnotatedParameterProcessor processor : processors) {
			result.put(processor.getAnnotationType(), processor);
		}
		return result;
	}

	private List<AnnotatedParameterProcessor> getDefaultAnnotatedArgumentsProcessors() {

		List<AnnotatedParameterProcessor> annotatedArgumentResolvers = new ArrayList<>();

		annotatedArgumentResolvers.add(new MatrixVariableParameterProcessor());
		annotatedArgumentResolvers.add(new PathVariableParameterProcessor());
		annotatedArgumentResolvers.add(new RequestParamParameterProcessor());
		annotatedArgumentResolvers.add(new RequestHeaderParameterProcessor());
		annotatedArgumentResolvers.add(new QueryMapParameterProcessor());
		annotatedArgumentResolvers.add(new RequestPartParameterProcessor());

		return annotatedArgumentResolvers;
	}

	private Annotation synthesizeWithMethodParameterNameAsFallbackValue(Annotation parameterAnnotation, Method method,
			int parameterIndex) {
		Map<String, Object> annotationAttributes = AnnotationUtils.getAnnotationAttributes(parameterAnnotation);
		Object defaultValue = AnnotationUtils.getDefaultValue(parameterAnnotation);
		if (defaultValue instanceof String && defaultValue.equals(annotationAttributes.get(AnnotationUtils.VALUE))) {
			Type[] parameterTypes = method.getGenericParameterTypes();
			String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
			if (shouldAddParameterName(parameterIndex, parameterTypes, parameterNames)) {
				annotationAttributes.put(AnnotationUtils.VALUE, parameterNames[parameterIndex]);
			}
		}
		return AnnotationUtils.synthesizeAnnotation(annotationAttributes, parameterAnnotation.annotationType(), null);
	}

	private boolean shouldAddParameterName(int parameterIndex, Type[] parameterTypes, String[] parameterNames) {
		// has a parameter name
		return parameterNames != null && parameterNames.length > parameterIndex
		// has a type
				&& parameterTypes != null && parameterTypes.length > parameterIndex;
	}

	private boolean isMultipartFormData(MethodMetadata data) {
		Collection<String> contentTypes = data.template().headers().get(HttpEncoding.CONTENT_TYPE);

		if (contentTypes != null && !contentTypes.isEmpty()) {
			String type = contentTypes.iterator().next();
			try {
				return Objects.equals(MediaType.valueOf(type), MediaType.MULTIPART_FORM_DATA);
			}
			catch (InvalidMediaTypeException ignored) {
				return false;
			}
		}

		return false;
	}

	private static class ConvertingExpanderFactory {

		private final ConversionService conversionService;

		ConvertingExpanderFactory(ConversionService conversionService) {
			this.conversionService = conversionService;
		}

		Param.Expander getExpander(TypeDescriptor typeDescriptor) {
			return value -> {
				Object converted = conversionService.convert(value, typeDescriptor, STRING_TYPE_DESCRIPTOR);
				return (String) converted;
			};
		}

	}

	private class SimpleAnnotatedParameterContext implements AnnotatedParameterProcessor.AnnotatedParameterContext {

		private final MethodMetadata methodMetadata;

		private final int parameterIndex;

		SimpleAnnotatedParameterContext(MethodMetadata methodMetadata, int parameterIndex) {
			this.methodMetadata = methodMetadata;
			this.parameterIndex = parameterIndex;
		}

		@Override
		public MethodMetadata getMethodMetadata() {
			return methodMetadata;
		}

		@Override
		public int getParameterIndex() {
			return parameterIndex;
		}

		@Override
		public void setParameterName(String name) {
			nameParam(methodMetadata, name, parameterIndex);
		}

		@Override
		public Collection<String> setTemplateParameter(String name, Collection<String> rest) {
			return addTemplateParameter(rest, name);
		}

	}

}
